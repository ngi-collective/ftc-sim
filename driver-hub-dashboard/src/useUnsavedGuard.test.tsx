// @vitest-environment happy-dom
import { act, type ReactElement } from 'react';
import { createRoot } from 'react-dom/client';
import { afterEach, describe, expect, it } from 'vitest';
import { useUnsavedGuard } from './useUnsavedGuard';

/**
 * Rendered for real, because what is worth testing is the effect's add/remove pair following a
 * changing flag — the handler itself is one line. Calling the hook as a plain function would
 * exercise nothing.
 */
declare global {
  /** React reads this to decide whether {@code act} is allowed; it is not part of lib.dom. */
  var IS_REACT_ACT_ENVIRONMENT: boolean;
}

globalThis.IS_REACT_ACT_ENVIRONMENT = true;

const host = document.createElement('div');
document.body.append(host);
const root = createRoot(host);

afterEach(() => {
  act(() => root.render(null));
});

function render(element: ReactElement | null): void {
  act(() => root.render(element));
}

/** True when something on the page would stop the browser navigating away. */
function navigationIsBlocked(): boolean {
  const leaving = new Event('beforeunload', { cancelable: true });
  window.dispatchEvent(leaving);
  return leaving.defaultPrevented;
}

function Guarded({ unsaved }: { unsaved: boolean }): null {
  useUnsavedGuard(unsaved);
  return null;
}

describe('guarding a page with an unsaved mount on it', () => {
  it('lets the page go when there is nothing unsaved', () => {
    render(<Guarded unsaved={false} />);
    expect(navigationIsBlocked()).toBe(false);
  });

  it('asks before leaving while a mount is unsaved, and stops asking once it is not', () => {
    // Saving is a separate action from editing, so a browser can be holding an aimed camera that
    // no file describes; losing it to a reload means finding the angle again by eye.
    render(<Guarded unsaved />);
    expect(navigationIsBlocked()).toBe(true);

    // Removed rather than left installed and deciding to allow: a beforeunload listener keeps the
    // page out of the back/forward cache for as long as it exists.
    render(<Guarded unsaved={false} />);
    expect(navigationIsBlocked()).toBe(false);
  });
});
