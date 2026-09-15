/**
 * One message per frame, and never a dropped last one.
 *
 * <p>A slider being dragged fires a pointer event per mouse report — 125 Hz on an ordinary mouse,
 * a thousand on a gaming one — and each one is a socket write the server parses, applies and
 * broadcasts back. Sending them all buys nothing: the browser cannot draw more than one frame per
 * frame, so every message but the last of each frame describes a mount nobody ever saw.</p>
 *
 * <p>What makes this different from a throttle is the trailing value. A throttle that drops
 * everything inside its window drops the value the pointer stopped on, which is the one value the
 * user chose: the camera would come to rest a few degrees away from the slider. Here the newest
 * value is held and sent by the scheduled flush, so the last drag position always goes out.</p>
 */

/**
 * Wraps {@code send} so it runs at most once per scheduled tick, always with the newest value.
 *
 * @param schedule defers one flush; {@code requestAnimationFrame} in the browser, a queue a test
 *     drains by hand. Called only when a flush is not already pending, so the scheduler never
 *     accumulates callbacks either.
 */
export function createCoalescedSender<T>(options: {
  send: (value: T) => void;
  schedule: (flush: () => void) => void;
}): (value: T) => void {
  // Boxed rather than a bare T: a pending value is not the same thing as no pending value, and T
  // is free to be a type whose values include null.
  let pending: { value: T } | null = null;

  const flush = () => {
    const held = pending;
    pending = null;
    if (held) options.send(held.value);
  };

  return (value: T) => {
    const idle = pending === null;
    pending = { value };
    if (idle) options.schedule(flush);
  };
}
