import { OrbitControls } from '@react-three/drei';
import { Canvas, useThree, type ThreeEvent } from '@react-three/fiber';
import { useCallback, useEffect, useRef, useState } from 'react';
import * as THREE from 'three';
import type { DeviceState, GamepadState } from '../protocol';
import { CHASSIS, type DeviceLayout } from './layout';
import { DeviceLabel, ImuModel, MotorModel, SelectionRing, ServoModel } from './parts';

export interface ViewOptions {
  /** Turn the chassis with the IMU heading, so a yaw command reads as the robot turning. */
  followYaw: boolean;
  /** Snap dragged devices to a 1 cm grid. */
  snap: boolean;
  showLabels: boolean;
  /** Draw the left stick as a translation arrow and the right stick as a turn arc. */
  showStickVector: boolean;
}

export const DEFAULT_VIEW_OPTIONS: ViewOptions = {
  followYaw: true,
  snap: true,
  showLabels: true,
  showStickVector: true,
};

const SNAP_METRES = 0.01;
const GROUND = new THREE.Vector3(0, 1, 0);

/**
 * One device, placed and oriented by its layout.
 *
 * <p>Left-drag moves it across the deck; shift-drag raises and lowers it. The drag runs against a
 * plane through the device rather than against the mesh, so the pointer keeps its grip when it
 * leaves the geometry, and the result is converted back into the chassis frame — the chassis may be
 * yawed under it.</p>
 */
function DeviceNode({
  device,
  layout,
  selected,
  chassisYaw,
  robot,
  options,
  onSelect,
  onMove,
  onDragChange,
}: {
  device: DeviceState;
  layout: DeviceLayout;
  selected: boolean;
  chassisYaw: number;
  robot: React.RefObject<THREE.Group | null>;
  options: ViewOptions;
  onSelect: (name: string) => void;
  onMove: (name: string, position: [number, number, number]) => void;
  onDragChange: (dragging: boolean) => void;
}) {
  const group = useRef<THREE.Group>(null);
  const camera = useThree((state) => state.camera);
  const drag = useRef<{ plane: THREE.Plane; offset: THREE.Vector3; vertical: boolean } | null>(null);
  const [dragging, setDragging] = useState(false);

  const endDrag = useCallback(() => {
    if (!drag.current) return;
    drag.current = null;
    setDragging(false);
    onDragChange(false);
  }, [onDragChange]);

  useEffect(() => {
    if (!dragging) return;
    // Pointer capture can be lost (window blur, context menu); never strand the scene mid-drag.
    window.addEventListener('pointerup', endDrag);
    window.addEventListener('pointercancel', endDrag);
    return () => {
      window.removeEventListener('pointerup', endDrag);
      window.removeEventListener('pointercancel', endDrag);
    };
  }, [dragging, endDrag]);

  const onPointerDown = (event: ThreeEvent<PointerEvent>) => {
    event.stopPropagation();
    onSelect(device.name);
    if (event.button !== 0 || !group.current) return;

    const origin = group.current.getWorldPosition(new THREE.Vector3());
    const plane = new THREE.Plane();
    const vertical = event.shiftKey;
    if (vertical) {
      const normal = camera.getWorldDirection(new THREE.Vector3());
      normal.y = 0;
      plane.setFromNormalAndCoplanarPoint(normal.normalize(), origin);
    } else {
      plane.setFromNormalAndCoplanarPoint(GROUND, origin);
    }

    const hit = new THREE.Vector3();
    if (!event.ray.intersectPlane(plane, hit)) return;

    drag.current = { plane, offset: origin.clone().sub(hit), vertical };
    (event.target as Element).setPointerCapture(event.pointerId);
    setDragging(true);
    onDragChange(true);
  };

  const onPointerMove = (event: ThreeEvent<PointerEvent>) => {
    const state = drag.current;
    if (!state || !robot.current) return;
    event.stopPropagation();

    const hit = new THREE.Vector3();
    if (!event.ray.intersectPlane(state.plane, hit)) return;
    const local = robot.current.worldToLocal(hit.add(state.offset));

    const snap = (value: number) =>
      options.snap ? Math.round(value / SNAP_METRES) * SNAP_METRES : Number(value.toFixed(4));

    onMove(
      device.name,
      state.vertical
        ? [layout.position[0], Math.max(0, snap(local.y)), layout.position[2]]
        : [snap(local.x), layout.position[1], snap(local.z)],
    );
  };

  const [yaw, pitch, roll] = layout.rotation;

  return (
    <group position={layout.position}>
      {selected && <SelectionRing />}
      <group
        ref={group}
        rotation={[
          THREE.MathUtils.degToRad(pitch),
          THREE.MathUtils.degToRad(yaw),
          THREE.MathUtils.degToRad(roll),
        ]}
        rotation-order="YXZ"
        scale={layout.scale}
        onPointerDown={onPointerDown}
        onPointerMove={onPointerMove}
        onPointerUp={endDrag}
        onPointerOver={() => (document.body.style.cursor = 'grab')}
        onPointerOut={() => (document.body.style.cursor = 'auto')}
      >
        {device.kind === 'motor' && <MotorModel device={device} layout={layout} />}
        {device.kind === 'servo' && <ServoModel device={device} layout={layout} />}
        {device.kind === 'imu' && <ImuModel device={device} chassisYaw={chassisYaw} />}
        {device.kind === 'unknown' && (
          <mesh>
            <boxGeometry args={[0.04, 0.04, 0.04]} />
            <meshStandardMaterial color="#3b4652" />
          </mesh>
        )}
      </group>
      {options.showLabels && (
        <DeviceLabel
          device={device}
          layout={layout}
          selected={selected}
          height={0.09 * layout.scale}
        />
      )}
    </group>
  );
}

/**
 * The bare chassis the devices are mounted to: a deck, side rails, and a nose marker for forward.
 *
 * <p>The deck is translucent on purpose. A motor bolted under the plate is the normal case, and the
 * point of this view is to see it turn.</p>
 */
function Chassis() {
  const half = { x: CHASSIS.width / 2, z: CHASSIS.depth / 2 };
  return (
    <group>
      <mesh position={[0, CHASSIS.deckY, 0]} receiveShadow>
        <boxGeometry args={[CHASSIS.width, 0.012, CHASSIS.depth]} />
        <meshStandardMaterial
          color="#33475c"
          metalness={0.3}
          roughness={0.6}
          transparent
          opacity={0.45}
          depthWrite={false}
        />
      </mesh>
      {[-half.x, half.x].map((x) => (
        <mesh key={x} position={[x, CHASSIS.deckY - 0.03, 0]} castShadow>
          <boxGeometry args={[0.012, 0.05, CHASSIS.depth]} />
          <meshStandardMaterial color="#2d3c4c" metalness={0.4} roughness={0.6} />
        </mesh>
      ))}
      {[-half.z, half.z].map((z) => (
        <mesh key={z} position={[0, CHASSIS.deckY - 0.03, z]} castShadow>
          <boxGeometry args={[CHASSIS.width, 0.05, 0.012]} />
          <meshStandardMaterial color="#2d3c4c" metalness={0.4} roughness={0.6} />
        </mesh>
      ))}
      {/* Control hub, purely for orientation while the camera swings around. */}
      <mesh position={[0.08, CHASSIS.deckY + 0.018, 0.09]} castShadow>
        <boxGeometry args={[0.09, 0.024, 0.06]} />
        <meshStandardMaterial color="#1d2732" roughness={0.8} />
      </mesh>
      <mesh position={[0, CHASSIS.deckY + 0.008, -half.z - 0.02]} rotation={[-Math.PI / 2, 0, 0]}>
        <coneGeometry args={[0.026, 0.05, 3]} />
        <meshStandardMaterial color="#7CFC00" emissive="#7CFC00" emissiveIntensity={0.4} />
      </mesh>
    </group>
  );
}

/**
 * The driver's stick input drawn in the robot's own frame: an arrow for the translation the left
 * stick is asking for, an arc for the turn the right stick is asking for.
 */
function StickVector({ gamepad }: { gamepad: GamepadState }) {
  const strafe = gamepad.left_stick_x;
  // Sticks report -1 when pushed away from the driver, and the robot's nose points at -Z.
  const forward = -gamepad.left_stick_y;
  const magnitude = Math.min(1, Math.hypot(strafe, forward));
  const turn = gamepad.right_stick_x;
  const length = magnitude * 0.4;
  const y = CHASSIS.deckY + 0.06;

  return (
    <group>
      {magnitude > 0.05 && (
        <group position={[0, y, 0]} rotation={[0, Math.atan2(-strafe, forward), 0]}>
          <mesh position={[0, 0, -length / 2]} rotation={[Math.PI / 2, 0, 0]}>
            <cylinderGeometry args={[0.006, 0.006, length, 10]} />
            <meshBasicMaterial color="#8bd6ff" />
          </mesh>
          <mesh position={[0, 0, -length - 0.02]} rotation={[-Math.PI / 2, 0, 0]}>
            <coneGeometry args={[0.018, 0.04, 14]} />
            <meshBasicMaterial color="#8bd6ff" />
          </mesh>
        </group>
      )}
      {Math.abs(turn) > 0.05 && (
        <mesh
          position={[0, y - 0.03, 0]}
          rotation={[-Math.PI / 2, 0, turn > 0 ? Math.PI / 2 : Math.PI / 2 - Math.abs(turn) * 2.2]}
        >
          <ringGeometry args={[0.16, 0.176, 40, 1, 0, Math.abs(turn) * 2.2]} />
          <meshBasicMaterial color="#ffb86b" side={THREE.DoubleSide} transparent opacity={0.85} />
        </mesh>
      )}
    </group>
  );
}

function SceneContents({
  devices,
  layout,
  selected,
  gamepad,
  options,
  onSelect,
  onMove,
}: {
  devices: DeviceState[];
  layout: Record<string, DeviceLayout>;
  selected: string | null;
  gamepad: GamepadState;
  options: ViewOptions;
  onSelect: (name: string | null) => void;
  onMove: (name: string, position: [number, number, number]) => void;
}) {
  const robot = useRef<THREE.Group>(null);
  const controls = useRef<React.ComponentRef<typeof OrbitControls>>(null);

  const imu = devices.find((device) => device.kind === 'imu');
  const chassisYaw =
    options.followYaw && imu ? THREE.MathUtils.degToRad(imu.yawDegrees) : 0;

  const onDragChange = useCallback((dragging: boolean) => {
    if (controls.current) controls.current.enabled = !dragging;
    document.body.style.cursor = dragging ? 'grabbing' : 'auto';
  }, []);

  return (
    <>
      <color attach="background" args={['#080c11']} />
      <hemisphereLight args={['#9fc4e0', '#101820', 0.9]} />
      <ambientLight intensity={0.55} />
      <directionalLight
        castShadow
        position={[0.9, 1.4, 0.8]}
        intensity={1.6}
        shadow-mapSize={[1024, 1024]}
        shadow-camera-left={-1}
        shadow-camera-right={1}
        shadow-camera-top={1}
        shadow-camera-bottom={-1}
      />

      {/* Empty space clears the selection, the same way clicking off a node does elsewhere. */}
      <mesh
        rotation={[-Math.PI / 2, 0, 0]}
        receiveShadow
        onPointerDown={(event) => {
          if (event.button === 0) onSelect(null);
        }}
      >
        <planeGeometry args={[4, 4]} />
        <meshStandardMaterial color="#0c1219" roughness={1} />
      </mesh>
      <gridHelper args={[2.4, 24, '#1d3040', '#141d26']} position={[0, 0.001, 0]} />

      <group ref={robot} rotation={[0, chassisYaw, 0]}>
        <Chassis />
        {options.showStickVector && <StickVector gamepad={gamepad} />}
        {devices.map((device) => (
          <DeviceNode
            key={device.name}
            device={device}
            layout={layout[device.name]}
            selected={selected === device.name}
            chassisYaw={chassisYaw}
            robot={robot}
            options={options}
            onSelect={onSelect}
            onMove={onMove}
            onDragChange={onDragChange}
          />
        ))}
      </group>

      <OrbitControls
        ref={controls}
        makeDefault
        enablePan
        target={[0, 0.1, 0]}
        minDistance={0.35}
        maxDistance={3}
        maxPolarAngle={Math.PI / 2 - 0.02}
      />
    </>
  );
}

export function RobotScene(props: {
  devices: DeviceState[];
  layout: Record<string, DeviceLayout>;
  selected: string | null;
  gamepad: GamepadState;
  options: ViewOptions;
  onSelect: (name: string | null) => void;
  onMove: (name: string, position: [number, number, number]) => void;
}) {
  return (
    <Canvas shadows camera={{ position: [0.55, 0.6, 0.85], fov: 42, near: 0.05, far: 20 }}>
      <SceneContents {...props} />
    </Canvas>
  );
}
