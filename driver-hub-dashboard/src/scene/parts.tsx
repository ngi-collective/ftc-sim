import { useFrame } from '@react-three/fiber';
import { Html } from '@react-three/drei';
import { useRef } from 'react';
import * as THREE from 'three';
import type { DeviceState } from '../protocol';
import type { DeviceLayout, Mount } from './layout';

/**
 * The meshes for one device, drawn in the device's own frame: the shaft is local +Z, the origin is
 * the face the shaft comes out of. Placement and orientation belong to the caller, so a motor looks
 * the same whichever way the user has aimed it.
 */

const IDLE = new THREE.Color('#2b3a4a');
const FORWARD = new THREE.Color('#48d97a');
const REVERSE = new THREE.Color('#ff6b6b');
const STALLED = new THREE.Color('#f5a623');
const METAL = '#8d99a6';
const CASING = '#4a5560';

/** Idle grey through to saturated green (forward) or red (reverse) at full power. */
export function powerColour(power: number): THREE.Color {
  const magnitude = Math.min(1, Math.abs(power));
  return IDLE.clone().lerp(power >= 0 ? FORWARD : REVERSE, magnitude);
}


/**
 * Turns the encoder into a shaft angle.
 *
 * <p>Device snapshots arrive at 10&nbsp;Hz, far below frame rate, so between them the shaft is spun
 * from the reported velocity. The encoder is the truth about <em>where</em> the shaft is, but a
 * sample can be 100&nbsp;ms old &mdash; half a turn at drivetrain speed &mdash; so it is only worth
 * chasing once the shaft is nearly still. That is also when phase actually matters: a parked arm or
 * a RUN_TO_POSITION move should settle on the angle the encoder reports, not near it.</p>
 */
function useShaftSpin(device: DeviceState, layout: DeviceLayout) {
  const group = useRef<THREE.Group>(null);
  const angle = useRef(0);
  const sample = useRef({ velocity: 0, position: 0, ticksPerRev: 1, sign: 1 });

  sample.current = {
    velocity: device.velocityTicksPerSecond,
    position: device.position,
    ticksPerRev: Math.max(1, layout.ticksPerRev),
    sign: layout.invert ? -1 : 1,
  };

  useFrame((_, delta) => {
    const { velocity, position, ticksPerRev, sign } = sample.current;
    const radiansPerTick = (2 * Math.PI) / ticksPerRev;
    const step = Math.min(delta, 0.1);

    angle.current += sign * velocity * radiansPerTick * step;

    // A twentieth of a revolution per second: slow enough that a stale sample is still accurate.
    if (Math.abs(velocity) < ticksPerRev / 20) {
      const measured = sign * position * radiansPerTick;
      let error = (measured - angle.current) % (2 * Math.PI);
      if (error > Math.PI) error -= 2 * Math.PI;
      if (error < -Math.PI) error += 2 * Math.PI;
      // Snap out the last sliver rather than easing toward it forever.
      angle.current += Math.abs(error) < 0.002 ? error : error * Math.min(1, step * 12);
    }

    if (group.current) group.current.rotation.z = angle.current;
  });

  return group;
}

/** Arc around the motor face showing how much power is commanded, and which way. */
function PowerArc({ power, stalled }: { power: number; stalled: boolean }) {
  const sweep = Math.min(1, Math.abs(power)) * Math.PI * 1.9;
  if (sweep < 0.01) return null;
  const start = power >= 0 ? Math.PI / 2 : Math.PI / 2 - sweep;
  return (
    <mesh position={[0, 0, 0.004]}>
      <ringGeometry args={[0.036, 0.045, 48, 1, start, sweep]} />
      <meshBasicMaterial
        color={stalled ? STALLED : powerColour(power)}
        side={THREE.DoubleSide}
        transparent
        opacity={0.92}
      />
    </mesh>
  );
}

function Spokes({ radius, count, colour }: { radius: number; count: number; colour: string }) {
  return (
    <>
      {Array.from({ length: count }, (_, index) => (
        <mesh key={index} rotation={[0, 0, (index * Math.PI) / count]}>
          <boxGeometry args={[radius * 2, 0.006, 0.004]} />
          <meshStandardMaterial color={colour} metalness={0.3} roughness={0.6} />
        </mesh>
      ))}
    </>
  );
}

/** Whatever the shaft is driving. Every option carries a mark that makes rotation obvious. */
function MountMesh({ mount, tint }: { mount: Mount; tint: THREE.Color }) {
  switch (mount) {
    case 'wheel':
      // A torus tyre with open spokes: the hole and the tread blocks make the angle readable from
      // any camera position, which a solid disc does not.
      return (
        <group>
          <mesh>
            <torusGeometry args={[0.043, 0.011, 10, 28]} />
            <meshStandardMaterial color="#161a1f" roughness={0.95} />
          </mesh>
          {Array.from({ length: 8 }, (_, index) => {
            const theta = (index / 8) * Math.PI * 2;
            return (
              <mesh
                key={index}
                position={[Math.cos(theta) * 0.043, Math.sin(theta) * 0.043, 0]}
                rotation={[0, 0, theta]}
              >
                <boxGeometry args={[0.012, 0.014, 0.03]} />
                <meshStandardMaterial
                  color={index === 0 ? tint : '#333c46'}
                  emissive={index === 0 ? tint : '#000000'}
                  emissiveIntensity={index === 0 ? 0.5 : 0}
                  roughness={0.8}
                />
              </mesh>
            );
          })}
          <mesh rotation={[Math.PI / 2, 0, 0]}>
            <cylinderGeometry args={[0.013, 0.013, 0.024, 16]} />
            <meshStandardMaterial color={METAL} metalness={0.6} roughness={0.4} />
          </mesh>
          <Spokes radius={0.038} count={3} colour="#b9c6d2" />
        </group>
      );
    case 'omni':
      return (
        <group>
          <mesh rotation={[Math.PI / 2, 0, 0]}>
            <cylinderGeometry args={[0.032, 0.032, 0.024, 20]} />
            <meshStandardMaterial color="#2c333b" roughness={0.7} />
          </mesh>
          {Array.from({ length: 8 }, (_, index) => {
            const theta = (index / 8) * Math.PI * 2;
            return (
              <mesh
                key={index}
                position={[Math.cos(theta) * 0.042, Math.sin(theta) * 0.042, 0]}
                rotation={[Math.PI / 2, 0, -theta]}
              >
                <cylinderGeometry args={[0.011, 0.011, 0.026, 10]} />
                <meshStandardMaterial
                  color={index % 2 === 0 ? tint : new THREE.Color('#8d99a6')}
                  roughness={0.5}
                />
              </mesh>
            );
          })}
        </group>
      );
    case 'spool':
      return (
        <group rotation={[Math.PI / 2, 0, 0]}>
          <mesh>
            <cylinderGeometry args={[0.018, 0.018, 0.036, 18]} />
            <meshStandardMaterial color={METAL} metalness={0.6} roughness={0.4} />
          </mesh>
          {[-0.018, 0.018].map((offset) => (
            <mesh key={offset} position={[0, offset, 0]}>
              <cylinderGeometry args={[0.028, 0.028, 0.004, 18]} />
              <meshStandardMaterial color={tint} emissive={tint} emissiveIntensity={0.3} />
            </mesh>
          ))}
        </group>
      );
    case 'arm':
      return (
        <group>
          <mesh rotation={[Math.PI / 2, 0, 0]}>
            <cylinderGeometry args={[0.012, 0.012, 0.01, 16]} />
            <meshStandardMaterial color={METAL} metalness={0.6} roughness={0.4} />
          </mesh>
          <mesh position={[0.03, 0, 0]}>
            <boxGeometry args={[0.06, 0.01, 0.008]} />
            <meshStandardMaterial color={tint} emissive={tint} emissiveIntensity={0.35} />
          </mesh>
          <mesh position={[0.058, 0, 0]}>
            <sphereGeometry args={[0.008, 12, 12]} />
            <meshStandardMaterial color="#dbe4ec" />
          </mesh>
        </group>
      );
    case 'bare':
      return (
        <group>
          <mesh rotation={[Math.PI / 2, 0, 0]}>
            <cylinderGeometry args={[0.016, 0.016, 0.012, 18]} />
            <meshStandardMaterial color={METAL} metalness={0.7} roughness={0.35} />
          </mesh>
          <mesh position={[0.008, 0, 0.007]}>
            <boxGeometry args={[0.016, 0.005, 0.003]} />
            <meshStandardMaterial color={tint} emissive={tint} emissiveIntensity={0.5} />
          </mesh>
        </group>
      );
  }
}

export function MotorModel({ device, layout }: { device: DeviceState; layout: DeviceLayout }) {
  const spin = useShaftSpin(device, layout);
  // Commanded power with nothing to show for it: the classic "why isn't it moving" picture.
  const stalled =
    Math.abs(device.physicalPower) > 0.05 && Math.abs(device.velocityTicksPerSecond) < 1;
  const tint = stalled ? STALLED : powerColour(device.physicalPower);

  return (
    <group>
      {/* Can and gearbox: the fixed half of the motor. */}
      <mesh position={[0, 0, -0.062]} rotation={[Math.PI / 2, 0, 0]}>
        <cylinderGeometry args={[0.024, 0.024, 0.085, 20]} />
        <meshStandardMaterial color={CASING} metalness={0.55} roughness={0.45} />
      </mesh>
      <mesh position={[0, 0, -0.012]} rotation={[Math.PI / 2, 0, 0]}>
        <cylinderGeometry args={[0.031, 0.031, 0.026, 22]} />
        <meshStandardMaterial color="#39424c" metalness={0.4} roughness={0.6} />
      </mesh>
      {/* Power band on the casing: readable from behind, where the arc is hidden. */}
      <mesh position={[0, 0, -0.095]} rotation={[Math.PI / 2, 0, 0]}>
        <cylinderGeometry args={[0.0245, 0.0245, 0.014, 20]} />
        <meshStandardMaterial color={tint} emissive={tint} emissiveIntensity={0.45} />
      </mesh>
      <mesh position={[0, 0, 0.012]} rotation={[Math.PI / 2, 0, 0]}>
        <cylinderGeometry args={[0.006, 0.006, 0.05, 12]} />
        <meshStandardMaterial color={METAL} metalness={0.8} roughness={0.25} />
      </mesh>

      <PowerArc power={device.commandedPower} stalled={stalled} />

      <group ref={spin} position={[0, 0, 0.034]}>
        <MountMesh mount={layout.mount} tint={tint} />
      </group>
    </group>
  );
}

export function ServoModel({ device, layout }: { device: DeviceState; layout: DeviceLayout }) {
  const horn = useRef<THREE.Group>(null);
  const target = useRef(0);
  // A standard servo sweeps 300° over its 0..1 range; centre it so 0.5 points along +X.
  target.current = (device.hornPosition - 0.5) * (300 * (Math.PI / 180)) * (layout.invert ? -1 : 1);

  useFrame((_, delta) => {
    if (!horn.current) return;
    // Snapshots land at 10 Hz; ease so a jammed-vs-sweeping comparison reads as motion, not steps.
    horn.current.rotation.z += (target.current - horn.current.rotation.z) * Math.min(1, delta * 12);
  });

  const drift = Math.abs(device.commandedPosition - device.hornPosition);
  const tint = drift > 0.02 ? new THREE.Color('#f5a623') : new THREE.Color('#48d97a');

  return (
    <group>
      <mesh position={[0, 0, -0.022]}>
        <boxGeometry args={[0.04, 0.038, 0.04]} />
        <meshStandardMaterial color={CASING} roughness={0.6} />
      </mesh>
      <mesh position={[0, 0, -0.002]} rotation={[Math.PI / 2, 0, 0]}>
        <cylinderGeometry args={[0.014, 0.014, 0.008, 16]} />
        <meshStandardMaterial color="#39424c" />
      </mesh>
      {/* Commanded position as a ghost mark, so lag against the horn is visible. */}
      <mesh
        position={[0, 0, 0.004]}
        rotation={[0, 0, (device.commandedPosition - 0.5) * (300 * (Math.PI / 180))]}
      >
        <ringGeometry args={[0.026, 0.032, 24, 1, -0.12, 0.24]} />
        <meshBasicMaterial color="#8bd6ff" side={THREE.DoubleSide} transparent opacity={0.8} />
      </mesh>
      <group ref={horn} position={[0, 0, 0.008]}>
        <MountMesh mount={layout.mount === 'wheel' ? 'arm' : layout.mount} tint={tint} />
      </group>
    </group>
  );
}

/**
 * The IMU as a compass: the board sits flat, a needle points where the robot's heading actually is
 * in the field frame. {@code chassisYaw} is however much the chassis has already been turned, so
 * the needle reads the same whether or not the body is following the heading.
 */
export function ImuModel({ device, chassisYaw }: { device: DeviceState; chassisYaw: number }) {
  const yaw = device.yawDegrees * (Math.PI / 180);
  const spinning = Math.abs(device.yawRateDegreesPerSecond) > 1;

  return (
    <group>
      <mesh>
        <boxGeometry args={[0.052, 0.008, 0.038]} />
        <meshStandardMaterial color="#1f4d3d" roughness={0.7} />
      </mesh>
      <group rotation={[0, -chassisYaw, 0]}>
        <mesh rotation={[-Math.PI / 2, 0, 0]} position={[0, 0.006, 0]}>
          <ringGeometry args={[0.03, 0.034, 32]} />
          <meshBasicMaterial color="#2f4858" side={THREE.DoubleSide} />
        </mesh>
        <mesh position={[0, 0.006, -0.036]} rotation={[-Math.PI / 2, 0, 0]}>
          <planeGeometry args={[0.008, 0.008]} />
          <meshBasicMaterial color="#5b7a8c" side={THREE.DoubleSide} />
        </mesh>
      </group>
      <group rotation={[0, yaw - chassisYaw, 0]}>
        <mesh position={[0, 0.008, -0.022]} rotation={[-Math.PI / 2, 0, 0]}>
          <coneGeometry args={[0.012, 0.03, 12]} />
          <meshStandardMaterial
            color={spinning ? '#8bd6ff' : '#48d97a'}
            emissive={spinning ? '#8bd6ff' : '#48d97a'}
            emissiveIntensity={0.5}
          />
        </mesh>
      </group>
    </group>
  );
}

/** Floating readout pinned above a device. DOM text, so it stays sharp and needs no font download. */
export function DeviceLabel({
  device,
  layout,
  selected,
  height,
}: {
  device: DeviceState;
  layout: DeviceLayout;
  selected: boolean;
  height: number;
}) {
  const detail =
    device.kind === 'motor'
      ? `${device.commandedPower >= 0 ? '+' : ''}${device.commandedPower.toFixed(2)} · ${(
          (device.velocityTicksPerSecond / Math.max(1, layout.ticksPerRev)) *
          60
        ).toFixed(0)} rpm`
      : device.kind === 'servo'
        ? `${device.hornPosition.toFixed(2)}`
        : `${device.yawDegrees.toFixed(0)}°`;

  return (
    <Html position={[0, height, 0]} center distanceFactor={1.1} pointerEvents="none" zIndexRange={[10, 0]}>
      <div
        style={{
          whiteSpace: 'nowrap',
          fontFamily: 'ui-monospace, monospace',
          fontSize: 11,
          padding: '2px 6px',
          borderRadius: 3,
          border: `1px solid ${selected ? '#7CFC00' : '#22303c'}`,
          background: 'rgba(8, 12, 16, 0.82)',
          color: selected ? '#c8ffc8' : '#9fb4c7',
          transform: 'translateY(-6px)',
        }}
      >
        {device.name} <span style={{ color: '#6f8698' }}>{detail}</span>
      </div>
    </Html>
  );
}

/** Cyan ring under a selected device, so the inspector and the scene agree on what is being edited. */
export function SelectionRing() {
  return (
    <mesh rotation={[-Math.PI / 2, 0, 0]} position={[0, -0.001, 0]}>
      <ringGeometry args={[0.062, 0.072, 40]} />
      <meshBasicMaterial color="#7CFC00" side={THREE.DoubleSide} transparent opacity={0.75} />
    </mesh>
  );
}
