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
 * Where the simulated webcam is bolted to the robot, and how wide it sees. Java
 * {@code CameraMountPayload}.
 *
 * <p>The six mount numbers are in the units the robot's configuration file uses: metres in the
 * robot frame — {@code +X} out the nose, {@code +Y} to the robot's left, {@code +Z} up, origin on
 * the floor at the footprint centre — and degrees, yaw CCW from the nose, <em>pitch positive
 * upward</em>, roll about the optical axis. They are the numbers a person types into that file, so
 * the editor can show them unconverted and a saved value reads back identically.</p>
 *
 * <p>The two field-of-view angles are the camera's own and are not editable here; they come from
 * the same file. {@link unsaved} is the server saying the session is aiming somewhere the file
 * does not yet describe.</p>
 *
 * <p>Sent in the greeting for a session whose robot declares a webcam, and again on every change.
 * {@link name} is that webcam's {@link DeviceState#name}: the device rail and this frame agree on
 * one spelling rather than on a second flag saying which device is the camera.</p>
 */
export interface CameraMountPayload {
  name: string;
  forwardMetres: number;
  leftMetres: number;
  heightMetres: number;
  yawDegrees: number;
  pitchDegrees: number;
  rollDegrees: number;
  horizontalFovDegrees: number;
  verticalFovDegrees: number;
  unsaved: boolean;
}

/** The part of a mount a browser may set: where the camera sits and where it looks. */
export type CameraMount = Pick<
  CameraMountPayload,
  | 'forwardMetres'
  | 'leftMetres'
  | 'heightMetres'
  | 'yawDegrees'
  | 'pitchDegrees'
  | 'rollDegrees'
>;

const MOUNT_NUMBERS = [
  'forwardMetres',
  'leftMetres',
  'heightMetres',
  'yawDegrees',
  'pitchDegrees',
  'rollDegrees',
  'horizontalFovDegrees',
  'verticalFovDegrees',
] satisfies (keyof CameraMountPayload)[];

/**
 * A mount out of a {@code sim/camera} payload, or null when a number is missing or not finite.
 *
 * <p>Checked rather than asserted, unlike the other server-composed frames: these numbers come off
 * a hand-edited configuration file, and they are multiplied into the frustum's geometry every
 * frame. One {@code NaN} there does not draw a wrong pyramid, it makes three.js drop the whole
 * robot group, so a typo in the file would read as the dashboard being broken.</p>
 */
export function parseCameraMount(payload: unknown): CameraMountPayload | null {
  const message = fields(payload);
  if (!message || typeof message.name !== 'string' || typeof message.unsaved !== 'boolean') {
    return null;
  }
  for (const key of MOUNT_NUMBERS) {
    if (!Number.isFinite(message[key])) return null;
  }
  return {
    name: message.name,
    forwardMetres: message.forwardMetres as number,
    leftMetres: message.leftMetres as number,
    heightMetres: message.heightMetres as number,
    yawDegrees: message.yawDegrees as number,
    pitchDegrees: message.pitchDegrees as number,
    rollDegrees: message.rollDegrees as number,
    horizontalFovDegrees: message.horizontalFovDegrees as number,
    verticalFovDegrees: message.verticalFovDegrees as number,
    unsaved: message.unsaved,
  };
}

/** Where a {@code sim/camera-save} landed, out of the {@code sim/camera-saved} reply. */
export function parseSavedCameraMount(payload: unknown): { path: string } | null {
  const message = fields(payload);
  return message && typeof message.path === 'string' ? { path: message.path } : null;
}

/**
 * What the simulated world holds besides the robot, as the server sees it: the AprilTags the
 * camera can detect, the game elements on the floor and the structures bolted to the field. Sent
 * on connect and whenever the scene changes, so the field view can draw exactly what the camera
 * view is looking at.
 *
 * <p>Geometry arrives finished. The server sends the four corners it derived the tag's pose from
 * and the pattern it printed on the tag, rather than a pose and a family id, because a mirrored
 * 36h11 pattern is mostly an invalid codeword: a tag the scene built itself from a pose would look
 * plausible here while being undetectable there, which is the disagreement this message exists to
 * remove.</p>
 *
 * <p>Java {@code ScenePayload}, whose nested {@code Tag}, {@code Corner}, {@code Element},
 * {@code Structure} and {@code Solid} are flattened here with the {@code Scene} prefix their outer
 * class gives them: TypeScript has no nested interface scope, and a bare {@code Element} would
 * shadow the DOM global of that name.</p>
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
  /**
   * The structure whose pivot carries this tag, named as {@link SceneStructure#name} names it, or
   * null when the tag is bolted to the field and never moves.
   *
   * <p>A name rather than an index: the two tipping HIVEs are two structures, and list order is
   * not something {@code sim/scene} promises. Null rather than absent because this protocol
   * serialises nulls on purpose &mdash; see {@link SimScenarios#active} &mdash; and optional
   * besides, since a server predating tipping HIVEs sends neither. Both read as falsy, which is
   * all any of this file's readers test.</p>
   */
  attachedTo?: string | null;
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

/**
 * One primitive a structure is drawn from: a box or an open-ended cylinder, posed in the field
 * frame and coloured. Java {@code ScenePayload.Solid}.
 *
 * <p>{@code shape} is the discriminator, and <em>every</em> number is always on the wire — the
 * pair the other shape has no use for arrives as zero. That is deliberate: Java writes one flat
 * record, so nothing here has to decide which arm of a union it was handed, and a reader that
 * consults the wrong pair draws a solid of size zero rather than a plausible wrong one. A box is
 * sized by {@code lengthX/lengthY/lengthZ}; a cylinder by {@code radiusMetres} and
 * {@code lengthMetres}.</p>
 *
 * <p><strong>A cylinder's axis is its own local {@code +Z}</strong>, and a cylinder is an
 * <em>open-ended shell with no end caps</em> — it models a ring or a length of pipe, not a rod. A
 * FLOWER is the reason: capping its tube would hide the POLLEN sitting inside it, which is the one
 * thing anyone who opened this view is looking for. Three.js builds cylinders about {@code Y}, so
 * {@code scene/solids.ts} owns that correction and no component repeats it.</p>
 */
export interface SceneSolid {
  shape: 'box' | 'cylinder';
  /** Field-frame metres, and the <em>centre</em> of the solid — not a corner, not an end cap. */
  x: number;
  y: number;
  z: number;
  /**
   * Which way it is turned, in the one rotation convention this codebase has and the same angles
   * the camera mount is quoted in: yaw CCW-positive about field {@code +Z}, then pitch positive
   * upward, then roll about the solid's own {@code +X}, which is to say Java {@code Pose3d}'s
   * {@code Rz(yaw) * Ry(-pitch) * Rx(roll)}. Rolling last is what makes roll spin a solid in place
   * rather than aim it somewhere else.
   */
  yawDegrees: number;
  pitchDegrees: number;
  rollDegrees: number;
  /** A box's full extents along its own axes, metres. Zero on a cylinder. */
  lengthX: number;
  lengthY: number;
  lengthZ: number;
  /** A cylinder's radius, and its extent along its own {@code +Z}, metres. Zero on a box. */
  radiusMetres: number;
  lengthMetres: number;
  /** 0-255, exactly as {@link SceneElement} carries a ball's colour. */
  red: number;
  green: number;
  blue: number;
}

/**
 * The hinge a structure swings on. Java {@code ScenePayload.Pivot}.
 *
 * <p>A point on the axis and a unit direction along it, both in the field frame, plus the angle
 * the published {@link SceneStructure#solids} are <em>already drawn at</em>. That last field is
 * the whole reason this is not just an axis: a live angle equal to it means "draw exactly what was
 * sent", so a renderer turns the group by the <em>difference</em>. Applying the live angle as an
 * absolute rotation tips the structure twice over — and at the small angles a HIVE spends most of
 * a match at, twice over still looks almost right.</p>
 */
export interface ScenePivot {
  /** A point on the axis, field-frame metres. */
  x: number;
  y: number;
  z: number;
  /**
   * A unit vector along the axis, field frame, with the rotation CCW-positive about it. Always
   * {@code (1, 0, 0)} for a HIVE today, which is not a promise: it comes off the wire because the
   * next hinge on this field need not be parallel to the audience wall.
   */
  axisX: number;
  axisY: number;
  axisZ: number;
  /** The angle, radians, that {@link SceneStructure#solids} were posed at when this was sent. */
  angleRadians: number;
}

/**
 * One piece of field furniture — the four FLOWERs today, the HIVE when it lands — as the posed
 * primitives that draw it. Java {@code ScenePayload.Structure}.
 *
 * <p>Nothing about this is computed in the browser, and that is the whole point of publishing it:
 * the coordinates are CAD measurements, and retyping thirty inch-denominated numbers into
 * TypeScript is the same class of mistake as a mirrored tag — plausible on screen, wrong everywhere
 * it matters. See {@code docs/adr/0004-structures-are-published-as-posed-primitives.md}.</p>
 *
 * <p>These are the drawing, not the colliders. The physics world builds its own from the same
 * dimensions and the two are not meant to agree in detail: a FLOWER is one cylinder drawn and a
 * ring of boxes collided with, because ode4j has no cylinder-versus-cylinder collider at all.</p>
 *
 * <p>A structure that moves carries a {@link pivot}: the {@link solids} are still absolute
 * field-frame poses, drawn at the pivot's own {@code angleRadians}, and the live angle arrives
 * through {@link SimBodies#pivots}. A structure without one — the A-frame the HIVEs hang on, the
 * four FLOWERs — never moves at all.</p>
 */
export interface SceneStructure {
  /** As the server names it, e.g. {@code FLOWER RED AUDIENCE}: unique within a scene. */
  name: string;
  solids: SceneSolid[];
  /**
   * The hinge this structure swings on, or null when it is bolted to the field. See
   * {@link ScenePivot} for why the angle it was drawn at travels with it. Null rather than absent
   * for {@link SceneTag#attachedTo}'s reason, and optional for the same one too.
   */
  pivot?: ScenePivot | null;
}

export interface ScenePayload {
  tags: SceneTag[];
  elements: SceneElement[];
  structures: SceneStructure[];
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
 * How far one pivoting structure has swung, right now. Java {@code BodiesPayload.Pivot}.
 *
 * <p>Keyed by structure name, not by index, because there are two tipping HIVEs and the name is
 * the stable thing — {@code sim/scene} does not promise list order. The angle is absolute in the
 * pivot's own convention, so it is compared against {@link ScenePivot#angleRadians} rather than
 * accumulated.</p>
 */
export interface SimPivot {
  name: string;
  angleRadians: number;
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
  /**
   * Where the tipping structures are, or absent from a server that predates them.
   *
   * <p>Sibling to {@link bodies} and under the same "only while something moved" gate, so either
   * list may be empty while the other is not: a HIVE goes on swinging after the last ball has come
   * to rest, and those frames carry no bodies at all.</p>
   */
  pivots?: SimPivot[];
}

/**
 * One upward-facing CELL of the HIVE, and what it is currently worth. Java
 * {@code ScorePayload.Cell}.
 *
 * <p>{@link cell} is the CELL's name as the field spells it — {@code RED SCORING},
 * {@code RED AUDIENCE}, {@code BLUE AUDIENCE}, {@code BLUE SCORING}. It is a label to show a
 * driver, not a key: which alliance the CELL scores for is {@link alliance}, and reading it back
 * out of the name would be a second, weaker copy of a fact the server already sent.</p>
 *
 * <p>{@link holding} counts the game elements in the CELL, POLLEN and NECTAR alike, and
 * {@link points} is twice it — the manual's two points per element. Both are sent rather than one
 * derived here, so a rule change moves in the Java that owns it instead of in a browser that
 * guessed the multiplier.</p>
 */
export interface SimScoreCell {
  cell: string;
  alliance: Alliance;
  holding: number;
  points: number;
}

/**
 * What the HIVE is holding right now, and what it would score. Java {@code ScorePayload}.
 *
 * <p>{@link cells} lists only the upward-facing CELLs — normally one per alliance. Game manual
 * §10.5.1 scores "any POLLEN and/or NECTAR left in an upward-facing CELL", so a CELL turned the
 * other way cannot score and is absent from the list entirely rather than present as a zero: a
 * zero would read as an empty CELL still waiting to be filled.</p>
 *
 * <p>Unlike {@code sim/bodies} this one is in the connect greeting. Nothing else on the wire lets
 * the browser work a score out for itself — the body stream says where balls are, not which CELL
 * they are resting in — so a browser that only learned the score on the next change would show
 * nothing through a whole match that scored early. It arrives again on OpMode init, on a scenario
 * load, and on the control cycles where the numbers actually moved, which is nothing like the
 * 50 Hz body traffic.</p>
 */
export interface SimScore {
  redPoints: number;
  bluePoints: number;
  cells: SimScoreCell[];
  /**
   * Completed HIVE TIPs, cumulative over the match, worth 20 each inside the totals above.
   *
   * <p>Sent beside the totals because a total alone is ambiguous at the moment a driver cares
   * about: a TIP empties the CELL that earned it, so six POLLEN worth 12 becoming one TIP is a
   * total that went up by 8 and a CELL that went to zero. Optional because a server from before
   * the HIVE could tip sends neither, and a readout showing a dash is better than one showing
   * zero TIPs it was never told about.</p>
   */
  redTips?: number;
  blueTips?: number;
}

/**
 * The score out of a {@code sim/score} payload, or null when the frame does not carry one.
 *
 * <p>Checked rather than asserted, unlike the sim frames beside it. The strip maps over
 * {@link SimScore.cells} to build its tooltip, and it is drawn in every view, so a frame from a
 * server that renamed or dropped the key would land as {@code undefined.map} and take the whole
 * page down — the same failure a {@code device/state} without its devices used to cause, and the
 * reason {@link parseDeviceStates} exists. The totals are checked for finiteness for
 * {@link parseCameraMount}'s reason: a {@code NaN} that reaches the readout stays on screen
 * reading "NaN" until the next change, where keeping the last honest score costs nothing.</p>
 *
 * <p>Only the wrapper is checked, not each CELL, as with {@link parseOpModeList}: the rows are
 * composed by the same tick that builds the Java record, and a bad one shows as a blank tooltip
 * line rather than a dead page.</p>
 */
export function parseSimScore(payload: unknown): SimScore | null {
  const message = fields(payload);
  if (
    !message ||
    !Number.isFinite(message.redPoints) ||
    !Number.isFinite(message.bluePoints) ||
    !Array.isArray(message.cells)
  ) {
    return null;
  }
  return {
    redPoints: message.redPoints as number,
    bluePoints: message.bluePoints as number,
    cells: message.cells as SimScoreCell[],
    // Passed through only when they are numbers, so an older server's absent pair stays absent
    // rather than becoming a zero the readout cannot tell from "no tips yet".
    ...(Number.isFinite(message.redTips) ? { redTips: message.redTips as number } : {}),
    ...(Number.isFinite(message.blueTips) ? { blueTips: message.blueTips as number } : {}),
  };
}

/**
 * The staged fields the server can load, and the one it is showing now. Java
 * {@code ScenariosPayload}.
 *
 * <p>{@link scenarios} are bare names — {@code match-staging}, not {@code match-staging.json} —
 * sorted by the server, and legitimately empty on a machine whose scenario directory holds nothing
 * yet. {@link directory} is absolute for {@link parseSavedCameraMount}'s reason: the browser may be
 * nowhere near the machine holding the files, and this is the path someone types to add one.</p>
 *
 * <p>{@link active} is null for the robot's own field — the official BioBuzz field with nothing
 * staged on it, which is what a session started with no flag shows. Null is an answer here, not the
 * absence of one.</p>
 */
export interface SimScenarios {
  scenarios: string[];
  directory: string;
  active: string | null;
}

/**
 * The scenario list out of a {@code sim/scenarios} payload, or null when the frame does not carry
 * one.
 *
 * <p>Checked rather than asserted, and checked the way {@link parseLayoutList} is, because it is the
 * same kind of frame: a directory listing, where a name that is not a string is a file someone put
 * on disk rather than a bug in the tick that built the record. Such a name is dropped and the rest
 * of the frame kept — failing the whole thing would cost the picker every other scenario over one
 * odd file — while a missing {@code scenarios} key fails it, since the picker maps over the list on
 * every render.</p>
 *
 * <p>{@link SimScenarios.active} is accepted as null and rejected as absent. The two are one
 * {@code ==} apart in JavaScript and opposites here: null is the server saying the robot's own field
 * is loaded, while a missing key is a server that has stopped reporting which scenario is on, and
 * reading that as "own field" would leave the picker confidently naming the wrong entry.</p>
 */
export function parseSimScenarios(payload: unknown): SimScenarios | null {
  const message = fields(payload);
  if (
    !message ||
    !Array.isArray(message.scenarios) ||
    typeof message.directory !== 'string' ||
    !(typeof message.active === 'string' || message.active === null)
  ) {
    return null;
  }
  return {
    scenarios: message.scenarios.filter((name): name is string => typeof name === 'string'),
    directory: message.directory,
    active: message.active,
  };
}

/** What the clock is doing before the server has said otherwise: real time, running, red alliance. */
export const DEFAULT_SIM_STATUS: SimStatus = { multiplier: 1, paused: false, alliance: 'red' };
