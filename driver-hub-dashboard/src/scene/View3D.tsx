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
  const cameraMount = dashboard.cameraMount;
  // The camera's placement is its mount, not a layout entry: excluded here so it never accrues a
  // cosmetic one, and so a saved layout file stops carrying numbers nothing reads.
  const api = useLayout(devices, cameraMount?.name ?? null);
  const [selected, setSelected] = useState<string | null>(null);
  const [options, setOptions] = useState<ViewOptions>(DEFAULT_VIEW_OPTIONS);
  const [loadFailure, setLoadFailure] = useState<string | null>(null);
  const [cameraExpanded, setCameraExpanded] = useState(false);
  // Collapsed, the field view gets the rail's 280 px — measured: the canvas goes from 1142 px wide
  // to 1422 on a 1440 px window. The rail is a placement editor, used in bursts and then not for
  // an hour, while the field is what a session is watched through, so the rail is the half that
  // folds. It is unmounted rather than hidden, which costs its own transient state on a collapse —
  // a half-typed layout name, a scroll position — and buys not having a column in the tree whose
  // controls are focusable and invisible. Nothing in there is unsaved work; the mount the star
  // marks lives on the server.
  const [toolsOpen, setToolsOpen] = useState(true);

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
          simScene={dashboard.simScene}
          bodies={dashboard.bodies}
          cameraMount={cameraMount}
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

      <button
        style={railHandle}
        onClick={() => setToolsOpen((open) => !open)}
        title={toolsOpen ? 'Collapse the tool rail' : 'Expand the tool rail'}
        aria-label={toolsOpen ? 'Collapse the tool rail' : 'Expand the tool rail'}
        aria-expanded={toolsOpen}
      >
        <span aria-hidden>{toolsOpen ? '›' : '‹'}</span>
        {/* Named only when it is shut, because a folded rail is otherwise an 18 px stripe nobody
            would read as "the placement editor is in here". */}
        {!toolsOpen && <span style={railHandleLabel}>tools</span>}
      </button>

      {toolsOpen && (
        <Inspector
          devices={devices}
          selected={selected}
          api={api}
          options={options}
          alliance={simStatus.alliance}
          layoutFiles={dashboard.layouts}
          layoutDirectory={dashboard.layoutDirectory}
          savedLayout={dashboard.savedLayout}
          cameraMount={cameraMount}
          savedCameraMount={dashboard.savedCameraMount}
          onSelect={setSelected}
          onOptions={(patch) => setOptions((previous) => ({ ...previous, ...patch }))}
          onAlliance={dashboard.setAlliance}
          onOverride={dashboard.overrideBehavior}
          onResetBehavior={dashboard.resetBehavior}
          onSaveLayout={(name) => dashboard.saveLayout(name, api.toFile())}
          onLoadLayout={dashboard.loadLayout}
          onDeleteLayout={dashboard.deleteLayout}
          onCameraMount={dashboard.setCameraMount}
          onSaveCameraMount={dashboard.saveCameraMount}
          onRevertCameraMount={dashboard.revertCameraMount}
        />
      )}
    </div>
  );
}

/**
 * The fold: a column of its own rather than something floating over the scene, so the control that
 * uncovers the field never covers it, and it stays on the window's right edge in both states —
 * against the rail when open, against nothing when shut.
 */
const railHandle: React.CSSProperties = {
  flex: '0 0 auto',
  width: 18,
  display: 'flex',
  flexDirection: 'column',
  alignItems: 'center',
  gap: 8,
  padding: '8px 0',
  border: 'none',
  borderLeft: '1px solid #1e2a1e',
  background: '#0b1016',
  color: '#7CFC00',
  fontFamily: 'monospace',
  fontSize: 13,
  cursor: 'pointer',
};

const railHandleLabel: React.CSSProperties = {
  writingMode: 'vertical-rl',
  letterSpacing: 1,
  fontSize: 10,
  color: '#5f7a5f',
};

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

/**
 * Top-right, opposite the camera panel. Both sit above the horizon of a typical field view so the
 * near half of the tiles — the half a driver is steering across — stays uncovered.
 */
const gamepadOverlay: React.CSSProperties = {
  position: 'absolute',
  right: 12,
  top: 12,
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

/**
 * Centred at the top: the two corners up here are taken, and this is the one overlay that has to
 * be read before anything has been done, so it cannot hide behind the camera panel.
 */
const emptyOverlay: React.CSSProperties = {
  position: 'absolute',
  top: 16,
  left: '50%',
  transform: 'translateX(-50%)',
  maxWidth: '40%',
  textAlign: 'center',
  color: '#5f7a5f',
  fontSize: 12,
  pointerEvents: 'none',
};

/**
 * Loud on purpose: both of these are states a driver would otherwise misread as working. Bottom
 * right, which the gamepad panel vacated, so it is clear of both top panels and of the telemetry
 * in the opposite corner.
 */
const warningOverlay: React.CSSProperties = {
  position: 'absolute',
  bottom: 12,
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
