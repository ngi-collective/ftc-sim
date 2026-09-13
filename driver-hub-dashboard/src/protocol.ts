// Mirrors org.ngicollective.testframework.dashboard.protocol. Keep in step with the Java side:
// these are the only shapes that cross the socket.

export interface Envelope {
  namespace: 'opmode' | 'telemetry' | 'gamepad' | 'device' | 'layout';
  type: string;
  payload: unknown;
}

/** A layout file the server holds, named as it is on disk. Its contents belong to the 3D scene. */
export interface LayoutRecord {
  name: string;
  layout: unknown;
}

/**
 * Layout messages are checked rather than asserted: their contents come off disk, where a hand
 * edit or a file from an older schema is an ordinary thing to meet, and a bad one must not take
 * the dashboard down with it.
 */
function fields(value: unknown): Record<string, unknown> | null {
  return typeof value === 'object' && value !== null ? (value as Record<string, unknown>) : null;
}

export function parseLayoutList(
  payload: unknown,
): { layouts: string[]; directory: string } | null {
  const message = fields(payload);
  if (!message || !Array.isArray(message.layouts) || typeof message.directory !== 'string') {
    return null;
  }
  return {
    layouts: message.layouts.filter((name): name is string => typeof name === 'string'),
    directory: message.directory,
  };
}

export function parseLayoutRecord(payload: unknown): LayoutRecord | null {
  const message = fields(payload);
  if (!message || typeof message.name !== 'string' || fields(message.layout) === null) {
    return null;
  }
  return { name: message.name, layout: message.layout };
}

export function parseSavedLayout(payload: unknown): { name: string; path: string } | null {
  const message = fields(payload);
  if (!message || typeof message.name !== 'string' || typeof message.path !== 'string') {
    return null;
  }
  return { name: message.name, path: message.path };
}

export interface OpModeInfo {
  name: string;
  group: string;
  flavor: 'TeleOp' | 'Autonomous';
  className: string;
}

export type OpModeState = 'STOPPED' | 'INIT' | 'RUNNING';

export interface OpModeStatus {
  opMode: string | null;
  state: OpModeState;
  failure: string | null;
}

export interface TelemetryFrame {
  timestamp: number;
  lines: string[];
}

export interface DeviceState {
  name: string;
  kind: 'motor' | 'servo' | 'imu' | 'unknown';
  behavior: string;
  commandedPower: number;
  physicalPower: number;
  velocityTicksPerSecond: number;
  position: number;
  mode: string | null;
  commandedPosition: number;
  hornPosition: number;
  yawDegrees: number;
  yawRateDegreesPerSecond: number;
}

export interface GamepadState {
  left_stick_x: number;
  left_stick_y: number;
  right_stick_x: number;
  right_stick_y: number;
  left_trigger: number;
  right_trigger: number;
  dpad_up: boolean;
  dpad_down: boolean;
  dpad_left: boolean;
  dpad_right: boolean;
  a: boolean;
  b: boolean;
  x: boolean;
  y: boolean;
  options: boolean;
}

export const NEUTRAL_GAMEPAD: GamepadState = {
  left_stick_x: 0,
  left_stick_y: 0,
  right_stick_x: 0,
  right_stick_y: 0,
  left_trigger: 0,
  right_trigger: 0,
  dpad_up: false,
  dpad_down: false,
  dpad_left: false,
  dpad_right: false,
  a: false,
  b: false,
  x: false,
  y: false,
  options: false,
};

/** The behaviors the backend will accept, per device kind. */
export const BEHAVIORS: Record<string, { type: string; value?: number; label: string }[]> = {
  motor: [
    { type: 'ideal', label: 'ideal' },
    { type: 'ramping', value: 0.5, label: 'ramping 0.5s' },
    { type: 'stalled', label: 'stalled' },
  ],
  servo: [
    { type: 'instant', label: 'instant' },
    { type: 'sweeping', value: 1, label: 'sweeping 1s' },
    { type: 'jammed', label: 'jammed' },
  ],
  imu: [
    { type: 'followingYawRate', label: 'follow yaw rate' },
    { type: 'rotating', value: 45, label: 'rotating 45deg/s' },
    { type: 'stationary', label: 'stationary' },
  ],
};
