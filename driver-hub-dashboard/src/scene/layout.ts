import { useCallback, useEffect, useMemo, useState } from 'react';
import type { DeviceState } from '../protocol';

/**
 * Where each simulated device sits on the 3D robot, and how it is drawn.
 *
 * <p>The scene is a representation, not a simulation: nothing here feeds back into the robot code.
 * A layout only answers "where on my robot is this device, and which way does its shaft point", so
 * that a spinning wheel in the browser matches the wheel the driver is picturing.</p>
 *
 * <p>Defaults are inferred from the device name ("FL" lands at the front-left corner, axle pointing
 * left). Anything a user changes is stored as an override, so renaming or adding hardware reflows
 * the untouched devices instead of stranding them at coordinates from a previous robot.</p>
 */

/** Metres. Roughly an FTC 18" chassis, which sets the scale for every part in the scene. */
export const CHASSIS = { width: 0.38, height: 0.05, depth: 0.4, deckY: 0.105 };

/** goBILDA 5202 encoder resolution — the drivetrain VerityRobot declares. Overridable per device. */
export const DEFAULT_TICKS_PER_REV = 537.7;

/** How the far end of the shaft is dressed. Purely cosmetic, but it is what makes rotation legible. */
export type Mount = 'wheel' | 'omni' | 'spool' | 'arm' | 'bare';

export const MOUNTS: Mount[] = ['wheel', 'omni', 'spool', 'arm', 'bare'];

export interface DeviceLayout {
  /** Metres, robot frame: +X right, +Y up, +Z toward the driver (so the robot drives at -Z). */
  position: [number, number, number];
  /** Degrees, applied YXZ: yaw about +Y, then pitch about +X, then roll about the shaft. */
  rotation: [number, number, number];
  scale: number;
  mount: Mount;
  /** Flip the drawn spin without touching robot code — for a gear or belt that reverses output. */
  invert: boolean;
  /** Encoder ticks per shaft revolution; converts wire ticks into an angle. */
  ticksPerRev: number;
}

export type LayoutOverrides = Record<string, Partial<DeviceLayout>>;

const STORAGE_KEY = 'driverhub.scene.layout.v1';

/** Two-letter corner names, in the orders teams actually write them. */
const CORNER_PAIRS: Record<string, [side: number, end: number]> = {
  fl: [-1, -1],
  lf: [-1, -1],
  fr: [1, -1],
  rf: [1, -1],
  bl: [-1, 1],
  lb: [-1, 1],
  br: [1, 1],
  rb: [1, 1],
  rl: [-1, 1],
  rr: [1, 1],
};

function inferCorner(rawName: string): [side: number, end: number] | null {
  const name = rawName.toLowerCase().replace(/[^a-z]/g, '');
  const pair = CORNER_PAIRS[name];
  if (pair) return pair;

  const left = name.includes('left');
  const right = name.includes('right');
  const front = name.includes('front');
  const back = name.includes('back') || name.includes('rear');
  if ((!left && !right) || (!front && !back)) return null;
  return [left ? -1 : 1, front ? -1 : 1];
}

/**
 * The layout a device gets before anyone touches it.
 *
 * <p>{@code spare} is the device's index among the devices that could not be placed by name; they
 * are dealt out in a row above the deck rather than piled on the origin.</p>
 */
export function defaultLayout(device: DeviceState, spare: number): DeviceLayout {
  const base: DeviceLayout = {
    position: [0, CHASSIS.deckY + 0.04, 0],
    rotation: [0, 0, 0],
    scale: 1,
    mount: device.kind === 'servo' ? 'arm' : 'bare',
    invert: false,
    ticksPerRev: DEFAULT_TICKS_PER_REV,
  };

  if (device.kind === 'imu') {
    return { ...base, position: [0, CHASSIS.deckY + 0.01, 0], mount: 'bare', scale: 1 };
  }

  const corner = device.kind === 'motor' ? inferCorner(device.name) : null;
  if (corner) {
    const [side, end] = corner;
    return {
      ...base,
      // Axle out through the side of the chassis: local +Z is the shaft, so yaw ±90° aims it at ±X.
      position: [side * (CHASSIS.width / 2 + 0.03), 0.05, end * 0.145],
      rotation: [side * 90, 0, 0],
      mount: 'wheel',
    };
  }

  // Dealt out in a row across the back of the deck, clear of the IMU at its centre.
  return {
    ...base,
    position: [(spare - 1) * 0.13, CHASSIS.deckY + 0.05, 0.13],
    rotation: [0, 0, 0],
  };
}

function load(): LayoutOverrides {
  try {
    const stored = localStorage.getItem(STORAGE_KEY);
    return stored ? (JSON.parse(stored) as LayoutOverrides) : {};
  } catch {
    // A corrupt or unreadable store is not worth failing the view over; start from defaults.
    return {};
  }
}

export interface LayoutApi {
  /** Effective layout per device name: defaults with any override applied. */
  layout: Record<string, DeviceLayout>;
  customized: (name: string) => boolean;
  update: (name: string, patch: Partial<DeviceLayout>) => void;
  reset: (name: string) => void;
  resetAll: () => void;
}

export function useLayout(devices: DeviceState[]): LayoutApi {
  const [overrides, setOverrides] = useState<LayoutOverrides>(load);

  useEffect(() => {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(overrides));
  }, [overrides]);

  const layout = useMemo(() => {
    const result: Record<string, DeviceLayout> = {};
    let spare = 0;
    for (const device of devices) {
      const placedByName = device.kind === 'imu' || inferCorner(device.name) !== null;
      result[device.name] = {
        ...defaultLayout(device, placedByName ? 0 : spare++),
        ...overrides[device.name],
      };
    }
    return result;
  }, [devices, overrides]);

  const update = useCallback((name: string, patch: Partial<DeviceLayout>) => {
    setOverrides((previous) => ({ ...previous, [name]: { ...previous[name], ...patch } }));
  }, []);

  const reset = useCallback((name: string) => {
    setOverrides((previous) => {
      const next = { ...previous };
      delete next[name];
      return next;
    });
  }, []);

  return {
    layout,
    customized: useCallback((name: string) => overrides[name] !== undefined, [overrides]),
    update,
    reset,
    resetAll: useCallback(() => setOverrides({}), []),
  };
}
