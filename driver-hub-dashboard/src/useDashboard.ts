import { useCallback, useEffect, useRef, useState } from 'react';
import {
  applyMessage,
  createThrottledPoseSink,
  type MessageSinks,
} from './messages';
import { createBodyBuffer, type BodyBuffer } from './scene/bodies';
import {
  DEFAULT_SIM_STATUS,
  parseEnvelope,
  type Alliance,
  type BehaviorSpec,
  type CameraStreamInfo,
  type DeviceState,
  type GamepadState,
  type LayoutRecord,
  type OpModeInfo,
  type OpModeStatus,
  type ScenePayload,
  type SimConfig,
  type SimPose,
  type SimStatus,
  type TelemetryFrame,
} from './protocol';

const DEFAULT_URL = `ws://${location.hostname}:8765`;

export interface Dashboard {
  connected: boolean;
  opModes: OpModeInfo[];
  status: OpModeStatus;
  /**
   * The newest `telemetry.update()` snapshot, or null before the first one. The Driver Station
   * shows only the latest composition, so each frame replaces its predecessor.
   */
  telemetry: TelemetryFrame | null;
  devices: DeviceState[];
  error: string | null;
  /** Layout files the server has on disk, and the directory it keeps them in. */
  layouts: string[];
  layoutDirectory: string | null;
  /** The last layout the server sent back, for the scene to adopt. */
  loadedLayout: LayoutRecord | null;
  /** Where the last save landed, so the UI can say what to commit. */
  savedLayout: { name: string; path: string } | null;
  /**
   * The newest pose the server sent, in the FTC field frame, or null when nothing is driving.
   *
   * <p>Committed at the throttle `messages.ts` sets with {@code POSE_COMMIT_INTERVAL_MS}, so it is
   * right for readouts and one tick behind for animation; anything that draws the robot should take
   * {@link subscribePose}.</p>
   */
  pose: SimPose | null;
  /**
   * Every pose, as it lands, outside React. Returns its own unsubscribe. Meant for a render loop:
   * a listener that writes a transform sees all 50 Hz without a single component re-rendering.
   */
  subscribePose: (listener: (pose: SimPose) => void) => () => void;
  /** The robot and field the server is simulating, or null before the first `sim/config`. */
  simConfig: SimConfig | null;
  /**
   * The tags and game elements the server has placed on that field, or null before the first
   * `sim/scene`. Sent again whenever the scene changes.
   */
  simScene: ScenePayload | null;
  /**
   * Where the moving balls are, sampled in a render loop rather than subscribed to.
   *
   * <p>A buffer rather than the newest frame, because bodies arrive on the server's 50 Hz control
   * cycle and the display draws faster than that; see {@code scene/bodies.ts}. Nothing outside the
   * 3D view has any use for it: no readout shows a ball's coordinates.</p>
   */
  bodies: BodyBuffer;
  /**
   * Where the camera view is served, or null when this session has no camera.
   *
   * Sent once on connect. The panel points an `<img>` at it; no frame ever crosses this socket.
   */
  cameraStream: CameraStreamInfo | null;
  /** Clock and alliance, as the server last reported them. */
  simStatus: SimStatus;
  init: (className: string) => void;
  start: () => void;
  stop: () => void;
  /** Writes one gamepad slot on the robot: 1 or 2, matching {@code gamepad1} / {@code gamepad2}. */
  sendGamepad: (state: GamepadState, which?: 1 | 2) => void;
  overrideBehavior: (device: string, type: string, value: number) => void;
  resetBehavior: (device: string) => void;
  saveLayout: (name: string, layout: unknown) => void;
  loadLayout: (name: string) => void;
  deleteLayout: (name: string) => void;
  /** Sets the rate simulated time runs at, and whether it runs at all. */
  setSimTime: (multiplier: number, paused: boolean) => void;
  /** Advances exactly this many ticks while paused; ignored while running. */
  stepSim: (ticks: number) => void;
  /** Teleports the robot to a field pose, in metres and degrees. */
  placeRobot: (x: number, y: number, headingDegrees: number) => void;
  setAlliance: (alliance: Alliance) => void;
}

export function useDashboard(url: string = DEFAULT_URL): Dashboard {
  const socketRef = useRef<WebSocket | null>(null);
  const [connected, setConnected] = useState(false);
  const [opModes, setOpModes] = useState<OpModeInfo[]>([]);
  const [status, setStatus] = useState<OpModeStatus>({
    opMode: null,
    state: 'STOPPED',
    failure: null,
  });
  const [telemetry, setTelemetry] = useState<TelemetryFrame | null>(null);
  const [devices, setDevices] = useState<DeviceState[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [layouts, setLayouts] = useState<string[]>([]);
  const [layoutDirectory, setLayoutDirectory] = useState<string | null>(null);
  const [loadedLayout, setLoadedLayout] = useState<LayoutRecord | null>(null);
  const [savedLayout, setSavedLayout] = useState<{ name: string; path: string } | null>(null);
  const [simConfig, setSimConfig] = useState<SimConfig | null>(null);
  const [simScene, setSimScene] = useState<ScenePayload | null>(null);
  const [cameraStream, setCameraStream] = useState<CameraStreamInfo | null>(null);
  const [simStatus, setSimStatus] = useState<SimStatus>(DEFAULT_SIM_STATUS);
  const [pose, setPose] = useState<SimPose | null>(null);
  const poseRef = useRef<SimPose | null>(null);
  const poseListeners = useRef(new Set<(pose: SimPose) => void>());
  // One per hook, not per connection: the 3D view holds on to it across a reconnect, and a fresh
  // buffer would answer with an empty list until the field next moved.
  const bodyBuffer = useRef(createBodyBuffer()).current;

  // Only when the server goes away: the robot itself outlives every OpMode, so a pose keeps
  // arriving between runs. With nobody on the other end there is no robot to draw at all, and the
  // last pose would claim one is still sitting there.
  const clearPose = useCallback(() => {
    poseRef.current = null;
    setPose(null);
  }, []);

  useEffect(() => {
    let closed = false;
    let socket: WebSocket;
    let retry: ReturnType<typeof setTimeout>;

    // Where every frame lands. The setters are stable for the hook's life, so the only state here
    // is the pose throttle's, and that belongs to the connection it is rationing: a reconnect
    // starts the interval over, which is what the freshly-cleared pose wants anyway.
    const sinks: MessageSinks = {
      setError,
      setOpModes,
      setStatus,
      setTelemetry,
      setDevices,
      setLayouts,
      setLayoutDirectory,
      setLoadedLayout,
      setSavedLayout,
      setSimConfig,
      // A scene is the server saying the field has been rearranged — INIT rebuilds the world and
      // puts every ball back where the scenario placed it. The scene carries those positions, so
      // every body frame older than it is now a lie about where things are.
      setSimScene: (scene) => {
        bodyBuffer.reset();
        setSimScene(scene);
      },
      setCameraStream,
      setSimStatus,
      pose: createThrottledPoseSink({
        // Un-throttled on purpose: this is the 50 Hz path the 3D view reads, and it never touches
        // React.
        publish: (next) => {
          poseRef.current = next;
          for (const listener of poseListeners.current) listener(next);
        },
        commit: setPose,
        now: () => performance.now(),
      }),
      // Straight into the buffer the 3D view samples in its render loop. No React state and no
      // listener fan-out: the balls have exactly one consumer, and it reads rather than listens.
      bodies: (frame) => bodyBuffer.accept(frame),
    };

    const connect = () => {
      socket = new WebSocket(url);
      socketRef.current = socket;

      socket.onopen = () => {
        setConnected(true);
        // The OpMode list arrives unasked; saved layouts have to be asked for.
        socket.send(JSON.stringify({ namespace: 'layout', type: 'list', payload: {} }));
      };
      socket.onclose = () => {
        setConnected(false);
        clearPose();
        // The server outlives page reloads and vice versa; keep trying rather than dead-ending.
        if (!closed) retry = setTimeout(connect, 1000);
      };
      socket.onmessage = (event) => {
        // A frame that is not JSON, or not an envelope at all, is dropped rather than thrown: an
        // exception out of onmessage is an unhandled rejection mid-match, and the one message is
        // never worth the session.
        const envelope = parseEnvelope(event.data as string);
        if (envelope) applyMessage(envelope, sinks);
      };
    };

    connect();
    return () => {
      closed = true;
      clearTimeout(retry);
      socket.close();
    };
  }, [url, clearPose]);

  const send = useCallback((namespace: string, type: string, payload: unknown) => {
    const socket = socketRef.current;
    if (socket?.readyState === WebSocket.OPEN) {
      socket.send(JSON.stringify({ namespace, type, payload }));
    }
  }, []);

  const init = useCallback(
    (className: string) => {
      setTelemetry(null);
      setError(null);
      send('opmode', 'init', { className });
    },
    [send],
  );

  return {
    connected,
    opModes,
    status,
    telemetry,
    devices,
    error,
    bodies: bodyBuffer,
    init,
    start: useCallback(() => send('opmode', 'start', {}), [send]),
    stop: useCallback(() => send('opmode', 'stop', {}), [send]),
    sendGamepad: useCallback(
      (state: GamepadState, which: 1 | 2 = 1) => send('gamepad', 'state', { gamepad: which, ...state }),
      [send],
    ),
    overrideBehavior: useCallback(
      (device: string, type: string, value: number) => {
        // Named rather than inlined so the shape the server parses has a name on this side too:
        // Java reads this object as a BehaviorSpec, and it used to exist here as two anonymous
        // keys that nothing connected to it.
        const behavior: BehaviorSpec = { type, value };
        send('device', 'override', { device, behavior });
      },
      [send],
    ),
    resetBehavior: useCallback((device: string) => send('device', 'reset', { device }), [send]),
    layouts,
    layoutDirectory,
    loadedLayout,
    savedLayout,
    saveLayout: useCallback(
      (name: string, layout: unknown) => send('layout', 'save', { name, layout }),
      [send],
    ),
    loadLayout: useCallback((name: string) => send('layout', 'load', { name }), [send]),
    deleteLayout: useCallback((name: string) => send('layout', 'delete', { name }), [send]),
    pose,
    subscribePose: useCallback((listener: (pose: SimPose) => void) => {
      const listeners = poseListeners.current;
      listeners.add(listener);
      return () => {
        listeners.delete(listener);
      };
    }, []),
    simConfig,
    simScene,
    cameraStream,
    simStatus,
    setSimTime: useCallback(
      (multiplier: number, paused: boolean) => send('sim', 'time', { multiplier, paused }),
      [send],
    ),
    stepSim: useCallback((ticks: number) => send('sim', 'step', { ticks }), [send]),
    placeRobot: useCallback(
      (x: number, y: number, headingDegrees: number) => send('sim', 'pose', { x, y, headingDegrees }),
      [send],
    ),
    setAlliance: useCallback(
      (alliance: Alliance) => send('sim', 'alliance', { alliance }),
      [send],
    ),
  };
}
