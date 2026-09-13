import { OrbitControls, RoundedBox } from '@react-three/drei';
import { Canvas, useFrame } from '@react-three/fiber';
import { useRef } from 'react';
import * as THREE from 'three';
import type { GamepadState } from '../protocol';

/**
 * gamepad1 as a physical object: sticks that lean, triggers that pull, buttons that light.
 *
 * <p>Its own canvas and its own camera, so the driver can look at the controller from an angle that
 * matches the one in their hands without disturbing the robot view.</p>
 */

const MAX_TILT = 0.5;
const BODY = '#232c36';
const IDLE_BUTTON = '#39434f';
const LIVE = '#7CFC00';

function Stick({ x, y, position }: { x: number; y: number; position: [number, number, number] }) {
  const group = useRef<THREE.Group>(null);
  const target = useRef({ x: 0, y: 0 });
  target.current = { x, y };

  useFrame((_, delta) => {
    if (!group.current) return;
    // Input is sampled at 20 Hz; ease so the stick reads as a thumb moving, not a teleport.
    const blend = Math.min(1, delta * 18);
    // Stick "up" is -1 and the cap should lean away from the viewer, hence the straight mapping.
    group.current.rotation.x += (target.current.y * MAX_TILT - group.current.rotation.x) * blend;
    group.current.rotation.z += (-target.current.x * MAX_TILT - group.current.rotation.z) * blend;
  });

  const deflection = Math.min(1, Math.hypot(x, y));
  const colour = new THREE.Color(IDLE_BUTTON).lerp(new THREE.Color(LIVE), deflection);

  return (
    <group position={position}>
      <mesh rotation={[-Math.PI / 2, 0, 0]}>
        <ringGeometry args={[0.026, 0.03, 24]} />
        <meshBasicMaterial color="#2f3a46" side={THREE.DoubleSide} />
      </mesh>
      <group ref={group}>
        <mesh position={[0, 0.014, 0]}>
          <cylinderGeometry args={[0.008, 0.011, 0.028, 14]} />
          <meshStandardMaterial color="#4a5560" metalness={0.4} roughness={0.5} />
        </mesh>
        <mesh position={[0, 0.03, 0]}>
          <cylinderGeometry args={[0.021, 0.017, 0.008, 20]} />
          <meshStandardMaterial
            color={colour}
            emissive={colour}
            emissiveIntensity={deflection * 0.6}
            roughness={0.6}
          />
        </mesh>
      </group>
    </group>
  );
}

function Button({
  position,
  pressed,
  radius = 0.011,
  colour = LIVE,
}: {
  position: [number, number, number];
  pressed: boolean;
  radius?: number;
  colour?: string;
}) {
  return (
    <mesh position={[position[0], position[1] - (pressed ? 0.004 : 0), position[2]]}>
      <cylinderGeometry args={[radius, radius, 0.01, 16]} />
      <meshStandardMaterial
        color={pressed ? colour : IDLE_BUTTON}
        emissive={pressed ? colour : '#000000'}
        emissiveIntensity={pressed ? 0.7 : 0}
        roughness={0.5}
      />
    </mesh>
  );
}

function DpadArm({
  position,
  size,
  pressed,
}: {
  position: [number, number, number];
  size: [number, number, number];
  pressed: boolean;
}) {
  return (
    <mesh position={[position[0], position[1] - (pressed ? 0.003 : 0), position[2]]}>
      <boxGeometry args={size} />
      <meshStandardMaterial
        color={pressed ? LIVE : IDLE_BUTTON}
        emissive={pressed ? LIVE : '#000000'}
        emissiveIntensity={pressed ? 0.6 : 0}
        roughness={0.6}
      />
    </mesh>
  );
}

function Trigger({ value, x }: { value: number; x: number }) {
  return (
    <group position={[x, 0.022, -0.072]} rotation={[-0.5 + value * 0.5, 0, 0]}>
      <mesh position={[0, 0, -0.012]}>
        <boxGeometry args={[0.038, 0.008, 0.03]} />
        <meshStandardMaterial
          color={value > 0.02 ? LIVE : '#2f3a46'}
          emissive={value > 0.02 ? LIVE : '#000000'}
          emissiveIntensity={value * 0.6}
          roughness={0.6}
        />
      </mesh>
    </group>
  );
}

function Controller({ gamepad }: { gamepad: GamepadState }) {
  return (
    <group>
      <RoundedBox args={[0.24, 0.045, 0.13]} radius={0.018} smoothness={4} castShadow>
        <meshStandardMaterial color={BODY} roughness={0.75} metalness={0.15} />
      </RoundedBox>
      {[-1, 1].map((side) => (
        <group key={side} position={[side * 0.088, -0.022, 0.045]} rotation={[0.45, side * 0.2, 0]}>
          <RoundedBox args={[0.052, 0.04, 0.09]} radius={0.018} smoothness={4}>
            <meshStandardMaterial color={BODY} roughness={0.8} />
          </RoundedBox>
        </group>
      ))}

      <Stick x={gamepad.left_stick_x} y={gamepad.left_stick_y} position={[-0.062, 0.022, 0.012]} />
      <Stick x={gamepad.right_stick_x} y={gamepad.right_stick_y} position={[0.062, 0.022, 0.012]} />

      <group position={[-0.045, 0.024, -0.032]}>
        <DpadArm position={[0, 0, -0.014]} size={[0.012, 0.008, 0.016]} pressed={gamepad.dpad_up} />
        <DpadArm position={[0, 0, 0.014]} size={[0.012, 0.008, 0.016]} pressed={gamepad.dpad_down} />
        <DpadArm position={[-0.014, 0, 0]} size={[0.016, 0.008, 0.012]} pressed={gamepad.dpad_left} />
        <DpadArm position={[0.014, 0, 0]} size={[0.016, 0.008, 0.012]} pressed={gamepad.dpad_right} />
      </group>

      <group position={[0.05, 0.024, -0.03]}>
        <Button position={[0, 0, 0.016]} pressed={gamepad.a} colour="#5ce65c" />
        <Button position={[0.016, 0, 0]} pressed={gamepad.b} colour="#ff6b6b" />
        <Button position={[-0.016, 0, 0]} pressed={gamepad.x} colour="#6bb6ff" />
        <Button position={[0, 0, -0.016]} pressed={gamepad.y} colour="#ffd76b" />
      </group>

      <Button position={[0, 0.024, -0.018]} pressed={gamepad.options} radius={0.007} colour="#c8c8c8" />

      <Trigger value={gamepad.left_trigger} x={-0.07} />
      <Trigger value={gamepad.right_trigger} x={0.07} />
    </group>
  );
}

export function GamepadView({ gamepad }: { gamepad: GamepadState }) {
  return (
    <Canvas camera={{ position: [0, 0.2, 0.26], fov: 40, near: 0.02, far: 5 }}>
      <hemisphereLight args={['#cfe4ff', '#0b1016', 0.7]} />
      <directionalLight position={[0.2, 0.6, 0.4]} intensity={1.3} />
      <Controller gamepad={gamepad} />
      <OrbitControls enablePan={false} minDistance={0.16} maxDistance={0.6} target={[0, 0, 0]} />
    </Canvas>
  );
}
