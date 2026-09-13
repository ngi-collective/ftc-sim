import { useEffect, useState } from 'react';
import { BEHAVIORS, type DeviceState } from './protocol';
import { useDashboard } from './useDashboard';
import { KEYBOARD_HELP, useKeyboardGamepad } from './useKeyboardGamepad';

// Variant A, "Console": telemetry-first. A dark monospace panel holds the OpMode's current
// telemetry snapshot; OpMode controls are a thin top bar; devices and the gamepad live in a narrow
// side rail.
export function App() {
  const dashboard = useDashboard();
  const gamepad = useKeyboardGamepad(dashboard.sendGamepad);
  const [selected, setSelected] = useState('');

  useEffect(() => {
    if (!selected && dashboard.opModes.length > 0) {
      setSelected(dashboard.opModes[0].className);
    }
  }, [dashboard.opModes, selected]);

  const running = dashboard.status.state === 'RUNNING';
  const statusColour = running ? '#7CFC00' : dashboard.status.state === 'INIT' ? '#d8d84a' : '#888';

  return (
    <div style={shell}>
      <div style={topBar}>
        <strong style={{ color: '#7CFC00' }}>Driver Hub — Console</strong>
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
        <span style={{ marginLeft: 'auto', color: statusColour }}>
          {dashboard.connected ? `status: ${dashboard.status.state.toLowerCase()}` : 'disconnected'}
        </span>
      </div>

      {(dashboard.error ?? dashboard.status.failure) && (
        <div style={errorBar}>{dashboard.error ?? dashboard.status.failure}</div>
      )}

      <div style={{ flex: 1, display: 'flex', minHeight: 0 }}>
        <div style={log}>
          {dashboard.telemetry ? (
            <>
              <div style={{ color: '#555' }}>
                {new Date(dashboard.telemetry.timestamp).toLocaleTimeString()}
              </div>
              {dashboard.telemetry.lines.map((line, lineIndex) => (
                <div key={lineIndex}>{line}</div>
              ))}
            </>
          ) : (
            <div style={{ color: '#555' }}>▌ waiting for telemetry.update()...</div>
          )}
        </div>

        <div style={rail}>
          <div style={railHeading}>devices</div>
          {dashboard.devices.map((device) => (
            <DeviceRow
              key={device.name}
              device={device}
              onOverride={dashboard.overrideBehavior}
              onReset={dashboard.resetBehavior}
            />
          ))}
          {dashboard.devices.length === 0 && <div style={{ color: '#555' }}>init an OpMode</div>}

          <div style={railHeading}>gamepad1</div>
          <div>
            LS ({gamepad.left_stick_x.toFixed(1)}, {gamepad.left_stick_y.toFixed(1)})
          </div>
          <div>
            RS ({gamepad.right_stick_x.toFixed(1)}, {gamepad.right_stick_y.toFixed(1)})
          </div>
          <div>LT {gamepad.left_trigger.toFixed(1)}</div>
          <div style={{ color: '#555', marginTop: 8, lineHeight: 1.5 }}>{KEYBOARD_HELP}</div>
        </div>
      </div>
    </div>
  );
}

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

const railHeading: React.CSSProperties = { color: '#7CFC00', margin: '0 0 8px' };

const button: React.CSSProperties = {
  background: '#132313',
  color: '#7CFC00',
  border: '1px solid #2a4a2a',
  padding: '4px 10px',
  cursor: 'pointer',
  fontFamily: 'monospace',
};

const chip: React.CSSProperties = {
  background: '#111a11',
  color: '#7fbf7f',
  border: '1px solid #24391f',
  padding: '1px 6px',
  fontSize: 11,
  cursor: 'pointer',
  fontFamily: 'monospace',
};

const activeChip: React.CSSProperties = { ...chip, background: '#24391f', color: '#c8ffc8' };

const selectStyle: React.CSSProperties = {
  background: '#132313',
  color: '#d6f5d6',
  border: '1px solid #2a4a2a',
  padding: '4px 8px',
  fontFamily: 'monospace',
};
