import { useEffect, useRef, useState } from 'react';
import { NEUTRAL_GAMEPAD, type GamepadState } from './protocol';

/**
 * Real controllers plugged into this computer, driving the simulated robot.
 *
 * <p>The browser's Gamepad API has no event for stick motion &mdash; a pad is a thing you poll.
 * So this samples on the same fixed interval the keyboard stand-in uses, which is also how the
 * Driver Station samples: an OpMode reads stick positions every loop and never sees events.</p>
 *
 * <p>Pads take robot slots in the order the browser lists them: the first connected pad is
 * {@code gamepad1}, the second is {@code gamepad2}, and any others are ignored because the SDK
 * has nowhere to put them.</p>
 *
 * <p>Nothing is sent while no pad is attached, so a dashboard without a controller leaves the
 * gamepad namespace alone and the keyboard stand-in keeps working.</p>
 *
 * <p>Note for Chrome: a pad stays invisible to the page until the driver presses a button on it.
 * That is a browser privacy rule, not a bug here &mdash; press a button and the pad appears.</p>
 */

const SAMPLE_INTERVAL_MS = 50;

/**
 * Sticks rest a little off-center, and the robot should not creep while nobody is touching it.
 * Radial rather than per-axis, so a diagonal nudge is not treated as two separate small pushes.
 */
const STICK_DEADZONE = 0.08;

export interface PadInfo {
  /** The robot gamepad this pad writes: 1 or 2. */
  slot: 1 | 2;
  /** The browser's name for the device, as shown in the header. */
  id: string;
  /**
   * Whether the browser recognised the pad's layout. Buttons are mapped by index either way,
   * which is right for anything that looks like an Xbox or PlayStation pad and a guess otherwise.
   */
  standard: boolean;
}

export interface HardwareGamepads {
  /** Attached pads in slot order; empty when none is connected. */
  pads: PadInfo[];
  /** The newest sample sent to {@code gamepad1}, for the on-screen controller. */
  gamepad1: GamepadState;
}

/**
 * What to tell the driver about the pads in their hands, or null when there are none and the
 * caller should fall back to whatever the keyboard is doing.
 *
 * <p>Browser pad ids trail a parenthetical of vendor and product codes; the human-readable part
 * is what belongs in a caption.</p>
 */
export function describePads(pads: PadInfo[]): string | null {
  if (pads.length === 0) return null;
  return pads
    .map(
      (pad) =>
        `gamepad${pad.slot}: ${pad.id.replace(/\s*\([^)]*\)\s*$/, '')}` +
        (pad.standard ? '' : ' (unrecognised layout — buttons may be wrong)'),
    )
    .join(' · ');
}

function pressed(pad: Gamepad, index: number): boolean {
  return pad.buttons[index]?.pressed ?? false;
}

function pull(pad: Gamepad, index: number): number {
  return pad.buttons[index]?.value ?? 0;
}

function stick(x: number, y: number): [number, number] {
  return Math.hypot(x, y) < STICK_DEADZONE ? [0, 0] : [x, y];
}

/**
 * One pad in the standard mapping, read as the SDK's {@code Gamepad}.
 *
 * <p>Stick Y needs no sign flip: the browser reports forward as negative and so does the SDK.</p>
 */
function read(pad: Gamepad): GamepadState {
  const [leftX, leftY] = stick(pad.axes[0] ?? 0, pad.axes[1] ?? 0);
  const [rightX, rightY] = stick(pad.axes[2] ?? 0, pad.axes[3] ?? 0);
  return {
    left_stick_x: leftX,
    left_stick_y: leftY,
    right_stick_x: rightX,
    right_stick_y: rightY,
    left_trigger: pull(pad, 6),
    right_trigger: pull(pad, 7),
    dpad_up: pressed(pad, 12),
    dpad_down: pressed(pad, 13),
    dpad_left: pressed(pad, 14),
    dpad_right: pressed(pad, 15),
    left_bumper: pressed(pad, 4),
    right_bumper: pressed(pad, 5),
    left_stick_button: pressed(pad, 10),
    right_stick_button: pressed(pad, 11),
    a: pressed(pad, 0),
    b: pressed(pad, 1),
    x: pressed(pad, 2),
    y: pressed(pad, 3),
    options: pressed(pad, 9),
  };
}

function samePads(a: PadInfo[], b: PadInfo[]): boolean {
  return (
    a.length === b.length &&
    a.every((pad, index) => pad.id === b[index].id && pad.standard === b[index].standard)
  );
}

export function useHardwareGamepads(
  send: (state: GamepadState, which: 1 | 2) => void,
): HardwareGamepads {
  const [pads, setPads] = useState<PadInfo[]>([]);
  const [gamepad1, setGamepad1] = useState<GamepadState>({ ...NEUTRAL_GAMEPAD });
  const driving = useRef(new Set<1 | 2>());

  useEffect(() => {
    const held = driving.current;

    /** Lets go of a slot we were driving, once, so the robot does not hold the last stick. */
    const release = (slot: 1 | 2) => {
      if (!held.delete(slot)) return;
      send({ ...NEUTRAL_GAMEPAD }, slot);
      if (slot === 1) setGamepad1({ ...NEUTRAL_GAMEPAD });
    };

    const timer = setInterval(() => {
      const attached = navigator
        .getGamepads()
        .filter((pad): pad is Gamepad => pad !== null)
        .slice(0, 2);

      const next: PadInfo[] = attached.map((pad, index) => ({
        slot: index === 0 ? 1 : 2,
        id: pad.id,
        standard: pad.mapping === 'standard',
      }));
      setPads((previous) => (samePads(previous, next) ? previous : next));

      // Browsers freeze a pad's readings while the page is in the background, so a stick left
      // pushed as the driver switches windows would otherwise pin the robot at that power
      // forever. Treat losing focus the way the keyboard stand-in treats it: let go.
      const focused = document.hasFocus();

      for (const slot of [1, 2] as const) {
        const pad = attached[slot - 1];
        if (!pad || !focused) {
          release(slot);
          continue;
        }
        const state = read(pad);
        held.add(slot);
        send(state, slot);
        if (slot === 1) setGamepad1(state);
      }
    }, SAMPLE_INTERVAL_MS);

    return () => {
      clearInterval(timer);
      release(1);
      release(2);
    };
  }, [send]);

  return { pads, gamepad1 };
}
