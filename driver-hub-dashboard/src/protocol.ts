// Mirrors org.ngicollective.testframework.dashboard.protocol. Keep in step with the Java side:
// these are the only shapes that cross the socket.

/**
 * The namespaces the server speaks. {@code gamepad} is browser-to-server only; the rest carry
 * frames the other way.
 *
 * <p>{@code camera} is one of them: {@code DashboardServer.onOpen} sends {@code camera/stream} to
 * every browser that connects to a session with a camera. It was missing from this union for as
 * long as the dispatch below keyed on a template literal, which widens to {@code string} and so
 * cannot notice a namespace nobody declared.</p>
 */
export type Namespace =
  | 'opmode'
  | 'telemetry'
  | 'gamepad'
  | 'device'
  | 'layout'
  | 'sim'
  | 'camera';

export interface Envelope {
  /**
   * Typed as the namespaces we know about, but the wire can carry any string: a server a version
   * ahead names namespaces this build has never heard of, and {@link parseEnvelope} keeps them
   * rather than rejecting the frame, so the dispatch can ignore them one by one.
   */
  namespace: Namespace;
  type: string;
  payload: unknown;
}

/**
 * One socket frame, or null when the text was not a frame at all.
 *
 * <p>Guarded because {@code JSON.parse} on a truncated or non-JSON message throws, and a throw out
 * of the socket's {@code onmessage} is an unhandled rejection in the middle of a match rather than
 * a dropped frame. Nothing on the wire is worth taking the dashboard down for.</p>
 */
export function parseEnvelope(text: string): Envelope | null {
  let parsed: unknown;
  try {
    parsed = JSON.parse(text);
  } catch {
    return null;
  }
  const message = fields(parsed);
  if (!message || typeof message.namespace !== 'string' || typeof message.type !== 'string') {
    return null;
  }
  return {
    namespace: message.namespace as Namespace,
    type: message.type,
    payload: message.payload,
  };
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

/**
 * The OpMode list out of an {@code opmode/list} payload, or null when the frame has no list in it.
 *
 * <p>Only the wrapper is checked, not each entry: the list is server-composed in the same tick that
 * builds the Java record, so a malformed entry is a bug to fix on both sides. A missing
 * {@code opModes} key is different in kind — it used to reach {@code setOpModes(undefined)} and
 * take out the next render of the OpMode picker, which is a blank dashboard rather than a stale
 * one.</p>
 */
export function parseOpModeList(payload: unknown): OpModeInfo[] | null {
  const message = fields(payload);
  return message && Array.isArray(message.opModes) ? (message.opModes as OpModeInfo[]) : null;
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

/** The device list out of a {@code device/state} payload, wrapper-checked as {@link parseOpModeList}. */
export function parseDeviceStates(payload: unknown): DeviceState[] | null {
  const message = fields(payload);
  return message && Array.isArray(message.devices) ? (message.devices as DeviceState[]) : null;
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

/**
 * A behavior named on the wire, from a closed catalog. Mirrors Java {@code BehaviorSpec}: a name
 * and its single tuning number, never code, so selecting one can never become a way to run
 * something on the robot's machine.
 */
export interface BehaviorSpec {
  type: string;
  /**
   * The behavior's one tuning number (ramp seconds, degrees/second, ...), where it has one.
   * Omitted for the behaviors that take none; Gson reads an absent number as 0.0, which is exactly
   * what those behaviors ignore.
   */
  value?: number;
}

/** The behaviors the backend will accept, per device kind, each with the label the rail shows. */
export const BEHAVIORS: Record<string, (BehaviorSpec & { label: string })[]> = {
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
 * Java {@code CameraStreamInfo}.
 *
 * The stream is MJPEG on its own HTTP port, not frames on this socket: an `<img>` pointed at `url`
 * decodes every frame itself, so nothing here ever touches pixel data. Absent for a robot that
 * declares no camera.
 */
export interface CameraStreamInfo {
  url: string;
  width: number;
  height: number;
  /** Below the simulated camera's own rate: this is a sampled view, not every frame. */
  framesPerSecond: number;
}

/**
 * What the simulated world holds besides the robot, as the server sees it: the AprilTags the
 * camera can detect and the game elements on the floor. Sent on connect and whenever the scene
 * changes, so the field view can draw exactly what the camera view is looking at.
 *
 * <p>Geometry arrives finished. The server sends the four corners it derived the tag's pose from
 * and the pattern it printed on the tag, rather than a pose and a family id, because a mirrored
 * 36h11 pattern is mostly an invalid codeword: a tag the scene built itself from a pose would look
 * plausible here while being undetectable there, which is the disagreement this message exists to
 * remove.</p>
 *
 * <p>Java {@code ScenePayload}, whose nested {@code Tag}, {@code Corner} and {@code Element} are
 * flattened here with the {@code Scene} prefix their outer class gives them: TypeScript has no
 * nested interface scope, and a bare {@code Element} would shadow the DOM global of that name.</p>
 */
export interface SceneCorner {
  x: number;
  y: number;
  z: number;
}

/** One AprilTag, placed and spelled out cell by cell. Java {@code ScenePayload.Tag}. */
export interface SceneTag {
  id: number;
  /** The owning cluster, named as the server's {@code TagCluster} names it. */
  cluster: string;
  sizeMetres: number;
  /**
   * Field-frame metres, ordered [topLeft, topRight, bottomRight, bottomLeft] as seen by a viewer
   * looking at the tag's visible face. The order is the contract: nothing downstream can tell a
   * rotated or mirrored quad from a correct one except by the tag coming out wrong.
   */
  corners: SceneCorner[];
  /**
   * The pattern itself: one string per row, {@code 'B'} black and {@code 'W'} white, row 0 the top
   * row and column 0 the left as that same viewer sees them. The server stays the only place the
   * family's bit tables live.
   */
  cells: string[];
}

/** A game element: a coloured sphere at a field-frame centre. Java {@code ScenePayload.Element}. */
export interface SceneElement {
  /**
   * Which ball this is, for as long as the session lasts. The same number identifies it in
   * {@link SimBodies}, which is how a position arriving fifty times a second is matched to the
   * colour and radius that arrived once.
   */
  id: number;
  name: string;
  x: number;
  y: number;
  z: number;
  radiusMetres: number;
  red: number;
  green: number;
  blue: number;
}

export interface ScenePayload {
  tags: SceneTag[];
  elements: SceneElement[];
}

/**
 * One body's position and orientation. Java {@code BodiesPayload.Body}.
 *
 * <p>Field frame, metres, {@code +Z} up, and a unit quaternion in {@code (x, y, z, w)} order —
 * three.js' order, so the browser hands it to a mesh without reshuffling.</p>
 */
export interface SimBody {
  id: number;
  x: number;
  y: number;
  z: number;
  qx: number;
  qy: number;
  qz: number;
  qw: number;
}

/**
 * Where the moving bodies on the field are. Java {@code BodiesPayload}.
 *
 * <p>Arrives only on the control cycles that moved something, so silence means a field at rest
 * rather than a lost connection — the pose stream is what says the server is alive.</p>
 */
export interface SimBodies {
  timestampMillis: number;
  elapsedSeconds: number;
  bodies: SimBody[];
}

/** What the clock is doing before the server has said otherwise: real time, running, red alliance. */
export const DEFAULT_SIM_STATUS: SimStatus = { multiplier: 1, paused: false, alliance: 'red' };
