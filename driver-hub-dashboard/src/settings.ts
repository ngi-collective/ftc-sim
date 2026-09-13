import { useCallback, useEffect, useState } from 'react';

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
}

const DEFAULTS: Settings = {
  keyboardGamepad: false,
};

const STORAGE_KEY = 'driverhub.settings.v1';

function load(): Settings {
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
    };
  } catch {
    // Unreadable storage is not worth failing the page over; fall back to the defaults.
    return { ...DEFAULTS };
  }
}

export function useSettings(): { settings: Settings; update: (patch: Partial<Settings>) => void } {
  const [settings, setSettings] = useState<Settings>(load);

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
