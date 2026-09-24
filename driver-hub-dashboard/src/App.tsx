import { memo, useEffect, useRef, useState } from 'react';
import {
  BEHAVIORS,
  type Alliance,
  type DeviceState,
  type GamepadState,
  type OpModeState,
  type SimScenarios,
  type SimScore,
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
import { useUnsavedGuard } from './useUnsavedGuard';

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

  // A camera aimed by hand and not yet saved exists only in this session. Reloading the page to
  // see whether the stream looks better is exactly the reflex that would throw it away.
  useUnsavedGuard(dashboard.cameraMount?.unsaved ?? false);

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
        <OpModeControls
          state={dashboard.status.state}
          selected={selected}
          onInit={() => dashboard.init(selected)}
          onStart={dashboard.start}
          onStop={dashboard.stop}
        />
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
        <SimScoreReadout score={dashboard.simScore} />
        <ScenarioPicker scenarios={dashboard.simScenarios} onChoose={dashboard.loadScenario} />
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
      {view === 'camera' && <CameraView stream={dashboard.cameraStream} pose={dashboard.pose} />}
    </div>
  );
}

/**
 * The OpMode's own controls: one button, then two.
 *
 * <p>INIT and START are one slot because they are one decision. An OpMode that has been
 * initialised cannot be initialised again and one that has not cannot be started, so the pair was
 * never two choices — it was one live button beside one greyed one, and which was which was the
 * only thing the strip had to say. The button says it instead: it reads {@code init} when there is
 * nothing running and {@code start} once there is something waiting for the buzzer, which is the
 * order the Driver Station puts a driver through.</p>
 *
 * <p>STOP is absent, not disabled, until there is something to stop. A greyed control is a claim
 * that an action exists here and cannot be taken now; a stop with no OpMode is not an action at
 * all. The cost is that the strip's width changes on INIT, which is the one moment the operator is
 * looking at these buttons anyway.</p>
 *
 * <p>RUNNING keeps the slot filled with a spent {@code start} rather than dropping to a lone stop.
 * START is the button a driver's hand is already on when the match begins, and a layout that
 * shuffles under it at that instant would move STOP to where START just was.</p>
 */
export function OpModeControls({
  state,
  selected,
  onInit,
  onStart,
  onStop,
}: {
  state: OpModeState;
  selected: string;
  onInit: () => void;
  onStart: () => void;
  onStop: () => void;
}) {
  if (state === 'STOPPED') {
    return (
      <button
        style={button}
        onClick={onInit}
        disabled={!selected}
        title="Builds the robot and runs the OpMode's init, as the Driver Station's INIT does."
      >
        init
      </button>
    );
  }
  return (
    <>
      <button
        style={button}
        onClick={onStart}
        disabled={state === 'RUNNING'}
        title="Starts the initialised OpMode."
      >
        start
      </button>
      <button style={button} onClick={onStop} title="Stops the OpMode and rebuilds the field.">
        stop
      </button>
    </>
  );
}

/**
 * The live CELL score, in the strip every view already shows.
 *
 * <p>Null renders nothing at all, which is the point of taking a nullable prop rather than being
 * guarded at the call site: a session whose robot has no scene-backed HIVE never receives a
 * {@code sim/score}, and a disconnect throws the last one away, so "RED 0 – BLUE 0" there would be
 * a claim that the CELLs are empty rather than an admission that nobody has said. An empty strip
 * is the difference between a zero and a silence, and only one of those is knowledge.</p>
 *
 * <p>The per-CELL breakdown goes in the tooltip rather than the bar. Mid-match the driver wants
 * two numbers at a glance; "which CELL is that 6 sitting in" is a question asked between matches,
 * and a {@code title} answers it for no pixels. A panel for it was the alternative and would cost
 * the 3D view height in every session, scoring or not.</p>
 *
 * <p>TIPs are the exception, and they earn their pixels: the totals include 20 for each one, and a
 * tip empties the CELL that earned it, so the moment a driver most wants to read this strip is the
 * moment the totals alone are least legible — 12 points of POLLEN becoming a 20-point TIP is a
 * total that rose by 8 and a CELL that went to zero. They appear only once one has happened, since
 * a permanent "0 TIPS" is noise on the field's most common state.</p>
 */
export function SimScoreReadout({ score }: { score: SimScore | null }) {
  if (!score) return null;
  // Only upward-facing CELLs are on the wire, so this names exactly the CELLs that can score right
  // now; a HIVE turned the other way simply drops out of the list rather than appearing as a zero.
  const breakdown = score.cells
    .map((cell) => `${cell.cell}: ${cell.holding} holding, ${cell.points} points`)
    .join('\n');
  const redTips = score.redTips ?? 0;
  const blueTips = score.blueTips ?? 0;
  const tips = redTips + blueTips > 0;
  return (
    <span
      style={scoreReadout}
      title={
        'What the HIVE is holding right now: 2 points for every POLLEN or NECTAR left in an ' +
        'upward-facing CELL at the end of the match, plus 20 for every HIVE TIP. Live — this is ' +
        'what would score if the match ended now.' +
        (tips ? `\n\nTIPS: red ${redTips}, blue ${blueTips}` : '') +
        (breakdown ? `\n\n${breakdown}` : '')
      }
    >
      <span style={controlLabel}>CELLS</span>
      <span style={redPoints}>
        RED {score.redPoints}
        {redTips > 0 ? ` (${redTips} TIP${redTips > 1 ? 'S' : ''})` : ''}
      </span>
      <span style={controlLabel}>–</span>
      <span style={bluePoints}>
        BLUE {score.bluePoints}
        {blueTips > 0 ? ` (${blueTips} TIP${blueTips > 1 ? 'S' : ''})` : ''}
      </span>
    </span>
  );
}

/**
 * The robot's own field, as an option value.
 *
 * <p>A position rather than a name, and that is the whole point of it: every scenario option
 * carries its index in the list instead of its name, so the values this select can produce are
 * decimal positions and no filename can enter that space. A name-shaped sentinel is the trap — the
 * empty string, {@code none}, {@code (robot)} — because a scenario is a file someone puts on disk,
 * and the day somebody saves {@code none.json} the picker offers two entries that mean different
 * things and send the same value. -1 is not a position any list has.</p>
 */
const OWN_FIELD = '-1';

/**
 * Which staged field the simulation is running, and the menu to change it.
 *
 * <p>Null renders nothing, for {@link SimScoreReadout}'s reason: a session with no server, and one
 * whose robot simulates no field at all, both have nothing to pick from, and an empty select there
 * would be a control that does nothing rather than an absence.</p>
 *
 * <p>The active scenario is read off the server's frame rather than kept here. The server owns
 * which field is loaded — it also loads one from the command line, and it rebuilds the world on
 * INIT — so a local copy would be a second answer that disagrees the moment anything but this
 * select changes it.</p>
 */
export function ScenarioPicker({
  scenarios,
  onChoose,
}: {
  scenarios: SimScenarios | null;
  onChoose: (name: string | null) => void;
}) {
  if (!scenarios) return null;
  const { active, directory } = scenarios;
  // The loaded one first if the server no longer lists it, which happens when its file is renamed
  // or deleted under a running session. Without this, indexOf answers -1 and the select lands on
  // the own-field entry: the picker would claim nothing is staged while a field full of balls is
  // on the table. Showing the name the server reports is the honest reading, and choosing it again
  // is a request the server will refuse loudly rather than a silent no-op.
  const available =
    active !== null && !scenarios.scenarios.includes(active)
      ? [active, ...scenarios.scenarios]
      : scenarios.scenarios;
  return (
    <select
      value={active === null ? OWN_FIELD : String(available.indexOf(active))}
      onChange={(event) => {
        const index = Number(event.target.value);
        onChoose(index < 0 ? null : available[index]);
      }}
      style={smallSelect}
      title={
        'What is staged on the field: which way each HIVE is tipped and where the balls lie. ' +
        'Picking one restages the field, and the scene and the CELL score follow from the server. ' +
        '"the robot\'s own field" is the official BioBuzz field with nothing placed on it, which ' +
        `is what a session started with no scenario shows.\n\nScenario files live in ${directory}`
      }
    >
      <option value={OWN_FIELD}>the robot&apos;s own field</option>
      {available.map((name, index) => (
        <option key={name} value={index}>
          {name}
        </option>
      ))}
    </select>
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

// Hoisted beside the bar it sits in, like every other style here, rather than inlined in the JSX:
// the strip re-renders on every committed pose, twenty times a second, and an object literal in
// the tree is a new style prop each time.
const scoreReadout: React.CSSProperties = { display: 'flex', alignItems: 'center', gap: 6 };

// The alliance chips' own colours, so a total reads as red or blue at the same glance the chip
// does. Borrowed rather than respelled: two definitions of "red" drift, and the one that drifts is
// always the copy.
const redPoints: React.CSSProperties = { color: redChip.color };
const bluePoints: React.CSSProperties = { color: blueChip.color };

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
