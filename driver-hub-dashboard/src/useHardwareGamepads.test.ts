import { describe, expect, it } from 'vitest';
import { NEUTRAL_GAMEPAD } from './protocol';
import { read, stick } from './useHardwareGamepads';

/**
 * The two pure halves of reading a controller: where the stick's rest position stops mattering,
 * and which index on the pad is which button on the robot.
 */

/** A standard-mapping pad, with whatever axes and buttons the case is about. */
function pad(options: { axes?: number[]; pressed?: number[]; values?: Record<number, number> }) {
  const count = 17;
  const buttons = Array.from({ length: count }, (_unused, index) => ({
    pressed: options.pressed?.includes(index) ?? false,
    touched: false,
    value: options.values?.[index] ?? 0,
  }));
  return {
    id: 'Test Pad (STANDARD GAMEPAD)',
    index: 0,
    connected: true,
    mapping: 'standard',
    timestamp: 0,
    axes: options.axes ?? [0, 0, 0, 0],
    buttons,
  } as unknown as Gamepad;
}

describe('stick', () => {
  it('zeroes a stick resting slightly off centre', () => {
    expect(stick(0.05, 0.05)).toEqual([0, 0]);
    expect(stick(0, 0.079)).toEqual([0, 0]);
    expect(stick(-0.05, 0.02)).toEqual([0, 0]);
  });

  it('measures the deadzone radially, not axis by axis', () => {
    // 0.07 and 0.05 are each inside 0.08 while their hypotenuse, 0.086, is outside it. A per-axis
    // deadzone — the obvious simplification of this function — would read this as a centred stick
    // and the robot would refuse a slow diagonal nudge entirely.
    expect(stick(0.07, 0.05)).toEqual([0.07, 0.05]);
    expect(Math.hypot(0.07, 0.05)).toBeGreaterThan(0.08);
  });

  it('passes the axes through unscaled once they are outside', () => {
    // Deliberately not re-normalised: rescaling the remainder to [0, 1] would make the first
    // commanded value past the deadzone a jump to 8% of full power, which a driver feathering a
    // mechanism into position feels as a lurch.
    expect(stick(0.09, 0)).toEqual([0.09, 0]);
    expect(stick(1, -1)).toEqual([1, -1]);
    expect(stick(-0.5, 0.25)).toEqual([-0.5, 0.25]);
  });
});

describe('read', () => {
  /**
   * The W3C standard mapping, which is the contract this function exists to pin. A wrong index is
   * invisible until a driver presses a button mid-match and the robot does something else.
   */
  const BUTTONS: [number, keyof typeof NEUTRAL_GAMEPAD][] = [
    [0, 'a'],
    [1, 'b'],
    [2, 'x'],
    [3, 'y'],
    [4, 'left_bumper'],
    [5, 'right_bumper'],
    [9, 'options'],
    [10, 'left_stick_button'],
    [11, 'right_stick_button'],
    [12, 'dpad_up'],
    [13, 'dpad_down'],
    [14, 'dpad_left'],
    [15, 'dpad_right'],
  ];

  it('maps each standard-mapping button to exactly one SDK field', () => {
    for (const [index, field] of BUTTONS) {
      const state = read(pad({ pressed: [index] }));
      const held = Object.entries(state)
        .filter(([, value]) => value === true)
        .map(([name]) => name);
      expect(held, `button ${index}`).toEqual([field]);
    }
  });

  it('reads the triggers as the analogue values of buttons 6 and 7', () => {
    // Triggers are buttons in the standard mapping, not axes, and they are the one pair whose
    // value rather than pressed state is what the OpMode reads.
    const state = read(pad({ values: { 6: 0.4, 7: 0.9 } }));
    expect(state.left_trigger).toBe(0.4);
    expect(state.right_trigger).toBe(0.9);
  });

  it('takes the sticks from axes 0-3 without flipping Y', () => {
    // The browser reports forward as negative and so does the SDK; a flip here would invert every
    // drive command.
    const state = read(pad({ axes: [0.3, -0.6, -0.9, 0.5] }));
    expect(state.left_stick_x).toBe(0.3);
    expect(state.left_stick_y).toBe(-0.6);
    expect(state.right_stick_x).toBe(-0.9);
    expect(state.right_stick_y).toBe(0.5);
  });

  it('reads a pad that reports no buttons or axes as neutral, never as undefined', () => {
    // Non-standard pads report shorter arrays. An undefined would reach the wire as a missing JSON
    // field and land in the OpMode as Gamepad's own default, so which fields exist would depend on
    // the controller plugged in.
    const state = read(pad({ axes: [], pressed: [] }));
    expect(state).toEqual(NEUTRAL_GAMEPAD);
    for (const value of Object.values(state)) expect(value).not.toBeUndefined();
  });

  it('sends every field the SDK Gamepad expects, whatever the pad reported', () => {
    // The Java side deserializes this straight onto a Gamepad; a field this function forgets is a
    // control that silently does nothing.
    expect(Object.keys(read(pad({}))).sort()).toEqual(Object.keys(NEUTRAL_GAMEPAD).sort());
  });
});
