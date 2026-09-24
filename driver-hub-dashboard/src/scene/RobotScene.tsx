import { OrbitControls } from '@react-three/drei';
import { Canvas, useFrame, useThree, type ThreeEvent } from '@react-three/fiber';
import { useCallback, useEffect, useRef, useState } from 'react';
import * as THREE from 'three';
import type {
  Alliance,
  CameraMountPayload,
  DeviceState,
  GamepadState,
  ScenePayload,
  SimConfig,
  SimPose,
} from '../protocol';
import { Field, STANDARD_FIELD } from './Field';
import type { BodyBuffer } from './bodies';
import { FieldContents } from './FieldContents';
import { fieldGround, sceneGround, sceneYaw } from './frame';
import { CHASSIS, type DeviceLayout } from './layout';
import {
  CameraFrustum,
  DeviceLabel,
  ImuModel,
  MotorModel,
  SelectionRing,
  ServoModel,
} from './parts';
import { placementFromDrag, type DragState } from './placement';

export interface ViewOptions {
  /** Turn the chassis with the IMU heading, so a yaw command reads as the robot turning. */
  followYaw: boolean;
  /** Snap dragged devices to a 1 cm grid. */
  snap: boolean;
  showLabels: boolean;
  /** Draw the left stick as a translation arrow and the right stick as a turn arc. */
  showStickVector: boolean;
  /** Sit the camera where the alliance's drive team stands, instead of wherever it was left. */
  allianceView: boolean;
  /** Draw the AprilTags and game elements the camera can see, as the server placed them. */
  showTags: boolean;
}

export const DEFAULT_VIEW_OPTIONS: ViewOptions = {
  followYaw: true,
  snap: true,
  showLabels: true,
  showStickVector: true,
  allianceView: true,
  showTags: true,
};

const SNAP_METRES = 0.01;
const GROUND = new THREE.Vector3(0, 1, 0);
/** The floor, for dragging the robot across it. Owned here, never mutated. */
const FLOOR = new THREE.Plane(new THREE.Vector3(0, 1, 0), 0);
/** Every camera in this scene looks at the middle of the field. */
const FIELD_CENTRE = new THREE.Vector3(0, 0, 0);

/** Metres, scene frame, and how the chassis mesh is proportioned. */
interface ChassisShape {
  width: number;
  depth: number;
  height: number;
  deckY: number;
}

/**
 * One device, placed and oriented by its layout.
 *
 * <p>Left-drag moves it across the deck; shift-drag raises and lowers it. The drag runs against a
 * plane through the device rather than against the mesh, so the pointer keeps its grip when it
 * leaves the geometry, and the result is converted back into the chassis frame — the chassis may be
 * yawed and driven out across the field under it.</p>
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
 *
 * <p>Wall contact repaints the whole body and cages it in a wire box. A robot pinned against the
 * perimeter still reports motor power and encoder counts, and mistaking that for motion is exactly
 * the reading error the sim exists to prevent.</p>
 */
function Chassis({ shape, contact }: { shape: ChassisShape; contact: boolean }) {
  const half = { x: shape.width / 2, z: shape.depth / 2 };
  return (
    <group>
      <mesh position={[0, shape.deckY, 0]} receiveShadow>
        <boxGeometry args={[shape.width, 0.012, shape.depth]} />
        <meshStandardMaterial
          color={contact ? '#6b3340' : '#33475c'}
          metalness={0.3}
          roughness={0.6}
          transparent
          opacity={0.45}
          depthWrite={false}
        />
      </mesh>
      {[-half.x, half.x].map((x) => (
        <mesh key={x} position={[x, shape.deckY - 0.03, 0]} castShadow>
          <boxGeometry args={[0.012, 0.05, shape.depth]} />
          <meshStandardMaterial
            color={contact ? '#8a3b46' : '#2d3c4c'}
            metalness={0.4}
            roughness={0.6}
          />
        </mesh>
      ))}
      {[-half.z, half.z].map((z) => (
        <mesh key={z} position={[0, shape.deckY - 0.03, z]} castShadow>
          <boxGeometry args={[shape.width, 0.05, 0.012]} />
          <meshStandardMaterial
            color={contact ? '#8a3b46' : '#2d3c4c'}
            metalness={0.4}
            roughness={0.6}
          />
        </mesh>
      ))}
      {/* Control hub, purely for orientation while the camera swings around. */}
      <mesh position={[0.08, shape.deckY + 0.018, 0.09]} castShadow>
        <boxGeometry args={[0.09, 0.024, 0.06]} />
        <meshStandardMaterial color="#1d2732" roughness={0.8} />
      </mesh>
      <mesh position={[0, shape.deckY + 0.008, -half.z - 0.02]} rotation={[-Math.PI / 2, 0, 0]}>
        <coneGeometry args={[0.026, 0.05, 3]} />
        <meshStandardMaterial color="#7CFC00" emissive="#7CFC00" emissiveIntensity={0.4} />
      </mesh>
      {contact && (
        <mesh position={[0, shape.deckY - 0.02, 0]}>
          <boxGeometry args={[shape.width + 0.04, shape.height + 0.1, shape.depth + 0.04]} />
          <meshBasicMaterial color="#ff7043" wireframe transparent opacity={0.9} />
        </mesh>
      )}
    </group>
  );
}

/**
 * The driver's stick input drawn in the robot's own frame: an arrow for the translation the left
 * stick is asking for, an arc for the turn the right stick is asking for.
 */
function StickVector({ gamepad, deckY }: { gamepad: GamepadState; deckY: number }) {
  const strafe = gamepad.left_stick_x;
  // Sticks report -1 when pushed away from the driver, and the robot's nose points at -Z.
  const forward = -gamepad.left_stick_y;
  const magnitude = Math.min(1, Math.hypot(strafe, forward));
  const turn = gamepad.right_stick_x;
  const length = magnitude * 0.4;
  const y = deckY + 0.06;

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

/**
 * Parks the camera where that alliance's drive team physically stands, looking across the field.
 *
 * <p>Red's station is at {@code -X_ftc} looking along {@code +X}, and the scene shares the field's
 * X axis, so the eye goes to scene {@code -X}; blue is the mirror. The move is eased rather than
 * snapped so the eye can follow which way the field turned, and the goal is dropped the moment it
 * is reached — after that OrbitControls owns the camera again and the user can look wherever they
 * like.</p>
 */
function AlliancePerspective({
  alliance,
  enabled,
  fieldSize,
  controls,
}: {
  alliance: Alliance;
  enabled: boolean;
  fieldSize: number;
  controls: React.RefObject<React.ComponentRef<typeof OrbitControls> | null>;
}) {
  const camera = useThree((state) => state.camera);
  const goal = useRef<THREE.Vector3 | null>(null);

  useEffect(() => {
    if (!enabled) {
      goal.current = null;
      return;
    }
    const side = alliance === 'red' ? -1 : 1;
    goal.current = new THREE.Vector3(side * fieldSize * 1.05, fieldSize * 0.55, 0);
  }, [alliance, enabled, fieldSize]);

  useFrame((_, delta) => {
    const target = goal.current;
    if (!target || !controls.current) return;
    // Frame-rate independent ease, so a 30 fps tab takes the same half-second as a 144 fps one.
    const step = 1 - Math.exp(-delta * 6);
    camera.position.lerp(target, step);
    controls.current.target.lerp(FIELD_CENTRE, step);
    if (camera.position.distanceTo(target) < 0.01) {
      camera.position.copy(target);
      controls.current.target.set(0, 0, 0);
      goal.current = null;
    }
    controls.current.update();
  });

  return null;
}

function Scene({
  devices,
  layout,
  selected,
  gamepad,
  options,
  pose,
  simConfig,
  simScene,
  bodies,
  cameraMount,
  alliance,
  subscribePose,
  onSelect,
  onMove,
  onPlaceRobot,
}: {
  devices: DeviceState[];
  layout: Record<string, DeviceLayout>;
  selected: string | null;
  gamepad: GamepadState;
  options: ViewOptions;
  pose: SimPose | null;
  simConfig: SimConfig | null;
  simScene: ScenePayload | null;
  bodies: BodyBuffer;
  /** The webcam's mount, which places and aims its frustum; null when the robot has no camera. */
  cameraMount: CameraMountPayload | null;
  alliance: Alliance;
  subscribePose: (listener: (pose: SimPose) => void) => () => void;
  onSelect: (name: string | null) => void;
  onMove: (name: string, position: [number, number, number]) => void;
  onPlaceRobot: (x: number, y: number, headingDegrees: number) => void;
}) {
  const robot = useRef<THREE.Group>(null);
  const controls = useRef<React.ComponentRef<typeof OrbitControls>>(null);

  /**
   * The chassis the robot config declares, falling back to the layout's stand-in until {@code
   * sim/config} lands — the devices are placed against that same stand-in, so the two agree.
   */
  const declared = simConfig?.robot.chassis;
  const shape: ChassisShape = declared
    ? {
        width: declared.widthMetres,
        depth: declared.lengthMetres,
        height: declared.heightMetres,
        deckY: declared.deckHeightMetres,
      }
    : { width: CHASSIS.width, depth: CHASSIS.depth, height: CHASSIS.height, deckY: CHASSIS.deckY };
  const field = simConfig?.field ?? null;
  const fieldSize = (field ?? STANDARD_FIELD).sizeMetres;

  /**
   * The pose the body is drawn at, kept out of React: it arrives at 50 Hz and the device rail has
   * no business re-rendering for it. The prop is the throttled copy, and seeding from it keeps the
   * body right when the socket goes quiet.
   */
  const live = useRef<SimPose | null>(pose);
  useEffect(() => {
    live.current = pose;
  }, [pose]);
  useEffect(() => subscribePose((next) => (live.current = next)), [subscribePose]);

  const imu = devices.find((device) => device.kind === 'imu');
  const imuYaw = options.followYaw && imu ? THREE.MathUtils.degToRad(imu.yawDegrees) : 0;
  /**
   * How far the body is turned in the field frame. Driven by the sim once it is running, by the
   * IMU when it is not, and the IMU compass needle reads against it either way.
   */
  const chassisYaw = pose ? THREE.MathUtils.degToRad(pose.headingDegrees) : imuYaw;

  useFrame(() => {
    const group = robot.current;
    if (!group) return;
    const current = live.current;
    if (current) {
      group.position.set(...sceneGround(current.x, current.y));
      group.rotation.y = sceneYaw(current.headingDegrees);
    } else {
      // Headless: no sim behind the dashboard, so the robot stays at the origin as it always did.
      group.position.set(0, 0, 0);
      group.rotation.y = imuYaw;
    }
  });

  const onDragChange = useCallback((dragging: boolean) => {
    if (controls.current) controls.current.enabled = !dragging;
    document.body.style.cursor = dragging ? 'grabbing' : 'auto';
  }, []);

  /**
   * Stable, because {@link Field} is memoised on its props and this scene re-renders on every
   * committed pose — twenty times a second. A handler rebuilt in the tree would defeat that memo
   * and reconcile the whole floor and perimeter along with it.
   */
  const onGroundDown = useCallback(
    (event: ThreeEvent<PointerEvent>) => {
      if (event.button === 0) onSelect(null);
    },
    [onSelect],
  );

  /**
   * Dragging the body places the robot: translate across the floor, shift-drag to swing the
   * heading. This is the "what if the robot starts two inches off" question, asked directly.
   *
   * <p>The result goes out as a pose and comes back from the sim like any other tick, so what the
   * screen shows is always what the sim believes — nothing is nudged locally.</p>
   */
  const chassisDrag = useRef<DragState | null>(null);
  const [placing, setPlacing] = useState(false);

  const endPlacing = useCallback(() => {
    if (!chassisDrag.current) return;
    chassisDrag.current = null;
    setPlacing(false);
    onDragChange(false);
  }, [onDragChange]);

  useEffect(() => {
    if (!placing) return;
    window.addEventListener('pointerup', endPlacing);
    window.addEventListener('pointercancel', endPlacing);
    return () => {
      window.removeEventListener('pointerup', endPlacing);
      window.removeEventListener('pointercancel', endPlacing);
    };
  }, [placing, endPlacing]);

  const onChassisDown = (event: ThreeEvent<PointerEvent>) => {
    // The chassis is not a device, so grabbing it clears whatever the inspector was editing.
    onSelect(null);
    const current = live.current;
    if (event.button !== 0 || !current) return;
    const hit = new THREE.Vector3();
    if (!event.ray.intersectPlane(FLOOR, hit)) return;
    event.stopPropagation();

    const grab = fieldGround(hit.x, hit.z);
    chassisDrag.current = {
      turning: event.shiftKey,
      offsetX: current.x - grab.x,
      offsetY: current.y - grab.y,
      grabBearing: null,
      startHeading: current.headingDegrees,
    };
    (event.target as Element).setPointerCapture(event.pointerId);
    setPlacing(true);
    onDragChange(true);
  };

  const onChassisMove = (event: ThreeEvent<PointerEvent>) => {
    const state = chassisDrag.current;
    const current = live.current;
    if (!state || !current) return;
    event.stopPropagation();

    const hit = new THREE.Vector3();
    if (!event.ray.intersectPlane(FLOOR, hit)) return;

    const { placement, grab } = placementFromDrag({
      drag: state,
      pointer: fieldGround(hit.x, hit.z),
      pose: current,
      chassis: shape,
      fieldSize,
      snap: options.snap,
    });
    state.grabBearing = grab.grabBearing;
    state.startHeading = grab.startHeading;
    if (placement) onPlaceRobot(placement.x, placement.y, placement.headingDegrees);
  };

  return (
    <>
      <color attach="background" args={['#080c11']} />
      <hemisphereLight args={['#9fc4e0', '#101820', 0.9]} />
      <ambientLight intensity={0.55} />
      <directionalLight
        castShadow
        position={[1.6, 3.2, 1.4]}
        intensity={1.6}
        shadow-mapSize={[2048, 2048]}
        shadow-camera-left={-fieldSize / 2}
        shadow-camera-right={fieldSize / 2}
        shadow-camera-top={fieldSize / 2}
        shadow-camera-bottom={-fieldSize / 2}
      />

      <Field field={field} alliance={alliance} onGroundDown={onGroundDown} />
      {options.showTags && <FieldContents contents={simScene} bodies={bodies} />}

      <group ref={robot}>
        <group
          onPointerDown={onChassisDown}
          onPointerMove={onChassisMove}
          onPointerUp={endPlacing}
          onPointerOver={() => {
            if (live.current) document.body.style.cursor = 'grab';
          }}
          onPointerOut={() => (document.body.style.cursor = 'auto')}
        >
          <Chassis shape={shape} contact={pose?.wallContact ?? false} />
        </group>
        {options.showStickVector && <StickVector gamepad={gamepad} deckY={shape.deckY} />}
        {/* The camera has no cosmetic placement to draw: its mount is the real thing, so it is
            drawn from the mount and left out of the device nodes entirely. */}
        {devices
          .filter((device) => device.name !== cameraMount?.name)
          .map((device) => (
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
        {cameraMount && devices.some((device) => device.name === cameraMount.name) && (
          <CameraFrustum
            mount={cameraMount}
            selected={selected === cameraMount.name}
            showLabel={options.showLabels}
            onSelect={() => onSelect(cameraMount.name)}
          />
        )}
      </group>

      <AlliancePerspective
        alliance={alliance}
        enabled={options.allianceView}
        fieldSize={fieldSize}
        controls={controls}
      />
      <OrbitControls
        ref={controls}
        makeDefault
        enablePan
        target={[0, 0, 0]}
        minDistance={0.35}
        maxDistance={12}
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
  pose: SimPose | null;
  simConfig: SimConfig | null;
  simScene: ScenePayload | null;
  bodies: BodyBuffer;
  cameraMount: CameraMountPayload | null;
  alliance: Alliance;
  subscribePose: (listener: (pose: SimPose) => void) => () => void;
  onSelect: (name: string | null) => void;
  onMove: (name: string, position: [number, number, number]) => void;
  onPlaceRobot: (x: number, y: number, headingDegrees: number) => void;
}) {
  return (
    // Opens on the red drive team's view of the whole field, which is where the toggle starts too.
    //
    // "percentage" rather than a bare `shadows`, which asks for PCFSoftShadowMap: three removed
    // that in 0.186 and answers it by warning and substituting PCFShadowMap. R3F re-applies this
    // prop on every render of the Canvas and this dashboard commits React state at 20 Hz, so the
    // substitution never settled: 350 identical warnings per 15 seconds, measured, which is enough
    // to bury anything else anyone is trying to read in the console. The drawing is unaffected
    // either way -- PCFShadowMap is what three was substituting, and both spellings end up at
    // `shadowMap.type === PCFShadowMap` with the same 165 draw calls.
    //
    // `dpr` is capped, and it is the single biggest lever in this file. R3F's default is the
    // display's own ratio, so a 5K panel asks for 14.7 Mpx of 4x-MSAA PBR every frame -- 59M
    // samples for a scene of 12k triangles. Measured on an M1 Max at this window size:
    // 2.19 ms of GPU per frame at dpr 2, 1.21 ms at 1.5, 0.57 ms at 1. A GPU a few years older is
    // several times slower per pixel, which is where "it taxes powerful machines" comes from.
    // 1.5 still supersamples a Retina panel and keeps the MSAA that the thin rails and the tag
    // quads need.
    <Canvas
      shadows="percentage"
      dpr={[1, 1.5]}
      camera={{ position: [0, 1.97, 3.76], fov: 45, near: 0.05, far: 40 }}
    >
      <Scene {...props} />
    </Canvas>
  );
}
