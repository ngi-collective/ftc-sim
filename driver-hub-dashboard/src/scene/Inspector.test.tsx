// @vitest-environment happy-dom
import { act } from 'react';
import { createRoot } from 'react-dom/client';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { CameraMount, CameraMountPayload, DeviceState } from '../protocol';
import { Inspector } from './Inspector';
import type { DeviceLayout, LayoutApi } from './layout';
import { DEFAULT_VIEW_OPTIONS } from './RobotScene';

/**
 * The rail, rendered, for one reason: which editor a device gets is decided by a name match, and
 * getting that wrong offers the camera six sliders that move a decorative cube — which is the exact
 * bug this whole feature exists to remove, and it looks like a working editor.
 *
 * <p>Queried by control shape rather than by label text: the wording is meant to be rewritten
 * whenever it reads badly to someone holding a robot.</p>
 */
declare global {
  /** React reads this to decide whether {@code act} is allowed; it is not part of lib.dom. */
  var IS_REACT_ACT_ENVIRONMENT: boolean;
}

globalThis.IS_REACT_ACT_ENVIRONMENT = true;

const MOUNT: CameraMountPayload = {
  name: 'Webcam 1',
  forwardMetres: 0.16,
  leftMetres: 0,
  heightMetres: 0.105,
  yawDegrees: 0,
  pitchDegrees: 35,
  rollDegrees: 0,
  horizontalFovDegrees: 60,
  verticalFovDegrees: 46.8,
  unsaved: false,
};

const PLACEMENT: DeviceLayout = {
  position: [0, 0.1, 0],
  rotation: [0, 0, 0],
  scale: 1,
  mount: 'bare',
  ratio: 1,
  outputOffset: [0, 0, 0.034],
  outputRotation: [0, 0, 0],
  ticksPerRev: 537.7,
};

function device(name: string, kind: DeviceState['kind']): DeviceState {
  return {
    name,
    kind,
    behavior: 'default',
    commandedPower: 0,
    physicalPower: 0,
    velocityTicksPerSecond: 0,
    position: 0,
    mode: null,
    commandedPosition: 0,
    hornPosition: 0,
    yawDegrees: 0,
    yawRateDegreesPerSecond: 0,
    clippedCommandCount: 0,
    lastClippedCommand: 0,
  };
}

const host = document.createElement('div');
document.body.append(host);
const root = createRoot(host);

afterEach(() => {
  act(() => root.render(null));
});

function show(selected: string, onCameraMount: (mount: CameraMount) => void): void {
  const api: LayoutApi = {
    layout: { 'Webcam 1': PLACEMENT, FL: PLACEMENT },
    customized: () => false,
    update: () => {},
    reset: () => {},
    resetAll: () => {},
    toFile: () => ({ version: 1, devices: {} }),
    applyFile: () => {},
  };
  act(() =>
    root.render(
      <Inspector
        devices={[device('FL', 'motor'), device('Webcam 1', 'unknown')]}
        selected={selected}
        api={api}
        options={DEFAULT_VIEW_OPTIONS}
        alliance="red"
        layoutFiles={[]}
        layoutDirectory={null}
        savedLayout={null}
        cameraMount={MOUNT}
        savedCameraMount={null}
        onSelect={() => {}}
        onOptions={() => {}}
        onAlliance={() => {}}
        onOverride={() => {}}
        onResetBehavior={() => {}}
        onSaveLayout={() => {}}
        onLoadLayout={() => {}}
        onDeleteLayout={() => {}}
        onCameraMount={onCameraMount}
        onSaveCameraMount={() => {}}
        onRevertCameraMount={() => {}}
      />,
    ),
  );
}

/** The editors' controls, by the shape that distinguishes them. */
function controls() {
  const numbers = [...host.querySelectorAll('input[type="number"]')] as HTMLInputElement[];
  const sliders = [...host.querySelectorAll('input[type="range"]')] as HTMLInputElement[];
  return {
    metreFields: numbers.filter((input) => input.step === '0.005'),
    positionFields: numbers.filter((input) => input.step === '0.01'),
    sliders,
    /** The "drives" picker, which only a mechanism has. */
    mountPicker: host.querySelector('select'),
  };
}

/**
 * Moves a control the way a pointer does.
 *
 * <p>Through the prototype's own setter on purpose: react-dom replaces {@code value} on each input
 * instance with a tracked setter, so a plain assignment updates React's idea of the current value
 * and the subsequent event is discarded as "no change".</p>
 */
function drag(control: HTMLInputElement, value: string): void {
  const native = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value')?.set;
  act(() => {
    native?.call(control, value);
    control.dispatchEvent(new Event('input', { bubbles: true }));
  });
}

describe('the rail editor a device gets', () => {
  it('offers the camera its mount, and none of the placement controls that mean nothing for it', () => {
    show('Webcam 1', () => {});
    const { metreFields, positionFields, sliders, mountPicker } = controls();
    // Forward, left, height — and no cosmetic position, aim preset, mount or scale control, all of
    // which move a drawn box while the real camera stays where the robot config put it.
    expect(metreFields).toHaveLength(3);
    expect(positionFields).toHaveLength(0);
    expect(mountPicker).toBeNull();
    // Yaw, pitch, roll, and nothing else: the layout editor's scale slider is gone too.
    expect(sliders).toHaveLength(3);
  });

  it('leaves every other device with the placement editor it always had', () => {
    show('FL', () => {});
    const { metreFields, positionFields, mountPicker } = controls();
    expect(metreFields).toHaveLength(0);
    expect(positionFields.length).toBeGreaterThan(0);
    expect(mountPicker).not.toBeNull();
  });
});

describe('dragging a mount control', () => {
  it('sends all six numbers, not just the one that moved', () => {
    // The server takes a whole mount: a message carrying only the dragged angle would aim the
    // camera at the origin with everything else zeroed.
    const sent = vi.fn();
    show('Webcam 1', sent);
    const yaw = controls().sliders[0];

    drag(yaw, '45');

    expect(sent).toHaveBeenCalledWith({
      forwardMetres: MOUNT.forwardMetres,
      leftMetres: MOUNT.leftMetres,
      heightMetres: MOUNT.heightMetres,
      yawDegrees: 45,
      pitchDegrees: MOUNT.pitchDegrees,
      rollDegrees: MOUNT.rollDegrees,
    });
  });
});
