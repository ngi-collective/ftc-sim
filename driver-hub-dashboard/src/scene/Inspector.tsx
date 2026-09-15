import { useState } from 'react';
import {
  BEHAVIORS,
  type Alliance,
  type CameraMount,
  type CameraMountPayload,
  type DeviceState,
} from '../protocol';
import { activeChip, button, chip, numberInput, railHeading, selectStyle } from '../ui';
import { INLINE_OUTPUT, MOUNTS, type DeviceLayout, type LayoutApi, type Mount } from './layout';
import type { ViewOptions } from './RobotScene';

/**
 * The rail beside the 3D view: pick a device, then say where it lives on the robot and which way it
 * faces. Nothing here changes the simulation — it changes how the simulation is drawn.
 */

/** Aim the shaft, in the words a driver would use looking at their own robot. */
const AIM_PRESETS: { label: string; rotation: [number, number, number] }[] = [
  { label: 'axle left', rotation: [-90, 0, 0] },
  { label: 'axle right', rotation: [90, 0, 0] },
  { label: 'shaft fwd', rotation: [180, 0, 0] },
  { label: 'shaft back', rotation: [0, 0, 0] },
  { label: 'shaft up', rotation: [0, -90, 0] },
  { label: 'shaft down', rotation: [0, 90, 0] },
];

const AXES: { index: 0 | 1 | 2; label: string }[] = [
  { index: 0, label: 'yaw' },
  { index: 1, label: 'pitch' },
  { index: 2, label: 'roll' },
];

const POSITION_AXES: { index: 0 | 1 | 2; label: string }[] = [
  { index: 0, label: 'x' },
  { index: 1, label: 'y' },
  { index: 2, label: 'z' },
];

/**
 * Where the driven part sits and which way it turns relative to the motor. "right angle" is the
 * bevel-or-chain case: the wheel turns about an axis the motor shaft does not.
 */
const OUTPUT_PRESETS: {
  label: string;
  outputRotation: [number, number, number];
  outputOffset: [number, number, number];
}[] = [
  { label: 'inline', outputRotation: [0, 0, 0], outputOffset: [...INLINE_OUTPUT] },
  { label: 'right angle ←', outputRotation: [-90, 0, 0], outputOffset: [-0.07, 0, 0.01] },
  { label: 'right angle →', outputRotation: [90, 0, 0], outputOffset: [0.07, 0, 0.01] },
  { label: 'right angle ↑', outputRotation: [0, -90, 0], outputOffset: [0, 0.07, 0.01] },
];

const RATIO_PRESETS: { label: string; ratio: number }[] = [
  { label: '1:1', ratio: 1 },
  { label: 'reversed', ratio: -1 },
  { label: '2:1 down', ratio: 0.5 },
  { label: '1:2 up', ratio: 2 },
];

function VectorFields({
  value,
  onChange,
}: {
  value: [number, number, number];
  onChange: (next: [number, number, number]) => void;
}) {
  return (
    <div style={{ display: 'flex', gap: 4, marginBottom: 8 }}>
      {POSITION_AXES.map(({ index, label }) => (
        <label key={label} style={{ color: '#6f8f6f' }}>
          {label}
          <input
            type="number"
            step={0.01}
            value={Number(value[index].toFixed(3))}
            style={{ ...numberInput, width: 58, marginLeft: 2 }}
            onChange={(event) => {
              const next: [number, number, number] = [...value];
              next[index] = Number(event.target.value);
              onChange(next);
            }}
          />
        </label>
      ))}
    </div>
  );
}

function AxisSliders({
  value,
  onChange,
}: {
  value: [number, number, number];
  onChange: (next: [number, number, number]) => void;
}) {
  return (
    <>
      {AXES.map(({ index, label }) => (
        <div key={label} style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
          <span style={{ width: 34, color: '#6f8f6f' }}>{label}</span>
          <input
            type="range"
            min={-180}
            max={180}
            step={1}
            value={value[index]}
            style={{ flex: 1, accentColor: '#7CFC00' }}
            onChange={(event) => {
              const next: [number, number, number] = [...value];
              next[index] = Number(event.target.value);
              onChange(next);
            }}
          />
          <span style={{ width: 34, textAlign: 'right' }}>{Math.round(value[index])}°</span>
        </div>
      ))}
    </>
  );
}

function DeviceEditor({
  device,
  layout,
  api,
  onOverride,
  onReset,
}: {
  device: DeviceState;
  layout: DeviceLayout;
  api: LayoutApi;
  onOverride: (device: string, type: string, value: number) => void;
  onReset: (device: string) => void;
}) {
  const behaviors = BEHAVIORS[device.kind] ?? [];

  return (
    <div style={{ borderTop: '1px solid #1e2a1e', paddingTop: 8, marginTop: 8 }}>
      <div style={{ color: '#c8ffc8', marginBottom: 6 }}>
        {device.name} <span style={{ color: '#5f7a5f' }}>{device.kind}</span>
      </div>

      <div style={{ color: '#6f8f6f', marginBottom: 2 }}>position (m)</div>
      <VectorFields
        value={layout.position}
        onChange={(position) => api.update(device.name, { position })}
      />

      <div style={{ color: '#6f8f6f', marginBottom: 2 }}>orientation (deg)</div>
      <AxisSliders
        value={layout.rotation}
        onChange={(rotation) => api.update(device.name, { rotation })}
      />

      <div style={{ display: 'flex', flexWrap: 'wrap', gap: 4, margin: '6px 0' }}>
        {AIM_PRESETS.map((preset) => (
          <button
            key={preset.label}
            style={
              preset.rotation.every((value, index) => value === layout.rotation[index])
                ? activeChip
                : chip
            }
            onClick={() => api.update(device.name, { rotation: preset.rotation })}
          >
            {preset.label}
          </button>
        ))}
      </div>

      <div style={{ display: 'flex', alignItems: 'center', gap: 6, marginBottom: 6 }}>
        <span style={{ color: '#6f8f6f' }}>drives</span>
        <select
          value={layout.mount}
          style={{ ...selectStyle, padding: '1px 4px', fontSize: 11 }}
          onChange={(event) => api.update(device.name, { mount: event.target.value as Mount })}
        >
          {MOUNTS.map((mount) => (
            <option key={mount} value={mount}>
              {mount}
            </option>
          ))}
        </select>
        <span style={{ color: '#6f8f6f' }}>ratio</span>
        <input
          type="number"
          step={0.25}
          value={layout.ratio}
          style={{ ...numberInput, width: 54 }}
          onChange={(event) => api.update(device.name, { ratio: Number(event.target.value) })}
        />
      </div>

      <div style={{ display: 'flex', flexWrap: 'wrap', gap: 4, marginBottom: 6 }}>
        {RATIO_PRESETS.map((preset) => (
          <button
            key={preset.label}
            style={layout.ratio === preset.ratio ? activeChip : chip}
            onClick={() => api.update(device.name, { ratio: preset.ratio })}
          >
            {preset.label}
          </button>
        ))}
      </div>

      {device.kind === 'motor' && (
        <>
          <div style={{ color: '#6f8f6f', marginBottom: 2 }}>driven part</div>
          <div style={{ display: 'flex', flexWrap: 'wrap', gap: 4, marginBottom: 4 }}>
            {OUTPUT_PRESETS.map((preset) => (
              <button
                key={preset.label}
                style={
                  preset.outputRotation.every(
                    (value, index) => value === layout.outputRotation[index],
                  )
                    ? activeChip
                    : chip
                }
                onClick={() =>
                  api.update(device.name, {
                    outputRotation: [...preset.outputRotation],
                    outputOffset: [...preset.outputOffset],
                  })
                }
              >
                {preset.label}
              </button>
            ))}
          </div>
          <VectorFields
            value={layout.outputOffset}
            onChange={(outputOffset) => api.update(device.name, { outputOffset })}
          />
          <AxisSliders
            value={layout.outputRotation}
            onChange={(outputRotation) => api.update(device.name, { outputRotation })}
          />
        </>
      )}

      <div style={{ display: 'flex', alignItems: 'center', gap: 6, marginBottom: 6 }}>
        <span style={{ color: '#6f8f6f' }}>scale</span>
        <input
          type="range"
          min={0.4}
          max={2.5}
          step={0.05}
          value={layout.scale}
          style={{ flex: 1, accentColor: '#7CFC00' }}
          onChange={(event) => api.update(device.name, { scale: Number(event.target.value) })}
        />
        <span style={{ width: 34, textAlign: 'right' }}>{layout.scale.toFixed(2)}x</span>
      </div>

      {device.kind === 'motor' && (
        <div style={{ display: 'flex', alignItems: 'center', gap: 6, marginBottom: 6 }}>
          <span style={{ color: '#6f8f6f' }}>ticks/rev</span>
          <input
            type="number"
            step={1}
            min={1}
            value={layout.ticksPerRev}
            style={numberInput}
            onChange={(event) =>
              api.update(device.name, { ticksPerRev: Math.max(1, Number(event.target.value)) })
            }
          />
        </div>
      )}

      <div style={{ display: 'flex', flexWrap: 'wrap', gap: 4, marginBottom: 6 }}>
        {behaviors.map((behavior) => (
          <button
            key={behavior.type}
            style={device.behavior === behavior.type ? activeChip : chip}
            onClick={() => onOverride(device.name, behavior.type, behavior.value ?? 0)}
          >
            {behavior.label}
          </button>
        ))}
        {device.behavior !== 'default' && (
          <button style={chip} onClick={() => onReset(device.name)}>
            reset behavior
          </button>
        )}
      </div>

      {api.customized(device.name) && (
        <button style={chip} onClick={() => api.reset(device.name)}>
          reset placement
        </button>
      )}
    </div>
  );
}

/** Where the camera is bolted, in the words someone holding the robot would use. */
const MOUNT_FIELDS: {
  key: keyof CameraMount;
  label: string;
  hint: string;
  low: number;
  high: number;
}[] = [
  // Generous for a 46 cm robot: a camera on a mast is ordinary, a camera two metres out is a typo.
  // Height starts at the floor, because a camera below it is not a viewpoint anyone wants to see
  // the field from.
  { key: 'forwardMetres', label: 'forward', hint: 'out the nose', low: -0.6, high: 0.6 },
  { key: 'leftMetres', label: 'left', hint: "to the robot's left", low: -0.6, high: 0.6 },
  { key: 'heightMetres', label: 'height', hint: 'above the floor', low: 0, high: 1.2 },
];

/**
 * A typed mount number, kept inside the range its control advertises.
 *
 * <p>{@code min} and {@code max} on a number input only bind its spinner: a typed 99 sails
 * straight through, and a field someone cleared to retype arrives as {@code NaN}, which would aim
 * the camera at nothing at all. Both come back as "leave the mount where it is".</p>
 */
function inRange(raw: string, low: number, high: number, unchanged: number): number {
  const value = Number(raw);
  return Number.isFinite(value) ? Math.min(high, Math.max(low, value)) : unchanged;
}

const MOUNT_ANGLES: { key: keyof CameraMount; label: string; hint: string; limit: number }[] = [
  { key: 'yawDegrees', label: 'yaw', hint: '+ swings left', limit: 180 },
  { key: 'pitchDegrees', label: 'pitch', hint: '+ looks up', limit: 90 },
  { key: 'rollDegrees', label: 'roll', hint: '+ leans the picture right', limit: 180 },
];

/**
 * The webcam's mount: the numbers that actually aim the camera the vision code reads, as opposed to
 * every other editor in this rail, which only says how the robot is drawn.
 *
 * <p>Each edit takes effect on the next rendered frame and nothing is written to disk, which is the
 * whole point — finding a good angle means watching the camera view while dragging, and an angle
 * that turns out to be wrong should cost nothing. Saving is a separate button, because a mount
 * committed to the robot's configuration file is a decision and not a byproduct of experimenting.
 * </p>
 *
 * <p>Every control reads the server's last {@code sim/camera} rather than a local copy: the
 * simulation owns the mount, so a slider always shows where the camera really is.</p>
 */
function CameraMountEditor({
  device,
  mount,
  savedPath,
  onMount,
  onSave,
  onRevert,
}: {
  device: DeviceState;
  mount: CameraMountPayload;
  /** Where the last save landed, so the rail can name the file worth committing. */
  savedPath: string | null;
  onMount: (mount: CameraMount) => void;
  onSave: () => void;
  onRevert: () => void;
}) {
  const aim = (patch: Partial<CameraMount>) =>
    onMount({
      forwardMetres: mount.forwardMetres,
      leftMetres: mount.leftMetres,
      heightMetres: mount.heightMetres,
      yawDegrees: mount.yawDegrees,
      pitchDegrees: mount.pitchDegrees,
      rollDegrees: mount.rollDegrees,
      ...patch,
    });

  return (
    <div style={{ borderTop: '1px solid #1e2a1e', paddingTop: 8, marginTop: 8 }}>
      <div style={{ color: '#c8ffc8', marginBottom: 6 }}>
        {device.name} <span style={{ color: '#5f7a5f' }}>camera mount</span>
      </div>
      <div style={{ color: '#5f7a5f', marginBottom: 6, lineHeight: 1.4 }}>
        aims the real camera as you drag. {mount.horizontalFovDegrees.toFixed(0)}° ×{' '}
        {mount.verticalFovDegrees.toFixed(0)}° lens, from the robot config.
      </div>

      <div style={{ color: '#6f8f6f', marginBottom: 2 }}>where it is bolted (m)</div>
      <div style={{ display: 'flex', gap: 4, marginBottom: 8 }}>
        {MOUNT_FIELDS.map((field) => (
          <label key={field.key} style={{ color: '#6f8f6f' }} title={`positive ${field.hint}`}>
            {field.label}
            <input
              type="number"
              step={0.005}
              min={field.low}
              max={field.high}
              value={Number(mount[field.key].toFixed(3))}
              style={{ ...numberInput, width: 58, marginLeft: 2 }}
              onChange={(event) =>
                aim({
                  [field.key]: inRange(
                    event.target.value,
                    field.low,
                    field.high,
                    mount[field.key],
                  ),
                })
              }
            />
          </label>
        ))}
      </div>

      <div style={{ color: '#6f8f6f', marginBottom: 2 }}>where it looks (deg)</div>
      {MOUNT_ANGLES.map((angle) => (
        <div key={angle.key} style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
          <span style={{ width: 34, color: '#6f8f6f' }} title={angle.hint}>
            {angle.label}
          </span>
          <input
            type="range"
            min={-angle.limit}
            max={angle.limit}
            step={1}
            value={mount[angle.key]}
            style={{ flex: 1, accentColor: '#7CFC00' }}
            onChange={(event) => aim({ [angle.key]: Number(event.target.value) })}
          />
          <span style={{ width: 34, textAlign: 'right' }}>{Math.round(mount[angle.key])}°</span>
        </div>
      ))}
      <div style={{ color: '#5f7a5f', margin: '2px 0 8px' }}>
        yaw + swings left · pitch + looks up · roll + leans the picture right
      </div>

      <div style={{ display: 'flex', alignItems: 'center', gap: 4 }}>
        <button style={chip} onClick={onSave} disabled={!mount.unsaved}>
          save to robot config
        </button>
        <button style={chip} onClick={onRevert} disabled={!mount.unsaved}>
          revert
        </button>
        {mount.unsaved && <span style={unsavedMark}>unsaved</span>}
      </div>

      {mount.unsaved ? (
        <div style={{ color: '#d8d84a', marginTop: 6, lineHeight: 1.4 }}>
          this angle lives in this session only — the robot config still says something else
        </div>
      ) : (
        savedPath && (
          <div style={{ color: '#5f7a5f', marginTop: 6, wordBreak: 'break-all' }}>
            wrote {savedPath} — commit it
          </div>
        )
      )}
    </div>
  );
}

/**
 * Layouts the server keeps as files. Saving writes one into the team's source tree, which is the
 * point: a robot's layout is worth committing next to the OpModes that drive it.
 */
function LayoutFiles({
  layouts,
  directory,
  savedLayout,
  onSave,
  onLoad,
  onDelete,
}: {
  layouts: string[];
  directory: string | null;
  savedLayout: { name: string; path: string } | null;
  onSave: (name: string) => void;
  onLoad: (name: string) => void;
  onDelete: (name: string) => void;
}) {
  const [name, setName] = useState('');
  const chosen = name.trim();

  return (
    <div style={{ borderTop: '1px solid #1e2a1e', paddingTop: 8, marginTop: 8 }}>
      <div style={railHeading}>saved layouts</div>

      {layouts.length === 0 && <div style={{ color: '#5f7a5f' }}>none saved yet</div>}
      {layouts.map((saved) => (
        <div key={saved} style={{ display: 'flex', alignItems: 'center', gap: 4, marginBottom: 3 }}>
          <span style={{ flex: 1, color: '#9fd89f' }}>{saved}</span>
          <button style={chip} onClick={() => onLoad(saved)}>
            load
          </button>
          <button style={chip} onClick={() => onSave(saved)}>
            overwrite
          </button>
          <button style={chip} onClick={() => onDelete(saved)}>
            delete
          </button>
        </div>
      ))}

      <div style={{ display: 'flex', gap: 4, marginTop: 6 }}>
        <input
          value={name}
          placeholder="new layout name"
          style={{ ...numberInput, flex: 1, width: 'auto' }}
          onChange={(event) => setName(event.target.value)}
          onKeyDown={(event) => {
            if (event.key !== 'Enter' || chosen === '') return;
            onSave(chosen);
            setName('');
          }}
        />
        <button
          style={chip}
          disabled={chosen === ''}
          onClick={() => {
            onSave(chosen);
            setName('');
          }}
        >
          save
        </button>
      </div>

      {savedLayout && (
        <div style={{ color: '#5f7a5f', marginTop: 6, wordBreak: 'break-all' }}>
          wrote {savedLayout.path} — commit it
        </div>
      )}
      {!savedLayout && directory && (
        <div style={{ color: '#5f7a5f', marginTop: 6, wordBreak: 'break-all' }}>{directory}</div>
      )}
    </div>
  );
}

export function Inspector({
  devices,
  selected,
  api,
  options,
  alliance,
  layoutFiles,
  layoutDirectory,
  savedLayout,
  cameraMount,
  savedCameraMount,
  onSelect,
  onOptions,
  onAlliance,
  onOverride,
  onResetBehavior,
  onSaveLayout,
  onLoadLayout,
  onDeleteLayout,
  onCameraMount,
  onSaveCameraMount,
  onRevertCameraMount,
}: {
  devices: DeviceState[];
  selected: string | null;
  api: LayoutApi;
  options: ViewOptions;
  /** Which alliance the sim is running for; the rail sets it, the sim owns it. */
  alliance: Alliance;
  layoutFiles: string[];
  layoutDirectory: string | null;
  savedLayout: { name: string; path: string } | null;
  /** The webcam's mount, or null when this robot has no camera. */
  cameraMount: CameraMountPayload | null;
  savedCameraMount: { path: string } | null;
  onSelect: (name: string | null) => void;
  onOptions: (patch: Partial<ViewOptions>) => void;
  onAlliance: (alliance: Alliance) => void;
  onOverride: (device: string, type: string, value: number) => void;
  onResetBehavior: (device: string) => void;
  onSaveLayout: (name: string) => void;
  onLoadLayout: (name: string) => void;
  onDeleteLayout: (name: string) => void;
  onCameraMount: (mount: CameraMount) => void;
  onSaveCameraMount: () => void;
  onRevertCameraMount: () => void;
}) {
  const device = devices.find((candidate) => candidate.name === selected) ?? null;

  return (
    <div style={rail}>
      <div style={railHeading}>view</div>
      <div style={{ display: 'flex', flexWrap: 'wrap', gap: 8, marginBottom: 10 }}>
        <label>
          <input
            type="checkbox"
            checked={options.followYaw}
            onChange={(event) => onOptions({ followYaw: event.target.checked })}
          />
          chassis follows IMU
        </label>
        <label>
          <input
            type="checkbox"
            checked={options.showStickVector}
            onChange={(event) => onOptions({ showStickVector: event.target.checked })}
          />
          stick vector
        </label>
        <label>
          <input
            type="checkbox"
            checked={options.showLabels}
            onChange={(event) => onOptions({ showLabels: event.target.checked })}
          />
          labels
        </label>
        <label>
          <input
            type="checkbox"
            checked={options.snap}
            onChange={(event) => onOptions({ snap: event.target.checked })}
          />
          snap 1cm
        </label>
        <label>
          <input
            type="checkbox"
            checked={options.allianceView}
            onChange={(event) => onOptions({ allianceView: event.target.checked })}
          />
          alliance view
        </label>
        <label>
          <input
            type="checkbox"
            checked={options.showTags}
            onChange={(event) => onOptions({ showTags: event.target.checked })}
          />
          tags &amp; elements
        </label>
      </div>

      {/* Alliance is not a camera preset: it is which station the sim zeroes the heading against. */}
      <div style={railHeading}>alliance</div>
      <div style={{ display: 'flex', gap: 4, marginBottom: 10 }}>
        {(['red', 'blue'] as Alliance[]).map((side) => (
          <button
            key={side}
            style={side === alliance ? activeChip : chip}
            onClick={() => onAlliance(side)}
          >
            {side}
          </button>
        ))}
      </div>

      <div style={railHeading}>devices</div>
      {devices.length === 0 && <div style={{ color: '#555' }}>init an OpMode</div>}
      <div style={{ display: 'flex', flexWrap: 'wrap', gap: 4 }}>
        {devices.map((candidate) => (
          <button
            key={candidate.name}
            style={candidate.name === selected ? activeChip : chip}
            onClick={() => onSelect(candidate.name === selected ? null : candidate.name)}
          >
            {candidate.name}
            {/* The star means "edited and not committed" for every device; for the camera the
                uncommitted thing is its mount, which the server tracks rather than this rail. */}
            {(candidate.name === cameraMount?.name
              ? cameraMount.unsaved
              : api.customized(candidate.name))
              ? '*'
              : ''}
          </button>
        ))}
      </div>

      {/* The camera is the device the mount names; nothing else knows which device that is. */}
      {device && cameraMount && device.name === cameraMount.name ? (
        <CameraMountEditor
          device={device}
          mount={cameraMount}
          savedPath={savedCameraMount?.path ?? null}
          onMount={onCameraMount}
          onSave={onSaveCameraMount}
          onRevert={onRevertCameraMount}
        />
      ) : device ? (
        <DeviceEditor
          device={device}
          layout={api.layout[device.name]}
          api={api}
          onOverride={onOverride}
          onReset={onResetBehavior}
        />
      ) : (
        devices.length > 0 && (
          <div style={{ color: '#5f7a5f', marginTop: 8, lineHeight: 1.5 }}>
            click a device in the scene to place it. drag to move, shift-drag to raise. drag the
            chassis to place the robot on the field, shift-drag it to swing the heading. left-drag
            empty space orbits, wheel zooms.
          </div>
        )
      )}

      <LayoutFiles
        layouts={layoutFiles}
        directory={layoutDirectory}
        savedLayout={savedLayout}
        onSave={onSaveLayout}
        onLoad={onLoadLayout}
        onDelete={onDeleteLayout}
      />

      <div style={{ marginTop: 'auto', paddingTop: 10 }}>
        <button style={button} onClick={api.resetAll}>
          reset all placements
        </button>
      </div>
    </div>
  );
}

const rail: React.CSSProperties = {
  width: 280,
  borderLeft: '1px solid #1e2a1e',
  padding: 12,
  fontSize: 12,
  overflowY: 'auto',
  display: 'flex',
  flexDirection: 'column',
  color: '#9fd89f',
};

/**
 * The one thing in this rail that is a warning rather than a control: an aimed camera nobody has
 * saved is lost on a reload, and it is worn in the same amber the status bar uses for INIT.
 */
const unsavedMark: React.CSSProperties = {
  color: '#d8d84a',
  border: '1px solid #4a4a14',
  background: '#232314',
  padding: '1px 6px',
  fontSize: 11,
};
