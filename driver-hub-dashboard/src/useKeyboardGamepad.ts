import { useEffect, useRef, useState } from 'react';
import { NEUTRAL_GAMEPAD, type GamepadState } from './protocol';

/**
 * Keyboard stand-in for gamepad1, when the user has asked for one.
 *
 * <p>Held keys, not key events: an OpMode polls stick positions every loop, so what matters is
 * which keys are down right now. State is pushed on a fixed interval rather than on every keydown,
 * which keeps the socket quiet and matches how a real gamepad is sampled.</p>
 *
 * <p>Switched off, this hook is silent in both directions: no key is captured, and nothing is sent
 * on the gamepad namespace. That matters beyond the local page &mdash; every connected dashboard
 * writes to the same {@code gamepad1}, so a tab left open streaming neutral input would fight
 * whoever is actually driving.</p>
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

export const KEYBOARD_OFF = 'keyboard joysticks off — turn them on in the header';

const SAMPLE_INTERVAL_MS = 50;

/**
 * Whether a key belongs to whatever the user is typing in.
 *
 * <p>The listener is on the window, so without this the drive keys would eat characters out of the
 * dashboard's own text fields &mdash; naming a layout "wasd" would drive the robot instead.</p>
 */
function isTyping(target: EventTarget | null): boolean {
  if (!(target instanceof HTMLElement)) return false;
  return (
    target.isContentEditable ||
    target instanceof HTMLInputElement ||
    target instanceof HTMLTextAreaElement ||
    target instanceof HTMLSelectElement
  );
}

export function useKeyboardGamepad(
  send: (state: GamepadState) => void,
  enabled: boolean,
): GamepadState {
  const held = useRef(new Set<string>());
  const wasEnabled = useRef(false);
  const [snapshot, setSnapshot] = useState<GamepadState>({ ...NEUTRAL_GAMEPAD });

  useEffect(() => {
    if (!enabled) return;

    const down = (event: KeyboardEvent) => {
      if (!KEY_MAP[event.code] || isTyping(event.target)) return;
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
  }, [enabled]);

  useEffect(() => {
    if (!enabled) {
      held.current.clear();
      setSnapshot({ ...NEUTRAL_GAMEPAD });
      // Switching off mid-drive must let go of the sticks, but a dashboard that never had them
      // stays quiet: one neutral frame only, and only on the way out.
      if (wasEnabled.current) send({ ...NEUTRAL_GAMEPAD });
      wasEnabled.current = false;
      return;
    }

    wasEnabled.current = true;
    const timer = setInterval(() => {
      const state: GamepadState = { ...NEUTRAL_GAMEPAD };
      for (const code of held.current) KEY_MAP[code]?.(state, true);
      setSnapshot(state);
      send(state);
    }, SAMPLE_INTERVAL_MS);
    return () => clearInterval(timer);
  }, [enabled, send]);

  return snapshot;
}
