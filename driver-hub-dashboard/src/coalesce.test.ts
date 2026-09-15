import { describe, expect, it } from 'vitest';
import { createCoalescedSender } from './coalesce';

/**
 * A hand-drained scheduler stands in for {@code requestAnimationFrame}: the behaviour worth testing
 * is entirely about which values survive a frame boundary, and a test that waited for real frames
 * would be a test that sometimes observed a different number of them.
 */
function frames() {
  const queued: (() => void)[] = [];
  return {
    schedule: (flush: () => void) => queued.push(flush),
    /** One animation frame: runs what was queued when it started, not what that queues in turn. */
    tick: () => {
      const due = queued.splice(0, queued.length);
      for (const flush of due) flush();
    },
    pending: () => queued.length,
  };
}

describe('coalescing a dragged value', () => {
  it('sends the newest value once a frame however fast the pointer reports', () => {
    const clock = frames();
    const sent: number[] = [];
    const send = createCoalescedSender<number>({ send: (value) => sent.push(value), schedule: clock.schedule });

    for (const degrees of [1, 2, 3, 4, 5]) send(degrees);
    // Still nothing on the wire, and only one flush owed: a pointer that reports a thousand times
    // a second must not leave a thousand callbacks or a thousand socket writes behind it.
    expect(sent).toEqual([]);
    expect(clock.pending()).toBe(1);

    clock.tick();
    expect(sent).toEqual([5]);
  });

  it('sends the value the drag stopped on, even though the frame before it already went out', () => {
    // The failure this exists for: a plain throttle drops everything inside its window, which is
    // exactly the value the pointer came to rest on. The camera would then sit a few degrees off
    // the slider, and the difference between that and a working editor is invisible.
    const clock = frames();
    const sent: number[] = [];
    const send = createCoalescedSender<number>({ send: (value) => sent.push(value), schedule: clock.schedule });

    send(10);
    clock.tick();
    send(20);
    send(35);
    clock.tick();

    expect(sent).toEqual([10, 35]);
    // And nothing is still owed once the pointer is up, so a later frame sends no stale mount.
    expect(clock.pending()).toBe(0);
    clock.tick();
    expect(sent).toEqual([10, 35]);
  });
});
