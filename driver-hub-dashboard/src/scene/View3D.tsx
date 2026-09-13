import { useState } from 'react';
import type { DeviceState, GamepadState, TelemetryFrame } from '../protocol';
import { KEYBOARD_HELP } from '../useKeyboardGamepad';
import { GamepadView } from './GamepadView';
import { Inspector } from './Inspector';
import { useLayout } from './layout';
import { DEFAULT_VIEW_OPTIONS, RobotScene, type ViewOptions } from './RobotScene';

/**
 * The 3D tab: a robot you can walk around, with every simulated device drawn where the team says it
 * lives, plus gamepad1 as an object in its own right.
 *
 * <p>Placement state lives here rather than in the scene so the inspector and the canvas edit the
 * same layout, and so a re-mount of the canvas cannot lose it.</p>
 */
export function View3D({
  devices,
  gamepad,
  telemetry,
  onOverride,
  onResetBehavior,
}: {
  devices: DeviceState[];
  gamepad: GamepadState;
  telemetry: TelemetryFrame | null;
  onOverride: (device: string, type: string, value: number) => void;
  onResetBehavior: (device: string) => void;
}) {
  const api = useLayout(devices);
  const [selected, setSelected] = useState<string | null>(null);
  const [options, setOptions] = useState<ViewOptions>(DEFAULT_VIEW_OPTIONS);

  return (
    <div style={{ flex: 1, display: 'flex', minHeight: 0 }}>
      <div style={{ flex: 1, position: 'relative', minWidth: 0 }}>
        <RobotScene
          devices={devices}
          layout={api.layout}
          selected={selected}
          gamepad={gamepad}
          options={options}
          onSelect={setSelected}
          onMove={(name, position) => api.update(name, { position })}
        />

        {devices.length === 0 && (
          <div style={emptyOverlay}>init an OpMode to populate the robot</div>
        )}

        {telemetry && (
          <div style={telemetryOverlay}>
            {telemetry.lines.map((line, index) => (
              <div key={index}>{line}</div>
            ))}
          </div>
        )}

        <div style={gamepadOverlay}>
          <div style={{ flex: 1, minHeight: 0 }}>
            <GamepadView gamepad={gamepad} />
          </div>
          <div style={gamepadCaption}>{KEYBOARD_HELP}</div>
        </div>
      </div>

      <Inspector
        devices={devices}
        selected={selected}
        api={api}
        options={options}
        onSelect={setSelected}
        onOptions={(patch) => setOptions((previous) => ({ ...previous, ...patch }))}
        onOverride={onOverride}
        onResetBehavior={onResetBehavior}
      />
    </div>
  );
}

const telemetryOverlay: React.CSSProperties = {
  position: 'absolute',
  left: 12,
  bottom: 12,
  maxHeight: '38%',
  maxWidth: '46%',
  overflowY: 'auto',
  padding: '6px 10px',
  border: '1px solid #1e2a1e',
  background: 'rgba(8, 12, 16, 0.78)',
  color: '#9fd89f',
  fontSize: 12,
  lineHeight: 1.45,
  pointerEvents: 'auto',
};

const gamepadOverlay: React.CSSProperties = {
  position: 'absolute',
  right: 12,
  bottom: 12,
  width: 260,
  height: 196,
  display: 'flex',
  flexDirection: 'column',
  border: '1px solid #1e2a1e',
  background: 'rgba(8, 12, 16, 0.72)',
};

const gamepadCaption: React.CSSProperties = {
  padding: '3px 6px',
  borderTop: '1px solid #1e2a1e',
  color: '#5f7a5f',
  fontSize: 10,
  lineHeight: 1.35,
};

const emptyOverlay: React.CSSProperties = {
  position: 'absolute',
  top: 16,
  left: 16,
  color: '#5f7a5f',
  fontSize: 12,
  pointerEvents: 'none',
};
