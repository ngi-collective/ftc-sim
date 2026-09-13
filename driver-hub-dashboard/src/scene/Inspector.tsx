import { useState } from 'react';
import { BEHAVIORS, type DeviceState } from '../protocol';
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
  layoutFiles,
  layoutDirectory,
  savedLayout,
  onSelect,
  onOptions,
  onOverride,
  onResetBehavior,
  onSaveLayout,
  onLoadLayout,
  onDeleteLayout,
}: {
  devices: DeviceState[];
  selected: string | null;
  api: LayoutApi;
  options: ViewOptions;
  layoutFiles: string[];
  layoutDirectory: string | null;
  savedLayout: { name: string; path: string } | null;
  onSelect: (name: string | null) => void;
  onOptions: (patch: Partial<ViewOptions>) => void;
  onOverride: (device: string, type: string, value: number) => void;
  onResetBehavior: (device: string) => void;
  onSaveLayout: (name: string) => void;
  onLoadLayout: (name: string) => void;
  onDeleteLayout: (name: string) => void;
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
            {api.customized(candidate.name) ? '*' : ''}
          </button>
        ))}
      </div>

      {device ? (
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
            click a device in the scene to place it. drag to move, shift-drag to raise. left-drag
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
