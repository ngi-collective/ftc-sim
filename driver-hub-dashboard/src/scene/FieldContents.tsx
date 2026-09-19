import { Html } from '@react-three/drei';
import { useFrame } from '@react-three/fiber';
import { useEffect, useMemo, useRef } from 'react';
import * as THREE from 'three';
import type { SceneElement, ScenePayload, SceneStructure, SceneTag } from '../protocol';
import type { BodyBuffer } from './bodies';
import { scenePoint } from './frame';
import {
  pivotLocal,
  pivotTurnRadians,
  splitByPivot,
  type PivotGroup,
  type PivotPlacement,
  type PivotSplit,
} from './pivots';
import { solidGeometry, solidGeometryKey, solidPose, type SolidPose } from './solids';

/**
 * Everything on the field that is not the robot: the AprilTags the camera can detect, the game
 * elements lying about and the structures the field is furnished with, converted out of the field
 * frame by {@code frame.ts} and {@code solids.ts} — which are the only places those conversions
 * are written.
 *
 * <p>Nothing here is inferred. Corners, cell patterns and colours all come off {@code sim/scene}
 * exactly as the server's renderer used them, because the whole point of drawing them here is that
 * the two views agree: a tag this file placed from a pose of its own would be a claim about what
 * the camera can see rather than a report of it.</p>
 */

/** Stable empties, so a scene-less session does not rebuild the caches below on every render. */
const NO_TAGS: SceneTag[] = [];
const NO_ELEMENTS: SceneElement[] = [];
const NO_STRUCTURES: SceneStructure[] = [];

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
 *
 * <p>{@code placement} is the hinge this tag is bolted to, or null when it is bolted to the field.
 * A tag that rides a hinge is built in that hinge's frame, because it is drawn as a child of the
 * hinge's group: leave the corners absolute and the group's own offset is applied to them twice,
 * which throws the tag a metre off the CELL it is printed on.</p>
 */
function TagQuad({
  tag,
  texture,
  placement,
}: {
  tag: SceneTag;
  texture: THREE.CanvasTexture;
  placement: PivotPlacement | null;
}) {
  const geometry = useMemo(() => {
    const positions: number[] = [];
    for (const corner of tag.corners) {
      const point = scenePoint(corner);
      positions.push(...(placement ? pivotLocal(point, placement) : point));
    }
    const built = new THREE.BufferGeometry();
    built.setAttribute('position', new THREE.Float32BufferAttribute(positions, 3));
    built.setAttribute('uv', new THREE.Float32BufferAttribute(QUAD_UVS, 2));
    built.setIndex(QUAD_INDICES);
    built.computeVertexNormals();
    return built;
  }, [tag.corners, placement]);
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
export interface ClusterLabel {
  cluster: string;
  position: [number, number, number];
}

/**
 * Centroid of every corner in the cluster, lifted by its largest tag so the text clears the tags
 * themselves rather than sitting in the middle of the pattern it is naming.
 */
export function clusterLabels(tags: SceneTag[]): ClusterLabel[] {
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
    const [x, y, z] = scenePoint({
      x: sum.x / sum.corners,
      y: sum.y / sum.corners,
      z: sum.z / sum.corners,
    });
    labels.push({ cluster, position: [x, y + sum.lift, z] });
  });
  return labels;
}

/**
 * The cluster names, as DOM text the canvas composites over itself.
 *
 * <p>A component rather than inline markup because the labels are drawn twice over: once for the
 * clusters bolted to the field and once inside each hinge's group, where the text has to swing
 * with the tags it is naming. Two copies of this markup would be two places for the label band to
 * drift out of step with the wall labels.</p>
 *
 * <p>{@code placement} is that hinge, or null for a label that never moves; see {@link TagQuad}
 * for why a child of the group is positioned relative to it.</p>
 */
function ClusterLabels({
  labels,
  placement,
}: {
  labels: ClusterLabel[];
  placement: PivotPlacement | null;
}) {
  return (
    <>
      {labels.map((label) => (
        <Html
          key={label.cluster}
          position={placement ? pivotLocal(label.position, placement) : label.position}
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
    </>
  );
}

/** One ball, already paired with the material its colour resolved to. */
interface Ball {
  key: string;
  /** Its id on the wire, which is how a body frame finds this mesh again. */
  id: number;
  position: [number, number, number];
  radius: number;
  material: THREE.MeshStandardMaterial;
}

/**
 * The game elements as spheres, in their own colours — the same colours the camera renders them
 * in, so a driver matching a blob on the camera panel to a ball on the field is matching like to
 * like.
 *
 * <p>Positions come from {@code sim/scene} once and from {@code sim/bodies} thereafter, and the
 * second path never touches React: a mesh's transform is written straight in the render loop, the
 * same way the robot's chassis is. Committing fifty position updates a second to state would
 * re-render the console log and the device rail along with the canvas, which is the trade
 * {@code messages.ts} already made for the pose.</p>
 */
function GameElements({ elements, bodies }: { elements: SceneElement[]; bodies: BodyBuffer }) {
  // One unit sphere scaled per element, and one material per distinct colour: a floor covered in
  // identical pollen costs draw calls, not uploads.
  const geometry = useMemo(() => new THREE.SphereGeometry(1, 16, 12), []);
  useEffect(() => () => geometry.dispose(), [geometry]);

  const { balls, materials } = useMemo(() => {
    const byColour = new Map<number, THREE.MeshStandardMaterial>();
    const built: Ball[] = [];
    for (const element of elements) {
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
        key: `${element.id}:${element.name}`,
        id: element.id,
        position: scenePoint(element),
        radius: element.radiusMetres,
        material,
      });
    }
    return { balls: built, materials: byColour };
  }, [elements]);
  useEffect(
    () => () => {
      for (const material of materials.values()) material.dispose();
    },
    [materials],
  );

  // Keyed by id rather than by index, because the two payloads are joined on the id and a mesh
  // holding the wrong one would be drawn in another ball's colour.
  const meshes = useRef(new Map<number, THREE.Mesh>());

  useFrame(() => {
    if (!bodies.hasFrame()) {
      // Nothing has ever been said about a body, so the scene is the only truth — and it may have
      // just changed, because INIT rebuilds the physics world and puts every ball back where the
      // scenario placed it.
      //
      // These positions are also on the meshes as a prop, and that is not enough: R3F skips
      // applying a prop whose numbers match what it applied last time, and a rearranged field
      // usually *is* the same numbers as the arrangement before it. So the mesh keeps whatever
      // this loop last wrote, and the balls stay drawn wherever they were shoved to — for as long
      // as nothing moves, which is exactly when a driver is looking at a field they think is
      // reset. Writing unconditionally makes this loop the only thing that positions a ball.
      //
      // Asked as "did the sample come back empty" instead, a frame that carries pivots and no
      // bodies — an ordinary frame, once a HIVE goes on swinging after the last ball has settled —
      // would read as silence and throw every ball back to its scenario position mid-match.
      for (const ball of balls) {
        const mesh = meshes.current.get(ball.id);
        if (!mesh) continue;
        mesh.position.set(ball.position[0], ball.position[1], ball.position[2]);
        mesh.quaternion.set(0, 0, 0, 1);
      }
      return;
    }

    // Date.now(), not performance.now(): the frames are stamped with the server's wall clock and
    // both processes are on this machine. performance.now() counts from page load, so sampling
    // with it asks where the balls were in 1970 and draws them stale forever.
    for (const body of bodies.sample(Date.now())) {
      const mesh = meshes.current.get(body.id);
      if (!mesh) continue;
      mesh.position.set(body.position[0], body.position[1], body.position[2]);
      mesh.quaternion.set(
        body.quaternion[0],
        body.quaternion[1],
        body.quaternion[2],
        body.quaternion[3],
      );
    }
  });

  return (
    <>
      {balls.map((ball) => (
        <mesh
          key={ball.key}
          ref={(mesh) => {
            if (mesh) meshes.current.set(ball.id, mesh);
            else meshes.current.delete(ball.id);
          }}
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

/**
 * Sides around a published cylinder. A FLOWER's tube is four inches across and read from a metre
 * away, where sixteen sides already look round and a tessellation nobody can see is a buffer
 * uploaded for nothing.
 */
const CYLINDER_SIDES = 16;

/** One solid of one structure, already paired with the buffer and material it shares. */
interface DrawnSolid {
  key: string;
  /**
   * Where to draw it. The position is in the structure's hinge frame when it has one, because the
   * mesh is then a child of that hinge's group; the orientation is the absolute one either way,
   * since the group's rotation composes onto it rather than replacing it.
   */
  pose: SolidPose;
  geometry: THREE.BufferGeometry;
  material: THREE.MeshStandardMaterial;
}

/** Stable empties, for a hinge whose lists are momentarily missing rather than merely short. */
const NO_SOLIDS: DrawnSolid[] = [];
const NO_LABELS: ClusterLabel[] = [];

/** The structure meshes, divided by whether the thing they are part of can swing. */
interface StructureMeshes {
  /** Every solid of every bolted-down structure, in one list: nothing joins to an individual one. */
  fixed: DrawnSolid[];
  /** Each swinging structure's solids, keyed by the name its hinge is reported under. */
  swinging: Map<string, DrawnSolid[]>;
}

/**
 * The field's furniture — the FLOWERs, the A-frame, the two tipping HIVEs — as the boxes and
 * cylinders the server published. Every number comes off {@code sim/scene}: the coordinates are CAD
 * measurements and Java owns them, so this file has no opinion about where a FLOWER is, only about
 * how to draw one where it was told (ADR-0004).
 *
 * <p>Cylinders are drawn <em>open</em> and from both sides, which is the one aesthetic decision
 * here and not really an aesthetic one: a FLOWER is a tube that holds POLLEN, and a capped tube
 * hides the balls inside it — the exact thing someone opens this view to look at. An open shell
 * needs {@code DoubleSide} or the far wall of the ring vanishes and the tube reads as a crescent.
 * Boxes are closed, so drawing the whole set double-sided costs them nothing and keeps one
 * material per colour.</p>
 *
 * <p>Buffers and materials are shared and disposed together, because a structure is the same few
 * primitives over and over: the four FLOWERs are two dozen tubes between them, in a handful of
 * sizes and one colour. One geometry and one material per solid would be two dozen uploads of the
 * same ring, and two dozen things to leak on the next scene republish — which arrives on every
 * INIT.</p>
 *
 * <p>One cache across the whole field, hinged and bolted alike, which is why this is a hook and not
 * a component: a HIVE's shelves are the same boxes in the same colour as its twin's, and a cache
 * per structure would upload each of them twice and dispose them from two places. The
 * {@code sim/scene} identity is the only dependency, so a tip — which never changes it — cannot
 * reach this memo at all.</p>
 */
function useStructureMeshes(split: PivotSplit): StructureMeshes {
  const { meshes, geometries, materials } = useMemo(() => {
    const byShape = new Map<string, THREE.BufferGeometry>();
    const byColour = new Map<number, THREE.MeshStandardMaterial>();

    const draw = (structure: SceneStructure, placement: PivotPlacement | null): DrawnSolid[] =>
      structure.solids.map((solid, index) => {
        const shape = solidGeometry(solid);
        const shapeKey = solidGeometryKey(shape);
        let geometry = byShape.get(shapeKey);
        if (!geometry) {
          geometry =
            shape.shape === 'box'
              ? new THREE.BoxGeometry(...shape.sizeMetres)
              : // Open-ended, with one height segment: the wire's cylinder is a shell, and
                // solidPose has already stood it up from three.js' Y axis onto the solid's +Z.
                new THREE.CylinderGeometry(
                  shape.radiusMetres,
                  shape.radiusMetres,
                  shape.lengthMetres,
                  CYLINDER_SIDES,
                  1,
                  true,
                );
          byShape.set(shapeKey, geometry);
        }

        const packed = (solid.red << 16) | (solid.green << 8) | solid.blue;
        let material = byColour.get(packed);
        if (!material) {
          material = new THREE.MeshStandardMaterial({
            color: new THREE.Color().setStyle(`rgb(${solid.red},${solid.green},${solid.blue})`),
            roughness: 0.6,
            side: THREE.DoubleSide,
          });
          byColour.set(packed, material);
        }

        const pose = solidPose(solid);
        // Named by structure and position in its own list: a structure's solids have no ids on the
        // wire, and none is wanted — nothing joins to an individual solid, and the whole scene
        // arrives at once whenever any of it changes.
        return {
          key: `${structure.name}:${index}`,
          pose: placement
            ? { position: pivotLocal(pose.position, placement), quaternion: pose.quaternion }
            : pose,
          geometry,
          material,
        };
      });

    // Bolted-down solids need no grouping of their own; a hinge's do, because each hinge draws its
    // own under one transform.
    const fixed: DrawnSolid[] = [];
    for (const structure of split.fixed) fixed.push(...draw(structure, null));

    const swinging = new Map<string, DrawnSolid[]>();
    for (const group of split.groups) {
      swinging.set(group.name, draw(group.structure, group.placement));
    }

    return { meshes: { fixed, swinging }, geometries: byShape, materials: byColour };
  }, [split]);

  useEffect(
    () => () => {
      for (const geometry of geometries.values()) geometry.dispose();
      for (const material of materials.values()) material.dispose();
    },
    [geometries, materials],
  );

  return meshes;
}

/** Solids at the poses {@link useStructureMeshes} worked out, in whichever frame they were built. */
function Solids({ solids }: { solids: DrawnSolid[] }) {
  return (
    <>
      {solids.map((solid) => (
        <mesh
          key={solid.key}
          geometry={solid.geometry}
          material={solid.material}
          position={solid.pose.position}
          quaternion={solid.pose.quaternion}
          castShadow
          receiveShadow
        />
      ))}
    </>
  );
}

/**
 * The tipping HIVEs: one {@code group} per hinge, carrying everything bolted to it — the solids,
 * the AprilTag quads whose {@code attachedTo} names it, and those tags' cluster labels.
 *
 * <p>One group per rigid body is the whole design. A HIVE is sixty-odd meshes and two tag clusters
 * that move together by definition, so the tip is one quaternion write per HIVE per frame instead
 * of sixty transforms that could disagree. Turning each mesh separately would also mean deriving
 * its own rotated pose, which is arithmetic repeated sixty times a frame to reach the answer the
 * scene graph already gives away.</p>
 *
 * <p>The angle arrives the way a ball's position does: sampled out of {@link BodyBuffer} inside
 * {@code useFrame} and written straight onto the object. Nothing here is React state — a HIVE
 * swinging at 50 Hz would otherwise re-render the console log and the device rail along with the
 * canvas, which is the performance bug that whole ref path exists to avoid.</p>
 *
 * <p>Written unconditionally, and for the reason {@link GameElements} gives: R3F skips a prop whose
 * numbers match the last ones it applied, so a HIVE left turned from a previous scene would stay
 * turned through a republish that puts it back upright.</p>
 */
function PivotedStructures({
  groups,
  solids,
  textures,
  bodies,
}: {
  groups: PivotGroup[];
  solids: Map<string, DrawnSolid[]>;
  textures: Map<number, THREE.CanvasTexture>;
  bodies: BodyBuffer;
}) {
  // Keyed by structure name, which is what sim/bodies reports an angle under: the two HIVEs are
  // interchangeable in every way except which one the server is talking about.
  const mounted = useRef(new Map<string, THREE.Group>());

  // One Vector3 per hinge, rebuilt only when the scene is. setFromAxisAngle wants a Vector3, and
  // building one per hinge per frame is garbage collected at display rate to say the same thing.
  const axes = useMemo(
    () =>
      new Map(groups.map((group) => [group.name, new THREE.Vector3(...group.placement.axis)])),
    [groups],
  );

  // Each hinge names its own clusters: the labels ride the tags, so they are centroids of this
  // HIVE's tags alone rather than of every tag on the field.
  const labels = useMemo(
    () => new Map(groups.map((group) => [group.name, clusterLabels(group.tags)])),
    [groups],
  );

  useFrame(() => {
    // Date.now() for the reason GameElements spells out: these are the server's timestamps.
    const angles = bodies.samplePivots(Date.now());
    for (const group of groups) {
      const object = mounted.current.get(group.name);
      const axis = axes.get(group.name);
      if (!object || !axis) continue;
      // The delta, never the live angle: the solids are already drawn at placement.drawnRadians.
      object.quaternion.setFromAxisAngle(
        axis,
        pivotTurnRadians(group.placement, angles.get(group.name)),
      );
    }
  });

  return (
    <>
      {groups.map((group) => (
        <group
          key={group.name}
          position={group.placement.position}
          ref={(object) => {
            if (object) mounted.current.set(group.name, object);
            else mounted.current.delete(group.name);
          }}
        >
          <Solids solids={solids.get(group.name) ?? NO_SOLIDS} />

          {group.tags.map((tag) => {
            const texture = textures.get(tag.id);
            return texture ? (
              <TagQuad key={tag.id} tag={tag} texture={texture} placement={group.placement} />
            ) : null;
          })}

          <ClusterLabels labels={labels.get(group.name) ?? NO_LABELS} placement={group.placement} />
        </group>
      ))}
    </>
  );
}

export function FieldContents({
  contents,
  bodies,
}: {
  contents: ScenePayload | null;
  bodies: BodyBuffer;
}) {
  const tags = contents ? contents.tags : NO_TAGS;
  const elements = contents ? contents.elements : NO_ELEMENTS;
  // A scene from a server that predates structures carries none; an empty field draws nothing
  // either way, so there is no version to negotiate here.
  const structures = contents?.structures ?? NO_STRUCTURES;
  const textures = useTagTextures(tags);

  // Which parts of the field can swing, and which tags ride which hinge. A scene from a server
  // that predates tipping HIVEs has no pivot on any structure, so every structure and every tag
  // comes back on the bolted-down side and the field is drawn exactly as it was before.
  const split = useMemo(() => splitByPivot(structures, tags), [structures, tags]);
  const meshes = useStructureMeshes(split);
  const labels = useMemo(() => clusterLabels(split.fixedTags), [split]);

  return (
    <group>
      {split.fixedTags.map((tag) => {
        const texture = textures.get(tag.id);
        return texture ? (
          <TagQuad key={tag.id} tag={tag} texture={texture} placement={null} />
        ) : null;
      })}

      <GameElements elements={elements} bodies={bodies} />

      <Solids solids={meshes.fixed} />

      <PivotedStructures
        groups={split.groups}
        solids={meshes.swinging}
        textures={textures}
        bodies={bodies}
      />

      <ClusterLabels labels={labels} placement={null} />
    </group>
  );
}
