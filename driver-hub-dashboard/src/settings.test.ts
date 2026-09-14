// @vitest-environment happy-dom
import { beforeEach, describe, expect, it } from 'vitest';
import { DEFAULT_SIM_STATUS } from './protocol';
import { loadSettings, SIM_MULTIPLIERS } from './settings';

/**
 * What a bad stored blob does to the dashboard.
 *
 * <p>Settings come back from a previous version of this app, or from a hand-edited devtools
 * session, so every field is a place where "whatever was in storage" must not become "whatever the
 * robot does". The key is spelled out here rather than imported: renaming it drops every existing
 * user's preferences, which is a thing to decide on purpose.</p>
 */
const STORAGE_KEY = 'driverhub.settings.v1';

describe('loadSettings', () => {
  beforeEach(() => {
    localStorage.clear();
  });

  it('falls back to the defaults for storage that is not JSON at all', () => {
    localStorage.setItem(STORAGE_KEY, '{"alliance":"blue"');
    expect(loadSettings()).toEqual({
      keyboardGamepad: false,
      alliance: DEFAULT_SIM_STATUS.alliance,
      simMultiplier: DEFAULT_SIM_STATUS.multiplier,
    });
  });

  it('falls back to the defaults for JSON that is not an object', () => {
    for (const stored of ['null', '"blue"', '42', '[{"alliance":"blue"}]']) {
      localStorage.setItem(STORAGE_KEY, stored);
      expect(loadSettings().alliance).toBe(DEFAULT_SIM_STATUS.alliance);
    }
  });

  it('starts from the defaults when nothing has been stored yet', () => {
    expect(loadSettings()).toEqual({
      keyboardGamepad: false,
      alliance: DEFAULT_SIM_STATUS.alliance,
      simMultiplier: DEFAULT_SIM_STATUS.multiplier,
    });
  });

  it('rejects a multiplier the speed strip does not offer', () => {
    // A rate with no chip would leave the strip showing nothing selected while simulated time ran
    // at it — the UI and the simulation disagreeing about a number the operator just read.
    for (const rejected of [3, 0, -1, 100, '2', null]) {
      localStorage.setItem(STORAGE_KEY, JSON.stringify({ simMultiplier: rejected }));
      expect(loadSettings().simMultiplier).toBe(DEFAULT_SIM_STATUS.multiplier);
    }
  });

  it('keeps every multiplier the speed strip does offer', () => {
    for (const rate of SIM_MULTIPLIERS) {
      localStorage.setItem(STORAGE_KEY, JSON.stringify({ simMultiplier: rate }));
      expect(loadSettings().simMultiplier).toBe(rate);
    }
  });

  it('rejects an alliance that is not one of the two', () => {
    // Alliance sets the zero of every heading the robot reports. Java's enum serializes lowercase,
    // so "RED" is a value from somewhere else, and taking it would start a field-centric OpMode a
    // quarter turn out while looking entirely normal on screen.
    for (const rejected of ['RED', 'Blue', 'green', '', 0, null]) {
      localStorage.setItem(STORAGE_KEY, JSON.stringify({ alliance: rejected }));
      expect(loadSettings().alliance).toBe(DEFAULT_SIM_STATUS.alliance);
    }
  });

  it('keeps a stored blue alliance, which is the one the default would hide', () => {
    localStorage.setItem(STORAGE_KEY, JSON.stringify({ alliance: 'blue' }));
    expect(loadSettings().alliance).toBe('blue');
  });

  it('keeps the good fields of a blob whose other fields are junk', () => {
    // Partial rejection, not all-or-nothing: one bad field should not throw away the alliance the
    // team set up on.
    localStorage.setItem(
      STORAGE_KEY,
      JSON.stringify({ keyboardGamepad: 'yes', alliance: 'blue', simMultiplier: 7 }),
    );
    expect(loadSettings()).toEqual({
      keyboardGamepad: false,
      alliance: 'blue',
      simMultiplier: DEFAULT_SIM_STATUS.multiplier,
    });
  });
});
