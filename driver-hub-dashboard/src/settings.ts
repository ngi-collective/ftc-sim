import { useCallback, useEffect, useState } from 'react';
import { DEFAULT_SIM_STATUS, type Alliance } from './protocol';

/**
 * Dashboard preferences that survive a reload.
 *
 * <p>One blob under one key rather than a key per setting: these are read together on every mount,
 * and adding the next one should not mean inventing another storage name and another migration.</p>
 */
export interface Settings {
  /**
   * Whether WASD and the arrow keys stand in for gamepad1.
   *
   * <p>Off by default. It is a global key grab, and a dashboard is also a thing people read while
   * typing somewhere else; nobody should have to guess why their keystrokes went missing.</p>
   */
  keyboardGamepad: boolean;
  /**
   * Which driver station the operator is standing behind.
   *
   * <p>It decides the zero of the robot's reported heading, so it outlives a reload: a team that
   * set up on blue should not come back from a refresh secretly running red's frame.</p>
   */
  alliance: Alliance;
  /** How fast simulated time runs against the wall clock. */
  simMultiplier: number;
}

const DEFAULTS: Settings = {
  keyboardGamepad: false,
  alliance: DEFAULT_SIM_STATUS.alliance,
  simMultiplier: DEFAULT_SIM_STATUS.multiplier,
};

/** The speeds the strip offers, and the only ones a stored preference is allowed to name. */
export const SIM_MULTIPLIERS = [0.25, 0.5, 1, 2, 4] as const;

const STORAGE_KEY = 'driverhub.settings.v1';

/**
 * The stored settings, with anything unreadable or no longer offered replaced by its default.
 *
 * <p>Exported because every one of those fallbacks is a decision, not a formality: a stored
 * multiplier the strip no longer offers would leave the speed control showing nothing selected
 * while simulated time ran at that rate, and a stored alliance of {@code "RED"} would put the
 * robot's heading zero a quarter turn from where the driver is standing.</p>
 */
export function loadSettings(): Settings {
  try {
    const stored = localStorage.getItem(STORAGE_KEY);
    if (!stored) return { ...DEFAULTS };
    const parsed: unknown = JSON.parse(stored);
    if (typeof parsed !== 'object' || parsed === null) return { ...DEFAULTS };
    const fields = parsed as Record<string, unknown>;
    return {
      keyboardGamepad:
        typeof fields.keyboardGamepad === 'boolean'
          ? fields.keyboardGamepad
          : DEFAULTS.keyboardGamepad,
      alliance:
        fields.alliance === 'blue' || fields.alliance === 'red'
          ? fields.alliance
          : DEFAULTS.alliance,
      simMultiplier: SIM_MULTIPLIERS.some((rate) => rate === fields.simMultiplier)
        ? (fields.simMultiplier as number)
        : DEFAULTS.simMultiplier,
    };
  } catch {
    // Unreadable storage is not worth failing the page over; fall back to the defaults.
    return { ...DEFAULTS };
  }
}

export function useSettings(): { settings: Settings; update: (patch: Partial<Settings>) => void } {
  const [settings, setSettings] = useState<Settings>(loadSettings);

  useEffect(() => {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(settings));
  }, [settings]);

  return {
    settings,
    update: useCallback(
      (patch: Partial<Settings>) => setSettings((previous) => ({ ...previous, ...patch })),
      [],
    ),
  };
}
