/**
 * What one server frame does to the dashboard's state, with the socket and React left out.
 *
 * <p>The dispatch used to live inside the {@code onmessage} closure of the hook's effect, where it
 * had fourteen {@code useState} setters and a socket in scope and so could not be exercised at all.
 * Here the frame arrives as data and the state arrives as {@link MessageSinks}, so the routing —
 * which is the part that silently drifted from the Java side — is ordinary testable code.</p>
 */

import {
  parseCameraMount,
  parseDeviceStates,
  parseLayoutList,
  parseLayoutRecord,
  parseOpModeList,
  parseSavedCameraMount,
  parseSavedLayout,
  parseSimScenarios,
  parseSimScore,
  type CameraMountPayload,
  type CameraStreamInfo,
  type DeviceState,
  type Envelope,
  type LayoutRecord,
  type OpModeInfo,
  type OpModeStatus,
  type ScenePayload,
  type SimBodies,
  type SimConfig,
  type SimPose,
  type SimScenarios,
  type SimScore,
  type SimStatus,
  type TelemetryFrame,
} from './protocol';

/**
 * Pose arrives at 50 Hz, which is what the scene wants and far more than React does: every commit
 * re-renders the console log and the device rail along with the canvas. So a pose does two
 * different things, and they are deliberately not the same thing.
 */
export const POSE_COMMIT_INTERVAL_MS = 50;

/**
 * The two fates of a pose.
 *
 * <p>{@link publish} runs on every single frame and must stay off React's path: it feeds listeners
 * that write a transform in a render loop, which is how the 3D view moves at 50 Hz without a
 * component re-rendering. {@link commit} is the React state write, and it is the one that is
 * rationed.</p>
 */
export interface PoseSink {
  publish: (pose: SimPose) => void;
  commit: (pose: SimPose) => void;
}

export interface MessageSinks {
  setError: (message: string) => void;
  setOpModes: (opModes: OpModeInfo[]) => void;
  setStatus: (status: OpModeStatus) => void;
  setTelemetry: (frame: TelemetryFrame) => void;
  setDevices: (devices: DeviceState[]) => void;
  setLayouts: (layouts: string[]) => void;
  setLayoutDirectory: (directory: string) => void;
  setLoadedLayout: (layout: LayoutRecord) => void;
  setSavedLayout: (saved: { name: string; path: string }) => void;
  setSimConfig: (config: SimConfig) => void;
  setSimScene: (scene: ScenePayload) => void;
  setCameraStream: (stream: CameraStreamInfo) => void;
  setCameraMount: (mount: CameraMountPayload) => void;
  /** Where {@code sim/camera-save} wrote, so the rail can name the file worth committing. */
  setSavedCameraMount: (saved: { path: string }) => void;
  setSimStatus: (status: SimStatus) => void;
  /**
   * What the HIVE is holding, as of the last control cycle that changed it. Plain React state
   * rather than a buffer or a listener fan-out like the pose and the bodies: this one is a pair of
   * numbers a person reads, and it arrives on the greeting and then only when it moves, so there
   * is no stream here to ration.
   */
  setSimScore: (score: SimScore) => void;
  /**
   * The staged fields on the server's disk and the one that is loaded, for the picker in the sim
   * strip. Arrives in the greeting and again whenever the active one changes, so the picker is
   * populated before anyone opens it and never shows a scenario the server has since moved off.
   */
  setSimScenarios: (scenarios: SimScenarios) => void;
  pose: PoseSink;
  /**
   * Bodies never reach React at all, unlike the pose, which commits a throttled copy for the
   * readouts. Nothing on the page displays a ball's coordinates — they are only ever drawn — so a
   * commit would re-render the console log and the device rail fifty times a second to change
   * nothing a person is reading.
   */
  bodies: (frame: SimBodies) => void;
}

/**
 * A {@link PoseSink} that fans every pose out immediately and commits at most one per
 * {@link POSE_COMMIT_INTERVAL_MS} — the cadence a plugged-in controller already re-renders the page
 * at, so a running simulation costs the tree nothing it was not already paying.
 *
 * <p>The clock is a parameter because the interesting behaviour is entirely about time: a test that
 * had to wait 50 real milliseconds to observe the second commit would be a test that sometimes
 * observes the third.</p>
 *
 * @param now monotonic milliseconds; {@code performance.now} in the browser
 */
export function createThrottledPoseSink(options: {
  publish: (pose: SimPose) => void;
  commit: (pose: SimPose) => void;
  now: () => number;
}): PoseSink {
  // Null rather than zero so the first pose of a session always commits: with a monotonic clock
  // that starts wherever it likes, zero would mean "committed at some point in the past" on one
  // clock and "committed just now" on another, and the robot would be invisible until the next
  // interval elapsed.
  let lastCommit: number | null = null;
  return {
    publish: options.publish,
    commit: (pose) => {
      const now = options.now();
      if (lastCommit !== null && now - lastCommit < POSE_COMMIT_INTERVAL_MS) return;
      lastCommit = now;
      options.commit(pose);
    },
  };
}

/**
 * Routes one frame to the state it belongs to.
 *
 * <p>A frame this build does not recognise — an unknown namespace, an unknown type, a payload
 * missing the key its wrapper promised — is dropped, never half-applied. The server is deployed
 * separately from the browser, so meeting a frame from a newer or older one is ordinary, and the
 * dashboard's job in that moment is to keep showing the frames it does understand.</p>
 *
 * <p>Layout payloads are validated, and so is anything whose absence would take a render down
 * rather than leave it stale — the list wrappers, the camera mount, the score. Everything else is
 * asserted. That asymmetry is deliberate: layouts come off disk, where a hand edit or a file from
 * an older schema is an ordinary thing to meet, while the rest are composed by the same tick that
 * builds the Java record, so a shape mismatch there is a bug to fix on both sides rather than a
 * file to survive.</p>
 */
export function applyMessage(envelope: Envelope, sinks: MessageSinks): void {
  const payload = envelope.payload as never;

  if (envelope.type === 'error') {
    // Any namespace may answer with an error, including one this build has never heard of, so this
    // is checked before the dispatch rather than inside it.
    const message = (envelope.payload as { message?: unknown } | null | undefined)?.message;
    if (typeof message === 'string') sinks.setError(message);
    return;
  }

  switch (`${envelope.namespace}/${envelope.type}`) {
    case 'opmode/list': {
      const opModes = parseOpModeList(envelope.payload);
      if (opModes) sinks.setOpModes(opModes);
      break;
    }
    case 'opmode/status':
      sinks.setStatus(payload as OpModeStatus);
      // No clearing the pose on STOPPED: the robot is still there, and the next pose says where.
      break;
    case 'telemetry/frame':
      sinks.setTelemetry(payload as TelemetryFrame);
      break;
    case 'device/state': {
      const devices = parseDeviceStates(envelope.payload);
      if (devices) sinks.setDevices(devices);
      break;
    }
    case 'layout/list': {
      const list = parseLayoutList(envelope.payload);
      if (list) {
        sinks.setLayouts(list.layouts);
        sinks.setLayoutDirectory(list.directory);
      }
      break;
    }
    case 'layout/data': {
      const record = parseLayoutRecord(envelope.payload);
      if (record) sinks.setLoadedLayout(record);
      break;
    }
    case 'layout/saved': {
      const saved = parseSavedLayout(envelope.payload);
      if (saved) sinks.setSavedLayout(saved);
      break;
    }
    case 'sim/pose': {
      const next = payload as SimPose;
      sinks.pose.publish(next);
      sinks.pose.commit(next);
      break;
    }
    case 'sim/bodies':
      sinks.bodies(payload as SimBodies);
      break;
    case 'sim/config':
      sinks.setSimConfig(payload as SimConfig);
      break;
    case 'sim/scene':
      sinks.setSimScene(payload as ScenePayload);
      break;
    case 'sim/status':
      sinks.setSimStatus(payload as SimStatus);
      break;
    case 'sim/score': {
      const score = parseSimScore(envelope.payload);
      if (score) sinks.setSimScore(score);
      break;
    }
    case 'sim/scenarios': {
      const scenarios = parseSimScenarios(envelope.payload);
      if (scenarios) sinks.setSimScenarios(scenarios);
      break;
    }
    case 'sim/camera': {
      const mount = parseCameraMount(envelope.payload);
      if (mount) sinks.setCameraMount(mount);
      break;
    }
    case 'sim/camera-saved': {
      const saved = parseSavedCameraMount(envelope.payload);
      if (saved) sinks.setSavedCameraMount(saved);
      break;
    }
    case 'camera/stream':
      sinks.setCameraStream(payload as CameraStreamInfo);
      break;
  }
}
