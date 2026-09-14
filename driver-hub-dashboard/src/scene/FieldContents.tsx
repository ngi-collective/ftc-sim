import { Html } from '@react-three/drei';
import { useEffect, useMemo, useRef } from 'react';
import * as THREE from 'three';
import type { SceneContents, SceneElement, SceneTag } from '../protocol';

/**
 * Everything on the field that is not the robot: the AprilTags the camera can detect and the game
 * elements lying about, in the scene frame — {@code sceneX = ftcX}, {@code sceneZ = -ftcY}, and
 * three.js Y is the field frame's Z.
 *
 * <p>Nothing here is inferred. Corners, cell patterns and colours all come off {@code sim/scene}
 * exactly as the server's renderer used them, because the whole point of drawing them here is that
 * the two views agree: a tag this file placed from a pose of its own would be a claim about what
 * the camera can see rather than a report of it.</p>
 */

/** Stable empties, so a scene-less session does not rebuild the caches below on every render. */
const NO_TAGS: SceneTag[] = [];
const NO_ELEMENTS: SceneElement[] = [];

/**
 * Two triangles over the corners [topLeft, topRight, bottomRight, bottomLeft], wound
 * counter-clockwise as seen from the tag's visible face — which is what makes that face the front
 * face, and so what makes a culled tag face the camera instead of hiding from it.
 */
const QUAD_INDICES = [0, 3, 2, 0, 2, 1];

/** Matched to that corner order. Canvas textures arrive flipped, so v = 1 is the pattern's top row. */
const QUAD_UVS = [0, 1, 1, 1, 1, 0, 0, 0];

const LABEL_COLOUR = '#7f9bb3';

/**
 * The pattern at one pixel per cell, magnified and minified with nearest filtering.
 *
 * <p>Filtering is the trap: interpolate a 8×8 tag up to a few hundred screen pixels and the borders
 * bleed into grey, which reads as a blurry photograph of a tag rather than the hard-edged thing a
 * detector thresholds. Cells stay squares at every distance.</p>
 */
function paintTag(cells: string[]): THREE.CanvasTexture {
  const rows = cells.length;
  const columns = rows === 0 ? 0 : cells[0].length;
  const canvas = document.createElement('canvas');
  canvas.width = Math.max(columns, 1);
  canvas.height = Math.max(rows, 1);
  const context = canvas.getContext('2d');
  if (context) {
    for (let row = 0; row < rows; row += 1) {
      const line = cells[row];
      for (let column = 0; column < columns; column += 1) {
        context.fillStyle = line[column] === 'W' ? '#ffffff' : '#000000';
        context.fillRect(column, row, 1, 1);
      }
    }
  }
  const texture = new THREE.CanvasTexture(canvas);
  texture.magFilter = THREE.NearestFilter;
  texture.minFilter = THREE.NearestFilter;
  texture.generateMipmaps = false;
  texture.colorSpace = THREE.SRGBColorSpace;
  return texture;
}

/**
 * One texture per tag id, kept across renders and disposed when its tag leaves the scene.
 *
 * <p>The cache is the ref; the memo only reconciles it against the tags that are actually here. A
 * canvas per frame would be both a leak and a stutter, and {@code sim/scene} re-arrives whole
 * whenever anything in the world moves.</p>
 */
function useTagTextures(tags: SceneTag[]): Map<number, THREE.CanvasTexture> {
  const cache = useRef(new Map<number, THREE.CanvasTexture>());
  const textures = useMemo(() => {
    const live = new Map<number, THREE.CanvasTexture>();
    for (const tag of tags) {
      const existing = cache.current.get(tag.id);
      live.set(tag.id, existing ?? paintTag(tag.cells));
    }
    for (const [id, texture] of cache.current) {
      if (!live.has(id)) texture.dispose();
    }
    cache.current = live;
    return live;
  }, [tags]);
  useEffect(
    () => () => {
      for (const texture of cache.current.values()) texture.dispose();
      cache.current.clear();
    },
    [],
  );
  return textures;
}

/**
 * One tag as the quad its corners describe, not as a plane placed at a pose: four points are
 * unambiguous, whereas orienting a plane from a pose is where a mirrored — and therefore
 * undetectable — tag comes from.
 */
function TagQuad({ tag, texture }: { tag: SceneTag; texture: THREE.CanvasTexture }) {
  const geometry = useMemo(() => {
    const positions: number[] = [];
    for (const corner of tag.corners) positions.push(corner.x, corner.z, -corner.y);
    const built = new THREE.BufferGeometry();
    built.setAttribute('position', new THREE.Float32BufferAttribute(positions, 3));
    built.setAttribute('uv', new THREE.Float32BufferAttribute(QUAD_UVS, 2));
    built.setIndex(QUAD_INDICES);
    built.computeVertexNormals();
    return built;
  }, [tag.corners]);
  useEffect(() => () => geometry.dispose(), [geometry]);

  return (
    <mesh geometry={geometry}>
      {/*
        Backface-culled, and unlit so white stays white. A real tag is printed on the front of
        something opaque: drawing its back would show a pattern from an angle no camera could ever
        detect it from, and the tag vanishing as you orbit behind it is the honest answer.
      */}
      <meshBasicMaterial map={texture} side={THREE.FrontSide} toneMapped={false} />
    </mesh>
  );
}

/** A cluster's name, floating over the tags that belong to it. */
interface ClusterLabel {
  cluster: string;
  position: [number, number, number];
}

/**
 * Centroid of every corner in the cluster, lifted by its largest tag so the text clears the tags
 * themselves rather than sitting in the middle of the pattern it is naming.
 */
function clusterLabels(tags: SceneTag[]): ClusterLabel[] {
  const sums = new Map<string, { x: number; y: number; z: number; corners: number; lift: number }>();
  for (const tag of tags) {
    const sum = sums.get(tag.cluster) ?? { x: 0, y: 0, z: 0, corners: 0, lift: 0 };
    for (const corner of tag.corners) {
      sum.x += corner.x;
      sum.y += corner.y;
      sum.z += corner.z;
      sum.corners += 1;
    }
    sum.lift = Math.max(sum.lift, tag.sizeMetres);
    sums.set(tag.cluster, sum);
  }

  const labels: ClusterLabel[] = [];
  sums.forEach((sum, cluster) => {
    if (sum.corners === 0) return;
    labels.push({
      cluster,
      position: [sum.x / sum.corners, sum.z / sum.corners + sum.lift, -(sum.y / sum.corners)],
    });
  });
  return labels;
}

/** One ball, already paired with the material its colour resolved to. */
interface Ball {
  key: string;
  position: [number, number, number];
  radius: number;
  material: THREE.MeshStandardMaterial;
}

/**
 * The game elements as spheres at the centres the server published, in their own colours — the same
 * colours the camera renders them in, so a driver matching a blob on the camera panel to a ball on
 * the field is matching like to like.
 */
function GameElements({ elements }: { elements: SceneElement[] }) {
  // One unit sphere scaled per element, and one material per distinct colour: a floor covered in
  // identical pollen costs draw calls, not uploads.
  const geometry = useMemo(() => new THREE.SphereGeometry(1, 16, 12), []);
  useEffect(() => () => geometry.dispose(), [geometry]);

  const { balls, materials } = useMemo(() => {
    const byColour = new Map<number, THREE.MeshStandardMaterial>();
    const built: Ball[] = [];
    elements.forEach((element, index) => {
      const packed = (element.red << 16) | (element.green << 8) | element.blue;
      let material = byColour.get(packed);
      if (!material) {
        material = new THREE.MeshStandardMaterial({
          color: new THREE.Color().setStyle(`rgb(${element.red},${element.green},${element.blue})`),
          roughness: 0.55,
        });
        byColour.set(packed, material);
      }
      built.push({
        key: `${element.name}:${index}`,
        position: [element.x, element.z, -element.y],
        radius: element.radiusMetres,
        material,
      });
    });
    return { balls: built, materials: byColour };
  }, [elements]);
  useEffect(
    () => () => {
      for (const material of materials.values()) material.dispose();
    },
    [materials],
  );

  return (
    <>
      {balls.map((ball) => (
        <mesh
          key={ball.key}
          geometry={geometry}
          material={ball.material}
          position={ball.position}
          scale={ball.radius}
          castShadow
        />
      ))}
    </>
  );
}

export function FieldContents({ contents }: { contents: SceneContents | null }) {
  const tags = contents ? contents.tags : NO_TAGS;
  const elements = contents ? contents.elements : NO_ELEMENTS;
  const textures = useTagTextures(tags);
  const labels = useMemo(() => clusterLabels(tags), [tags]);

  return (
    <group>
      {tags.map((tag) => {
        const texture = textures.get(tag.id);
        return texture ? <TagQuad key={tag.id} tag={tag} texture={texture} /> : null;
      })}

      <GameElements elements={elements} />

      {labels.map((label) => (
        <Html
          key={label.cluster}
          position={label.position}
          center
          distanceFactor={3}
          pointerEvents="none"
          // Same band as the wall labels: the camera panel is layered above this, not between.
          zIndexRange={[5, 0]}
        >
          <div
            style={{
              fontFamily: 'ui-monospace, monospace',
              fontSize: 11,
              letterSpacing: 2,
              color: LABEL_COLOUR,
              whiteSpace: 'nowrap',
            }}
          >
            {label.cluster}
          </div>
        </Html>
      ))}
    </group>
  );
}
