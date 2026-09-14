import { useEffect, useState } from 'react';
import type { GamepadState, SimConfig, SimPose, SimStatus } from '../protocol';
import type { Dashboard } from '../useDashboard';
import { CameraPip } from '../camera/CameraPip';
import { GamepadView } from './GamepadView';
import { Inspector } from './Inspector';
import { parseLayoutFile, useLayout } from './layout';
import { DEFAULT_VIEW_OPTIONS, RobotScene, type ViewOptions } from './RobotScene';

/**
 * The 3D tab: a robot driving a field, with every simulated device drawn where the team says it
 * lives, plus gamepad1 as an object in its own right.
 *
 * <p>Placement state lives here rather than in the scene so the inspector and the canvas edit the
 * same layout, and so a re-mount of the canvas cannot lose it.</p>
 */
export function View3D({
  dashboard,
  gamepad,
  caption,
  pose,
  simConfig,
  simStatus,
  placeRobot,
}: {
  dashboard: Dashboard;
  gamepad: GamepadState;
  /** What is driving gamepad1 right now — a named controller, or the keyboard stand-in. */
  caption: string;
  /** The sim's last pose, throttled for text and tints; the scene takes the 50 Hz stream itself. */
  pose: SimPose | null;
  simConfig: SimConfig | null;
  simStatus: SimStatus;
  placeRobot: (x: number, y: number, headingDegrees: number) => void;
}) {
  const devices = dashboard.devices;
  const api = useLayout(devices);
  const [selected, setSelected] = useState<string | null>(null);
  const [options, setOptions] = useState<ViewOptions>(DEFAULT_VIEW_OPTIONS);
  const [loadFailure, setLoadFailure] = useState<string | null>(null);
  const [cameraExpanded, setCameraExpanded] = useState(false);

  const loaded = dashboard.loadedLayout;
  const applyFile = api.applyFile;
  useEffect(() => {
    if (!loaded) return;
    const file = parseLayoutFile(loaded.layout);
    setLoadFailure(file ? null : `${loaded.name} has no placements this version understands`);
    if (file) applyFile(file);
  }, [loaded, applyFile]);

  /**
   * Motors whose commanded power went over range. The SDK clips silently, which turns a broken
   * normalisation into a robot that merely drives wrong, so the motor is named out loud here.
   */
  const clipped = devices.filter(
    (device) => device.kind === 'motor' && device.clippedCommandCount > 0,
  );

  return (
    <div style={{ flex: 1, display: 'flex', minHeight: 0 }}>
      <div style={{ flex: 1, position: 'relative', minWidth: 0 }}>
        <RobotScene
          devices={devices}
          layout={api.layout}
          selected={selected}
          gamepad={gamepad}
          options={options}
          pose={pose}
          simConfig={simConfig}
          alliance={simStatus.alliance}
          subscribePose={dashboard.subscribePose}
          onSelect={setSelected}
          onMove={(name, position) => api.update(name, { position })}
          onPlaceRobot={placeRobot}
        />

        {(pose?.wallContact || clipped.length > 0) && (
          <div style={warningOverlay}>
            {pose?.wallContact && (
              <div>wall contact · the robot is pinned, the encoders keep counting</div>
            )}
            {clipped.map((device) => (
              <div key={device.name}>
                {device.name} power clipped ×{device.clippedCommandCount} · last asked{' '}
                {device.lastClippedCommand.toFixed(2)}
              </div>
            ))}
          </div>
        )}

        {devices.length === 0 && (
          <div style={emptyOverlay}>init an OpMode to populate the robot</div>
        )}

        {(loadFailure ?? dashboard.error) && (
          <div style={emptyOverlay}>{loadFailure ?? dashboard.error}</div>
        )}

        {dashboard.telemetry && (
          <div style={telemetryOverlay}>
            {dashboard.telemetry.lines.map((line, index) => (
              <div key={index}>{line}</div>
            ))}
          </div>
        )}

        <div style={gamepadOverlay}>
          <div style={{ flex: 1, minHeight: 0 }}>
            <GamepadView gamepad={gamepad} />
          </div>
          <div style={gamepadCaption}>{caption}</div>
        </div>

        <CameraPip
          stream={dashboard.cameraStream}
          pose={pose}
          expanded={cameraExpanded}
          onExpandedChange={setCameraExpanded}
        />
      </div>

      <Inspector
        devices={devices}
        selected={selected}
        api={api}
        options={options}
        alliance={simStatus.alliance}
        layoutFiles={dashboard.layouts}
        layoutDirectory={dashboard.layoutDirectory}
        savedLayout={dashboard.savedLayout}
        onSelect={setSelected}
        onOptions={(patch) => setOptions((previous) => ({ ...previous, ...patch }))}
        onAlliance={dashboard.setAlliance}
        onOverride={dashboard.overrideBehavior}
        onResetBehavior={dashboard.resetBehavior}
        onSaveLayout={(name) => dashboard.saveLayout(name, api.toFile())}
        onLoadLayout={dashboard.loadLayout}
        onDeleteLayout={dashboard.deleteLayout}
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

/** Loud on purpose: both of these are states a driver would otherwise misread as working. */
const warningOverlay: React.CSSProperties = {
  position: 'absolute',
  top: 12,
  right: 12,
  maxWidth: 320,
  padding: '6px 10px',
  border: '1px solid #6b3a12',
  background: 'rgba(28, 14, 6, 0.85)',
  color: '#ffb86b',
  fontSize: 12,
  lineHeight: 1.5,
  pointerEvents: 'none',
};
