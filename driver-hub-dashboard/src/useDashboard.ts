import { useCallback, useEffect, useRef, useState } from 'react';
import type {
  DeviceState,
  Envelope,
  GamepadState,
  OpModeInfo,
  OpModeStatus,
  TelemetryFrame,
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
  init: (className: string) => void;
  start: () => void;
  stop: () => void;
  sendGamepad: (state: GamepadState) => void;
  overrideBehavior: (device: string, type: string, value: number) => void;
  resetBehavior: (device: string) => void;
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

  useEffect(() => {
    let closed = false;
    let socket: WebSocket;
    let retry: ReturnType<typeof setTimeout>;

    const connect = () => {
      socket = new WebSocket(url);
      socketRef.current = socket;

      socket.onopen = () => setConnected(true);
      socket.onclose = () => {
        setConnected(false);
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
          case 'opmode/status':
            setStatus(payload as OpModeStatus);
            break;
          case 'telemetry/frame':
            setTelemetry(payload as TelemetryFrame);
            break;
          case 'device/state':
            setDevices((payload as { devices: DeviceState[] }).devices);
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
  }, [url]);

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
      (state: GamepadState) => send('gamepad', 'state', { gamepad: 1, ...state }),
      [send],
    ),
    overrideBehavior: useCallback(
      (device: string, type: string, value: number) =>
        send('device', 'override', { device, behavior: { type, value } }),
      [send],
    ),
    resetBehavior: useCallback((device: string) => send('device', 'reset', { device }), [send]),
  };
}
