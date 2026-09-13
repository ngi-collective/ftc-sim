import { BEHAVIORS, type DeviceState } from '../protocol';
import { activeChip, button, chip, numberInput, railHeading, selectStyle } from '../ui';
import { MOUNTS, type DeviceLayout, type LayoutApi, type Mount } from './layout';
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
      <div style={{ display: 'flex', gap: 4, marginBottom: 8 }}>
        {POSITION_AXES.map(({ index, label }) => (
          <label key={label} style={{ color: '#6f8f6f' }}>
            {label}
            <input
              type="number"
              step={0.01}
              value={Number(layout.position[index].toFixed(3))}
              style={{ ...numberInput, width: 58, marginLeft: 2 }}
              onChange={(event) => {
                const position: [number, number, number] = [...layout.position];
                position[index] = Number(event.target.value);
                api.update(device.name, { position });
              }}
            />
          </label>
        ))}
      </div>

      <div style={{ color: '#6f8f6f', marginBottom: 2 }}>orientation (deg)</div>
      {AXES.map(({ index, label }) => (
        <div key={label} style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
          <span style={{ width: 34, color: '#6f8f6f' }}>{label}</span>
          <input
            type="range"
            min={-180}
            max={180}
            step={1}
            value={layout.rotation[index]}
            style={{ flex: 1, accentColor: '#7CFC00' }}
            onChange={(event) => {
              const rotation: [number, number, number] = [...layout.rotation];
              rotation[index] = Number(event.target.value);
              api.update(device.name, { rotation });
            }}
          />
          <span style={{ width: 34, textAlign: 'right' }}>{Math.round(layout.rotation[index])}°</span>
        </div>
      ))}

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
        <span style={{ color: '#6f8f6f' }}>mount</span>
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
        <label style={{ color: '#6f8f6f' }}>
          <input
            type="checkbox"
            checked={layout.invert}
            onChange={(event) => api.update(device.name, { invert: event.target.checked })}
          />
          invert
        </label>
      </div>

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

export function Inspector({
  devices,
  selected,
  api,
  options,
  onSelect,
  onOptions,
  onOverride,
  onResetBehavior,
}: {
  devices: DeviceState[];
  selected: string | null;
  api: LayoutApi;
  options: ViewOptions;
  onSelect: (name: string | null) => void;
  onOptions: (patch: Partial<ViewOptions>) => void;
  onOverride: (device: string, type: string, value: number) => void;
  onResetBehavior: (device: string) => void;
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
