// Mirrors org.ngicollective.testframework.dashboard.protocol. Keep in step with the Java side:
// these are the only shapes that cross the socket.

export interface Envelope {
  namespace: 'opmode' | 'telemetry' | 'gamepad' | 'device' | 'layout' | 'sim';
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
  /** How many times this motor was commanded past ±1 since the last reset, and the worst of them. */
  clippedCommandCount: number;
  lastClippedCommand: number;
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
  left_bumper: boolean;
  right_bumper: boolean;
  left_stick_button: boolean;
  right_stick_button: boolean;
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
  left_bumper: false,
  right_bumper: false,
  left_stick_button: false,
  right_stick_button: false,
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

/**
 * The simulated field and the robot driving on it.
 *
 * <p>These are server-composed, so they are typed rather than parsed: the same tick that builds
 * them builds the Java record, and a shape mismatch is a bug to fix on both sides, not a bad file
 * to survive.</p>
 *
 * <p>Poses are in the FTC field frame — metres from field centre, heading CCW-positive with zero
 * facing +X. The three.js conversion is the scene's business and lives there.</p>
 */
export type Alliance = 'red' | 'blue';

export interface SimPose {
  timestampMillis: number;
  elapsedSeconds: number;
  x: number;
  y: number;
  headingDegrees: number;
  forwardVelocity: number;
  lateralVelocity: number;
  yawRateDegreesPerSecond: number;
  wallContact: boolean;
}

export interface SimChassis {
  widthMetres: number;
  lengthMetres: number;
  heightMetres: number;
  deckHeightMetres: number;
}

export interface SimDrivetrain {
  type: string;
  wheelRadiusMetres: number;
  gearRatio: number;
  trackWidthMetres: number;
  wheelBaseMetres: number;
  strafeEfficiency: number;
}

export interface SimField {
  sizeMetres: number;
  wallHeightMetres: number;
  tileMetres: number;
}

export interface SimConfig {
  robot: { name: string; chassis: SimChassis; drivetrain: SimDrivetrain };
  field: SimField;
}

export interface SimStatus {
  multiplier: number;
  paused: boolean;
  alliance: Alliance;
}

/**
 * Where the Dashboard Camera View's pictures come from, as the server advertises it on connect.
 *
 * The stream is MJPEG on its own HTTP port, not frames on this socket: an `<img>` pointed at `url`
 * decodes every frame itself, so nothing here ever touches pixel data. Absent for a robot that
 * declares no camera.
 */
export interface CameraStream {
  url: string;
  width: number;
  height: number;
  /** Below the simulated camera's own rate: this is a sampled view, not every frame. */
  framesPerSecond: number;
}

/** What the clock is doing before the server has said otherwise: real time, running, red alliance. */
export const DEFAULT_SIM_STATUS: SimStatus = { multiplier: 1, paused: false, alliance: 'red' };
