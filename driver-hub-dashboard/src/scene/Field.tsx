import { Html } from '@react-three/drei';
import type { ThreeEvent } from '@react-three/fiber';
import { useEffect, useMemo } from 'react';
import * as THREE from 'three';
import type { Alliance, SimField } from '../protocol';

/**
 * The field the robot drives on, in the scene frame: {@code sceneX = ftcX}, {@code sceneZ = -ftcY},
 * origin at field centre.
 *
 * <p>The tiles are the point of this component. A driver reads an auton in tile counts — "one tile
 * forward, strafe half a tile" — so the floor is laid out at the real 24" pitch off the config
 * rather than as a decorative grid: pacing a path against it has to give the same answer as pacing
 * it on the real field. The perimeter is short of a whole number of tiles (141" inside a 6×6 tile
 * floor), so the edge row is trimmed to the wall exactly as a real field's is.</p>
 */

/** The FTC perimeter the backend also falls back to, for the frames before {@code sim/config}. */
export const STANDARD_FIELD: SimField = {
  sizeMetres: 3.5814,
  wallHeightMetres: 0.312,
  tileMetres: 0.6096,
};

/** Field walls are clear polycarbonate; the colour is the tape and the panel behind it. */
const ALLIANCE_WALL: Record<Alliance, string> = { red: '#c0303f', blue: '#2b62c4' };
const NEUTRAL_WALL = '#48586a';
const WALL_THICKNESS = 0.024;

interface Tile {
  key: string;
  position: [number, number, number];
  scale: [number, number, number];
  light: boolean;
}

interface Wall {
  key: string;
  position: [number, number, number];
  size: [number, number, number];
  colour: string;
}

/**
 * Cuts the floor into tiles at the true pitch, centred on the field, clipped to the perimeter.
 *
 * <p>Clipping rather than scaling keeps the pitch honest: a partial tile at the wall reads as a
 * partial tile, and the seam a driver counts from stays where the real seam is.</p>
 */
function tiles(size: number, pitch: number): Tile[] {
  const half = size / 2;
  const count = Math.ceil(size / pitch);
  const start = -(count * pitch) / 2;
  const out: Tile[] = [];

  for (let column = 0; column < count; column += 1) {
    const x0 = Math.max(start + column * pitch, -half);
    const x1 = Math.min(start + (column + 1) * pitch, half);
    if (x1 - x0 <= 1e-6) continue;
    for (let row = 0; row < count; row += 1) {
      const z0 = Math.max(start + row * pitch, -half);
      const z1 = Math.min(start + (row + 1) * pitch, half);
      if (z1 - z0 <= 1e-6) continue;
      out.push({
        key: `${column}:${row}`,
        position: [(x0 + x1) / 2, 0.001, (z0 + z1) / 2],
        scale: [x1 - x0, z1 - z0, 1],
        light: (column + row) % 2 === 0,
      });
    }
  }
  return out;
}

export function Field({
  field,
  alliance,
  onGroundDown,
}: {
  field: SimField | null;
  alliance: Alliance;
  /** Clicking the floor is clicking nothing in particular, which is how a selection is cleared. */
  onGroundDown: (event: ThreeEvent<PointerEvent>) => void;
}) {
  const { sizeMetres, wallHeightMetres, tileMetres } = field ?? STANDARD_FIELD;
  const half = sizeMetres / 2;

  // One unit plane and two materials for every tile: the floor is 36 draw calls, not 36 uploads.
  const assets = useMemo(() => {
    const geometry = new THREE.PlaneGeometry(1, 1);
    const light = new THREE.MeshStandardMaterial({ color: '#22303c', roughness: 0.95 });
    const dark = new THREE.MeshStandardMaterial({ color: '#18242e', roughness: 0.95 });
    return { geometry, light, dark };
  }, []);
  useEffect(
    () => () => {
      assets.geometry.dispose();
      assets.light.dispose();
      assets.dark.dispose();
    },
    [assets],
  );

  const grid = useMemo(() => tiles(sizeMetres, tileMetres), [sizeMetres, tileMetres]);

  // The stations are on the X axis, red at -X_ftc, audience at -Y_ftc: the field CAD, through
  // BioBuzzField's conversion of it, which is the same authority the tags are placed from.
  const walls: Wall[] = [
    {
      key: 'red',
      position: [-half - WALL_THICKNESS / 2, wallHeightMetres / 2, 0],
      size: [WALL_THICKNESS, wallHeightMetres, sizeMetres + 2 * WALL_THICKNESS],
      colour: ALLIANCE_WALL.red,
    },
    {
      key: 'blue',
      position: [half + WALL_THICKNESS / 2, wallHeightMetres / 2, 0],
      size: [WALL_THICKNESS, wallHeightMetres, sizeMetres + 2 * WALL_THICKNESS],
      colour: ALLIANCE_WALL.blue,
    },
    {
      // sceneZ = -ftcY, so the audience side (-Y_ftc) is scene +Z.
      key: 'audience',
      position: [0, wallHeightMetres / 2, half + WALL_THICKNESS / 2],
      size: [sizeMetres, wallHeightMetres, WALL_THICKNESS],
      colour: NEUTRAL_WALL,
    },
    {
      key: 'audience-far',
      position: [0, wallHeightMetres / 2, -half - WALL_THICKNESS / 2],
      size: [sizeMetres, wallHeightMetres, WALL_THICKNESS],
      colour: NEUTRAL_WALL,
    },
  ];

  return (
    <group>
      {/* The base under the tiles carries the pointer handler, so any click on the floor lands. */}
      <mesh rotation={[-Math.PI / 2, 0, 0]} receiveShadow onPointerDown={onGroundDown}>
        <planeGeometry args={[sizeMetres, sizeMetres]} />
        <meshStandardMaterial color="#0c1219" roughness={1} />
      </mesh>
      {grid.map((tile) => (
        <mesh
          key={tile.key}
          geometry={assets.geometry}
          material={tile.light ? assets.light : assets.dark}
          position={tile.position}
          rotation={[-Math.PI / 2, 0, 0]}
          scale={tile.scale}
          receiveShadow
        />
      ))}

      {walls.map((wall) => (
        <group key={wall.key} position={wall.position}>
          <mesh>
            <boxGeometry args={wall.size} />
            {/* Translucent: the real walls are clear, and an opaque near wall would hide the robot. */}
            <meshStandardMaterial
              color={wall.colour}
              roughness={0.35}
              metalness={0.1}
              transparent
              opacity={0.32}
              depthWrite={false}
            />
          </mesh>
          {/* The extrusion along the top. Opaque, so the perimeter reads as a line from any angle. */}
          <mesh position={[0, wallHeightMetres / 2, 0]}>
            <boxGeometry args={[wall.size[0] + 0.01, 0.014, wall.size[2] + 0.01]} />
            <meshStandardMaterial color={wall.colour} roughness={0.5} metalness={0.2} />
          </mesh>
        </group>
      ))}

      {/* Which end is which, for the camera angles where the walls alone are ambiguous. */}
      {(['red', 'blue'] as Alliance[]).map((station) => (
        <Html
          key={station}
          position={[(station === 'red' ? -1 : 1) * (half + 0.06), wallHeightMetres + 0.07, 0]}
          center
          distanceFactor={3}
          pointerEvents="none"
          zIndexRange={[5, 0]}
        >
          <div
            style={{
              fontFamily: 'ui-monospace, monospace',
              fontSize: 12,
              letterSpacing: 2,
              color: ALLIANCE_WALL[station],
              opacity: alliance === station ? 1 : 0.45,
              whiteSpace: 'nowrap',
            }}
          >
            {station.toUpperCase()} STATION
          </div>
        </Html>
      ))}
    </group>
  );
}
