import { memo, useEffect, useRef, useState } from 'react';
import {
  BEHAVIORS,
  type Alliance,
  type DeviceState,
  type GamepadState,
  type TelemetryFrame,
} from './protocol';
import { CameraView } from './camera/CameraView';
import { View3D } from './scene/View3D';
import { SIM_MULTIPLIERS, useSettings } from './settings';
import {
  activeChip,
  blueChip,
  button,
  chip,
  controlLabel,
  railHeading,
  redChip,
  selectStyle,
  smallSelect,
} from './ui';
import { useDashboard } from './useDashboard';
import { KEYBOARD_HELP, KEYBOARD_OFF, useKeyboardGamepad } from './useKeyboardGamepad';
import { describePads, useHardwareGamepads } from './useHardwareGamepads';

type View = 'scene' | 'console' | 'camera';

// Three ways to watch the same session. "scene" draws the robot in 3D — devices where the team says
// they sit on the chassis, spinning at the rate the encoders report. "console" is telemetry-first:
// a dark monospace panel with OpMode controls in a thin top bar and devices in a side rail.
// "camera" is what the robot's own camera sees, which is the only one of the three that shows the
// world as the vision code receives it.
export function App() {
  const dashboard = useDashboard();
  const { settings, update: updateSettings } = useSettings();
  // Real controllers win: while one is attached to gamepad1 the keyboard stand-in stands down,
  // rather than the two of them writing the same slot 20 times a second and cancelling out.
  const hardware = useHardwareGamepads(dashboard.sendGamepad);
  const padDrivingGamepad1 = hardware.pads.some((pad) => pad.slot === 1);
  const keyboard = useKeyboardGamepad(
    dashboard.sendGamepad,
    settings.keyboardGamepad && !padDrivingGamepad1,
  );
  const gamepad = padDrivingGamepad1 ? hardware.gamepad1 : keyboard;
  const inputCaption =
    describePads(hardware.pads) ?? (settings.keyboardGamepad ? KEYBOARD_HELP : KEYBOARD_OFF);
  const [selected, setSelected] = useState('');
  const [view, setView] = useState<View>('scene');

  useEffect(() => {
    if (!selected && dashboard.opModes.length > 0) {
      setSelected(dashboard.opModes[0].className);
    }
  }, [dashboard.opModes, selected]);

  // The server starts each session at its own defaults, so the stored preferences are pushed once
  // the socket is up — otherwise a reload would show blue in the strip while the robot is still
  // zeroed for red. Read through a ref so this fires on connect and not on every preference edit;
  // the edits push themselves.
  const prefs = useRef(settings);
  prefs.current = settings;
  const { connected, setAlliance, setSimTime, simStatus } = dashboard;
  useEffect(() => {
    if (!connected) return;
    setAlliance(prefs.current.alliance);
    setSimTime(prefs.current.simMultiplier, false);
  }, [connected, setAlliance, setSimTime]);

  // Alliance and speed are the operator's preference, so the strip shows the stored one and pushes
  // it; `paused` belongs to the server, which can change it without being asked — a step ends where
  // the tick budget runs out, and a stop leaves nothing to run.
  const chooseAlliance = (alliance: Alliance) => {
    updateSettings({ alliance });
    setAlliance(alliance);
  };
  const chooseSpeed = (multiplier: number) => {
    updateSettings({ simMultiplier: multiplier });
    setSimTime(multiplier, simStatus.paused);
  };

  const running = dashboard.status.state === 'RUNNING';
  const statusColour = running ? '#7CFC00' : dashboard.status.state === 'INIT' ? '#d8d84a' : '#888';

  return (
    <div style={shell}>
      <div style={topBar}>
        {/* Not "Driver Hub": that is REV's hardware, and this is the dashboard. */}
        <strong style={{ color: '#7CFC00' }}>Hub Dashboard</strong>
        {(['scene', 'console', 'camera'] as const).map((candidate) => (
          <button
            key={candidate}
            style={view === candidate ? activeChip : chip}
            onClick={() => setView(candidate)}
          >
            {candidate}
          </button>
        ))}
        <select
          value={selected}
          onChange={(event) => setSelected(event.target.value)}
          style={selectStyle}
        >
          {dashboard.opModes.map((opMode) => (
            <option key={opMode.className} value={opMode.className}>
              {opMode.name} ({opMode.flavor})
            </option>
          ))}
        </select>
        <button style={button} onClick={() => dashboard.init(selected)} disabled={!selected}>
          init
        </button>
        <button style={button} onClick={dashboard.start} disabled={dashboard.status.state !== 'INIT'}>
          start
        </button>
        <button style={button} onClick={dashboard.stop} disabled={dashboard.status.state === 'STOPPED'}>
          stop
        </button>
        <label
          style={{
            marginLeft: 'auto',
            color: padDrivingGamepad1 ? '#5f7a5f' : '#9fd89f',
            cursor: 'pointer',
          }}
          title={
            padDrivingGamepad1
              ? 'A real controller is driving gamepad1, so the keyboard stands down until it is unplugged.'
              : `Lets ${KEYBOARD_HELP} stand in for gamepad1. While it is on, this page grabs those keys.`
          }
        >
          <input
            type="checkbox"
            checked={settings.keyboardGamepad}
            onChange={(event) => updateSettings({ keyboardGamepad: event.target.checked })}
          />
          emulate joysticks with keyboard
        </label>
        <span style={{ marginLeft: 16, color: statusColour }}>
          {dashboard.connected ? `status: ${dashboard.status.state.toLowerCase()}` : 'disconnected'}
        </span>
      </div>

      <div style={simBar}>
        <span style={controlLabel}>SIM</span>
        {(['red', 'blue'] as const).map((alliance) => (
          <button
            key={alliance}
            style={
              settings.alliance === alliance ? (alliance === 'red' ? redChip : blueChip) : chip
            }
            onClick={() => chooseAlliance(alliance)}
            title={
              'Which driver station you are standing behind. It sets the zero of the reported ' +
              'heading: the robot reads 0° facing away from your own station.'
            }
          >
            {alliance}
          </button>
        ))}
        <button
          style={button}
          onClick={() => setSimTime(settings.simMultiplier, !simStatus.paused)}
          title="Stops simulated time. The socket stays up and the OpMode keeps its state."
        >
          {simStatus.paused ? 'resume' : 'pause'}
        </button>
        <select
          value={settings.simMultiplier}
          onChange={(event) => chooseSpeed(Number(event.target.value))}
          style={smallSelect}
          title={
            'How fast simulated time runs against the wall clock. Above 1x only autonomous is ' +
            "meaningful: gamepad input still arrives in real time, so at 4x the driver's stick is " +
            'sampled a quarter as often per simulated second.'
          }
        >
          {SIM_MULTIPLIERS.map((rate) => (
            <option key={rate} value={rate}>
              {rate}x
            </option>
          ))}
        </select>
        <button
          style={button}
          onClick={() => dashboard.stepSim(1)}
          disabled={!simStatus.paused}
          title="Advances one tick. Only while paused — running time is already advancing."
        >
          step
        </button>
        {dashboard.simConfig && (
          <span style={{ color: '#5f7a5f' }}>
            {dashboard.simConfig.robot.name} on a{' '}
            {dashboard.simConfig.field.sizeMetres.toFixed(2)} m field
          </span>
        )}
      </div>

      {(dashboard.error ?? dashboard.status.failure) && (
        <div style={errorBar}>{dashboard.error ?? dashboard.status.failure}</div>
      )}

      {view === 'scene' && (
        <View3D
          dashboard={dashboard}
          gamepad={gamepad}
          caption={inputCaption}
          pose={dashboard.pose}
          simConfig={dashboard.simConfig}
          simStatus={simStatus}
          placeRobot={dashboard.placeRobot}
        />
      )}
      {view === 'console' && (
        <ConsoleView
          telemetry={dashboard.telemetry}
          devices={dashboard.devices}
          gamepad={gamepad}
          caption={inputCaption}
          onOverride={dashboard.overrideBehavior}
          onReset={dashboard.resetBehavior}
        />
      )}
      {view === 'camera' && <CameraView stream={dashboard.cameraStream} />}
    </div>
  );
}

/**
 * The console tab, held still against the pose stream.
 *
 * <p>Pose lands 50 times a second, and none of it changes a telemetry line or a device row. Without
 * the memo every one of those ticks would rebuild the log the driver is reading and every row in
 * the rail; with it, this tab redraws when its own data does.</p>
 */
const ConsoleView = memo(function ConsoleView({
  telemetry,
  devices,
  gamepad,
  caption,
  onOverride,
  onReset,
}: {
  telemetry: TelemetryFrame | null;
  devices: DeviceState[];
  gamepad: GamepadState;
  caption: string;
  onOverride: (device: string, type: string, value: number) => void;
  onReset: (device: string) => void;
}) {
  return (
    <div style={{ flex: 1, display: 'flex', minHeight: 0 }}>
      <div style={log}>
        {telemetry ? (
          <>
            <div style={{ color: '#555' }}>
              {new Date(telemetry.timestamp).toLocaleTimeString()}
            </div>
            {telemetry.lines.map((line, lineIndex) => (
              <div key={lineIndex}>{line}</div>
            ))}
          </>
        ) : (
          <div style={{ color: '#555' }}>▌ waiting for telemetry.update()...</div>
        )}
      </div>

      <div style={rail}>
        <div style={railHeading}>devices</div>
        {devices.map((device) => (
          <DeviceRow key={device.name} device={device} onOverride={onOverride} onReset={onReset} />
        ))}
        {devices.length === 0 && <div style={{ color: '#555' }}>init an OpMode</div>}

        <div style={railHeading}>gamepad1</div>
        <div>
          LS ({gamepad.left_stick_x.toFixed(1)}, {gamepad.left_stick_y.toFixed(1)})
        </div>
        <div>
          RS ({gamepad.right_stick_x.toFixed(1)}, {gamepad.right_stick_y.toFixed(1)})
        </div>
        <div>LT {gamepad.left_trigger.toFixed(1)}</div>
        <div style={{ color: '#555', marginTop: 8, lineHeight: 1.5 }}>{caption}</div>
      </div>
    </div>
  );
});

function DeviceRow({
  device,
  onOverride,
  onReset,
}: {
  device: DeviceState;
  onOverride: (device: string, type: string, value: number) => void;
  onReset: (device: string) => void;
}) {
  const choices = BEHAVIORS[device.kind] ?? [];
  return (
    <div style={{ marginBottom: 10, color: '#9fd89f' }}>
      <div>
        {device.name}
        {device.kind === 'motor' && (
          <>
            : pwr {device.commandedPower.toFixed(2)} vel {device.velocityTicksPerSecond.toFixed(0)}
            <div style={{ color: '#6a9a6a' }}>pos {device.position}</div>
          </>
        )}
        {device.kind === 'servo' && `: cmd ${device.commandedPosition.toFixed(2)} horn ${device.hornPosition.toFixed(2)}`}
        {device.kind === 'imu' && `: yaw ${device.yawDegrees.toFixed(1)}°`}
      </div>
      <div style={{ display: 'flex', flexWrap: 'wrap', gap: 4, marginTop: 2 }}>
        {choices.map((choice) => (
          <button
            key={choice.type}
            style={device.behavior === choice.type ? activeChip : chip}
            onClick={() => onOverride(device.name, choice.type, choice.value ?? 0)}
          >
            {choice.label}
          </button>
        ))}
        {device.behavior !== 'default' && (
          <button style={chip} onClick={() => onReset(device.name)}>
            reset
          </button>
        )}
      </div>
    </div>
  );
}

const shell: React.CSSProperties = {
  display: 'flex',
  flexDirection: 'column',
  height: '100vh',
  background: '#0b0f14',
  color: '#d6f5d6',
  fontFamily: 'monospace',
};

const topBar: React.CSSProperties = {
  display: 'flex',
  alignItems: 'center',
  gap: 8,
  padding: '8px 12px',
  borderBottom: '1px solid #1e2a1e',
  fontSize: 13,
};

// A strip of its own rather than more things in the top bar: these drive the simulation, not the
// OpMode, and a driver reaching for pause should not have to pick it out of a row of eight.
const simBar: React.CSSProperties = {
  display: 'flex',
  alignItems: 'center',
  gap: 8,
  padding: '5px 12px',
  borderBottom: '1px solid #1e2a1e',
  fontSize: 12,
};

const errorBar: React.CSSProperties = {
  background: '#2a1313',
  color: '#ff9f9f',
  padding: '6px 12px',
  fontSize: 12,
};

const log: React.CSSProperties = {
  flex: 1,
  overflowY: 'auto',
  padding: 16,
  fontSize: 13,
  lineHeight: 1.5,
};

const rail: React.CSSProperties = {
  width: 260,
  borderLeft: '1px solid #1e2a1e',
  padding: 12,
  fontSize: 12,
  overflowY: 'auto',
};
