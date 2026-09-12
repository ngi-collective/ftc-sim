import { useEffect, useRef, useState } from 'react';
import { NEUTRAL_GAMEPAD, type GamepadState } from './protocol';

/**
 * Keyboard stand-in for gamepad1.
 *
 * <p>Held keys, not key events: an OpMode polls stick positions every loop, so what matters is
 * which keys are down right now. State is pushed on a fixed interval rather than on every keydown,
 * which keeps the socket quiet and matches how a real gamepad is sampled.
 */
const KEY_MAP: Record<string, (state: GamepadState, held: boolean) => void> = {
  KeyW: (s, held) => (s.left_stick_y = held ? -1 : 0),
  KeyS: (s, held) => (s.left_stick_y = held ? 1 : 0),
  KeyA: (s, held) => (s.left_stick_x = held ? -1 : 0),
  KeyD: (s, held) => (s.left_stick_x = held ? 1 : 0),
  ArrowLeft: (s, held) => (s.right_stick_x = held ? -1 : 0),
  ArrowRight: (s, held) => (s.right_stick_x = held ? 1 : 0),
  ArrowUp: (s, held) => (s.right_stick_y = held ? -1 : 0),
  ArrowDown: (s, held) => (s.right_stick_y = held ? 1 : 0),
  ShiftLeft: (s, held) => (s.left_trigger = held ? 1 : 0),
  KeyI: (s, held) => (s.dpad_up = held),
  KeyK: (s, held) => (s.dpad_down = held),
  KeyJ: (s, held) => (s.dpad_left = held),
  KeyL: (s, held) => (s.dpad_right = held),
  KeyO: (s, held) => (s.options = held),
};

export const KEYBOARD_HELP = 'WASD drive · arrows turn · shift slow · IJKL d-pad · O options';

const SAMPLE_INTERVAL_MS = 50;

export function useKeyboardGamepad(send: (state: GamepadState) => void): GamepadState {
  const held = useRef(new Set<string>());
  const [snapshot, setSnapshot] = useState<GamepadState>({ ...NEUTRAL_GAMEPAD });

  useEffect(() => {
    const down = (event: KeyboardEvent) => {
      if (!KEY_MAP[event.code]) return;
      event.preventDefault();
      held.current.add(event.code);
    };
    const up = (event: KeyboardEvent) => {
      held.current.delete(event.code);
    };
    // Losing focus with a key down would otherwise leave the robot driving forever.
    const blur = () => held.current.clear();

    window.addEventListener('keydown', down);
    window.addEventListener('keyup', up);
    window.addEventListener('blur', blur);
    return () => {
      window.removeEventListener('keydown', down);
      window.removeEventListener('keyup', up);
      window.removeEventListener('blur', blur);
    };
  }, []);

  useEffect(() => {
    const timer = setInterval(() => {
      const state: GamepadState = { ...NEUTRAL_GAMEPAD };
      for (const code of held.current) KEY_MAP[code]?.(state, true);
      setSnapshot(state);
      send(state);
    }, SAMPLE_INTERVAL_MS);
    return () => clearInterval(timer);
  }, [send]);

  return snapshot;
}
