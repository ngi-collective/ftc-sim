import { useCallback, useEffect, useRef, useState } from 'react';
import {
  DEFAULT_SIM_STATUS,
  parseLayoutList,
  parseLayoutRecord,
  parseSavedLayout,
  type Alliance,
  type CameraStream,
  type DeviceState,
  type Envelope,
  type GamepadState,
  type LayoutRecord,
  type OpModeInfo,
  type OpModeStatus,
  type SimConfig,
  type SimPose,
  type SimStatus,
  type TelemetryFrame,
} from './protocol';

/**
 * Pose arrives at 50 Hz, which is what the scene wants and far more than React does: every commit
 * re-renders the console log and the device rail along with the canvas. The fast path is therefore
 * a subscription fed straight off the socket, and {@link Dashboard.pose} is committed no more often
 * than this — the cadence a plugged-in controller already re-renders the page at, so a running
 * simulation costs the tree nothing it was not already paying.
 */
const POSE_COMMIT_INTERVAL_MS = 50;

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
   * <p>Committed at {@link POSE_COMMIT_INTERVAL_MS}, so it is right for readouts and one tick
   * behind for animation; anything that draws the robot should take {@link subscribePose}.</p>
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
   * Where the camera view is served, or null when this session has no camera.
   *
   * Sent once on connect. The panel points an `<img>` at it; no frame ever crosses this socket.
   */
  cameraStream: CameraStream | null;
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
  const [cameraStream, setCameraStream] = useState<CameraStream | null>(null);
  const [simStatus, setSimStatus] = useState<SimStatus>(DEFAULT_SIM_STATUS);
  const [pose, setPose] = useState<SimPose | null>(null);
  const poseRef = useRef<SimPose | null>(null);
  const poseListeners = useRef(new Set<(pose: SimPose) => void>());
  const lastPoseCommit = useRef(0);

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
        const envelope = JSON.parse(event.data as string) as Envelope;
        const payload = envelope.payload as never;

        if (envelope.type === 'error') {
          setError((payload as { message: string }).message);
          return;
        }
        switch (`${envelope.namespace}/${envelope.type}`) {
          case 'opmode/list':
            setOpModes((payload as { opModes: OpModeInfo[] }).opModes);
            break;
          case 'opmode/status': {
            const next = payload as OpModeStatus;
            setStatus(next);
            // No clearing on STOPPED: the robot is still there, and the next pose says where.
            break;
          }
          case 'telemetry/frame':
            setTelemetry(payload as TelemetryFrame);
            break;
          case 'device/state':
            setDevices((payload as { devices: DeviceState[] }).devices);
            break;
          case 'layout/list': {
            const list = parseLayoutList(envelope.payload);
            if (list) {
              setLayouts(list.layouts);
              setLayoutDirectory(list.directory);
            }
            break;
          }
          case 'layout/data': {
            const record = parseLayoutRecord(envelope.payload);
            if (record) setLoadedLayout(record);
            break;
          }
          case 'layout/saved': {
            const saved = parseSavedLayout(envelope.payload);
            if (saved) setSavedLayout(saved);
            break;
          }
          case 'sim/pose': {
            const next = payload as SimPose;
            poseRef.current = next;
            for (const listener of poseListeners.current) listener(next);
            const now = performance.now();
            if (now - lastPoseCommit.current >= POSE_COMMIT_INTERVAL_MS) {
              lastPoseCommit.current = now;
              setPose(next);
            }
            break;
          }
          case 'sim/config':
            setSimConfig(payload as SimConfig);
            break;
          case 'camera/stream':
            setCameraStream(payload as CameraStream);
            break;
          case 'sim/status':
            setSimStatus(payload as SimStatus);
            break;
        }
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
    init,
    start: useCallback(() => send('opmode', 'start', {}), [send]),
    stop: useCallback(() => send('opmode', 'stop', {}), [send]),
    sendGamepad: useCallback(
      (state: GamepadState, which: 1 | 2 = 1) => send('gamepad', 'state', { gamepad: which, ...state }),
      [send],
    ),
    overrideBehavior: useCallback(
      (device: string, type: string, value: number) =>
        send('device', 'override', { device, behavior: { type, value } }),
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
