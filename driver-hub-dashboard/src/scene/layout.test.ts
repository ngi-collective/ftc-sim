import { describe, expect, it } from 'vitest';
import shipped from '../../../TeamCode/robot-layouts/vertical-shafts.json';
import type { DeviceState } from '../protocol';
import {
  CHASSIS,
  DEFAULT_TICKS_PER_REV,
  defaultLayout,
  forFile,
  inferWheel,
  parseDeviceLayout,
  parseLayoutFile,
  placements,
  type DeviceLayout,
} from './layout';

/** A device as the rail reports one; only the name and kind decide a default placement. */
function device(name: string, kind: DeviceState['kind']): DeviceState {
  return {
    name,
    kind,
    behavior: 'default',
    commandedPower: 0,
    physicalPower: 0,
    velocityTicksPerSecond: 0,
    position: 0,
    mode: null,
    commandedPosition: 0,
    hornPosition: 0,
    yawDegrees: 0,
    yawRateDegreesPerSecond: 0,
    clippedCommandCount: 0,
    lastClippedCommand: 0,
  };
}

/** A placement that parses, to vary one field at a time against. */
const VALID: DeviceLayout = {
  position: [0.1, 0.2, 0.3],
  rotation: [0, 90, 0],
  scale: 1,
  mount: 'wheel',
  ratio: -1,
  outputOffset: [0, 0, 0.034],
  outputRotation: [90, 0, 0],
  ticksPerRev: DEFAULT_TICKS_PER_REV,
};

describe('reading a wheel position out of a device name', () => {
  it('reads the same corner from every order a team writes it in', () => {
    expect(inferWheel('fl')).toEqual({ side: -1, end: -1 });
    expect(inferWheel('lf')).toEqual({ side: -1, end: -1 });
    expect(inferWheel('FL')).toEqual({ side: -1, end: -1 });
    expect(inferWheel('frontLeftDrive')).toEqual({ side: -1, end: -1 });
    expect(inferWheel('front_left_motor')).toEqual({ side: -1, end: -1 });
  });

  it('keeps left on the left and right on the right, front at the front', () => {
    expect(inferWheel('br')).toEqual({ side: 1, end: 1 });
    expect(inferWheel('rearRight')).toEqual({ side: 1, end: 1 });
    expect(inferWheel('backRightDrive')).toEqual({ side: 1, end: 1 });
    expect(inferWheel('frontRight')).toEqual({ side: 1, end: -1 });
  });

  it('leaves the end unknown when the name says a side but not which end', () => {
    expect(inferWheel('leftDrive')).toEqual({ side: -1, end: null });
    expect(inferWheel('rightMotor')).toEqual({ side: 1, end: null });
  });

  it('places nothing from a name that is not about a wheel at all', () => {
    expect(inferWheel('arm')).toBeNull();
    expect(inferWheel('lift')).toBeNull();
    expect(inferWheel('intake')).toBeNull();
  });
});

describe('the layout a device gets before anyone touches it', () => {
  it('aims each wheel axle out of the side of the chassis it sits on', () => {
    const left = defaultLayout(device('FL', 'motor'), 0);
    const right = defaultLayout(device('FR', 'motor'), 0);

    // The shaft is the model's local +Z, so the yaw is what aims it at the field. Left and right
    // must be yawed opposite ways; the same sign on both mirrors half the drivetrain, and the
    // wheels on one side then turn the wrong way for every command sent to them.
    expect(left.rotation[0]).toBe(-90);
    expect(right.rotation[0]).toBe(90);
    expect(Math.sign(left.rotation[0])).toBe(Math.sign(left.position[0]));
    expect(Math.sign(right.rotation[0])).toBe(Math.sign(right.position[0]));
    expect(left.position[0]).toBeCloseTo(-(CHASSIS.width / 2 + 0.03), 10);
    expect(left.mount).toBe('wheel');
  });

  it('puts the front wheels in front of the back wheels', () => {
    const front = defaultLayout(device('FL', 'motor'), 0);
    const back = defaultLayout(device('BL', 'motor'), 0);
    // The nose is local -Z, so a front wheel sits at a lower Z than a back one.
    expect(front.position[2]).toBeLessThan(back.position[2]);
    expect(front.position[0]).toBe(back.position[0]);
  });

  it('centres a wheel along its side when the name did not say which end', () => {
    const named = defaultLayout(device('leftDrive', 'motor'), 0);
    expect(named.position[2]).toBe(0);
    expect(named.position[0]).toBeLessThan(0);
  });

  it('deals unplaceable devices out in a row rather than stacking them on each other', () => {
    const first = defaultLayout(device('arm', 'servo'), 0);
    const second = defaultLayout(device('wrist', 'servo'), 1);
    expect(first.position[0]).not.toBe(second.position[0]);
    expect(first.position[1]).toBe(second.position[1]);
    expect(first.mount).toBe('arm');
  });

  it('sits the IMU on the deck rather than out on a shaft', () => {
    const imu = defaultLayout(device('imu', 'imu'), 0);
    expect(imu.position).toEqual([0, CHASSIS.deckY + 0.01, 0]);
    expect(imu.mount).toBe('bare');
  });
});

describe('reading a hand-edited layout file', () => {
  it('drops the one device that will not parse and keeps the rest', () => {
    const parsed = parseLayoutFile({
      version: 1,
      devices: {
        FL: VALID,
        FR: { ...VALID, position: [0.1, 'up', 0.3] },
        BL: VALID,
      },
    });

    // "One motor lost its place" is a different message to the driver than "your layout file was
    // rejected", and the difference is decided here.
    expect(parsed).not.toBeNull();
    expect(Object.keys(parsed?.devices ?? {})).toEqual(['FL', 'BL']);
  });

  it('rejects the file only when no device in it survived', () => {
    expect(parseLayoutFile({ version: 1, devices: { FL: { position: [0, 0, 0] } } })).toBeNull();
    expect(parseLayoutFile({ version: 1, devices: {} })).toBeNull();
    expect(parseLayoutFile({ version: 1 })).toBeNull();
    expect(parseLayoutFile(null)).toBeNull();
    expect(parseLayoutFile('{}')).toBeNull();
  });

  it('refuses a placement that would draw a device wrong rather than guessing at it', () => {
    expect(parseDeviceLayout(VALID)).toEqual(VALID);
    expect(parseDeviceLayout({ ...VALID, mount: 'flywheel' })).toBeNull();
    expect(parseDeviceLayout({ ...VALID, position: [0, 0] })).toBeNull();
    expect(parseDeviceLayout({ ...VALID, scale: '1' })).toBeNull();
    // A zero or negative resolution turns every encoder reading into a division by nothing.
    expect(parseDeviceLayout({ ...VALID, ticksPerRev: 0 })).toBeNull();
    expect(parseDeviceLayout({ ...VALID, ticksPerRev: -537.7 })).toBeNull();
  });

  it('still reads the layout the team actually ships', () => {
    const parsed = parseLayoutFile(shipped);
    // Every device the file names keeps its placement: the plausible bug in a parser this strict
    // is dropping one entry quietly, which draws that device back at the origin. Named against
    // the file's own keys rather than a hard-coded list, because the file grows a device whenever
    // the robot does.
    expect(Object.keys(parsed?.devices ?? {})).toEqual(Object.keys(shipped.devices));
    expect(parsed?.devices.FL.mount).toBe('wheel');
    expect(parsed?.devices.FL.ratio).toBe(1);
    expect(parsed?.devices.FR.ratio).toBe(-1);
  });
});

describe('writing a layout back out', () => {
  it('rounds the placement so a committed file diffs on real changes, not on float noise', () => {
    const written = forFile({
      FL: {
        ...VALID,
        position: [0.1 + 0.2, -0.16000000000000003, 0.07000000000000002],
        rotation: [90.00000000000001, 0, 0],
        scale: 1.0000000000000002,
      },
    });

    expect(written.FL.position).toEqual([0.3, -0.16, 0.07]);
    expect(written.FL.rotation).toEqual([90, 0, 0]);
    expect(written.FL.scale).toBe(1);
  });

  it('keeps a placement that is already written to that precision exactly as it was', () => {
    const parsed = parseLayoutFile(shipped);
    expect(forFile(parsed?.devices ?? {})).toEqual(parsed?.devices);
  });
});

describe('placing the devices on the robot', () => {
  it('leaves the webcam out, because its real mount is not a placement at all', () => {
    // The webcam is aimed by the robot's configuration file, which is what the rendered camera
    // reads. A cosmetic placement beside it is a set of sliders that look like they aim the camera
    // and do not, and it ends up committed in the layout file where nothing ever reads it.
    const placed = placements([device('FL', 'motor'), device('Webcam 1', 'unknown')], {}, 'Webcam 1');
    expect(Object.keys(placed)).toEqual(['FL']);
  });

  it('leaves it out even when a layout file already saved one for it', () => {
    // Layout files committed before the camera had a mount editor carry a "Webcam 1" placement,
    // and those files are loaded straight into the overrides. The entry has to die on load, not
    // survive as the one device whose stored numbers outlive the feature.
    const stale = { 'Webcam 1': { position: [0.1, 0.2, 0.3] as [number, number, number] } };
    expect(placements([device('Webcam 1', 'unknown')], stale, 'Webcam 1')).toEqual({});
  });

  it('closes up the row of spares the camera used to sit in', () => {
    // Devices no rule can place are dealt out in a row across the deck by index. Skipping the
    // camera must not leave a gap where it was standing.
    const devices = [device('Webcam 1', 'unknown'), device('sensorColor', 'unknown')];
    expect(placements(devices, {}, 'Webcam 1').sensorColor).toEqual(
      defaultLayout(device('sensorColor', 'unknown'), 0),
    );
  });
});
