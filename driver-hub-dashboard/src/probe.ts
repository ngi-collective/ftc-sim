/**
 * Self-reported page health, for diagnosing a dashboard that has gone slow.
 *
 * <p>Opt-in: nothing runs unless the page is opened with `?probe`. That is not shyness about the
 * cost — it is that the numbers are only meaningful when a collector is listening, and a dashboard
 * during a match should not be posting anywhere.</p>
 *
 * <p>The point of measuring from inside the page is that <em>attaching a debugger can change what
 * is being measured</em>: React's development build piles up User Timing entries without bound
 * (~1,400 a second here, where the production build emits none), and a CPU profile taken through
 * DevTools over that buffer blames most of the main thread on `clearMeasures`. Everything here is
 * therefore readable with no debugger attached.</p>
 *
 * <p>Nothing here needs the compositor either. Interval drift, long tasks and a fixed synthetic
 * workload all run on the main thread whether or not frames are being produced, which matters
 * because a headless tab that is not in front is never composited &mdash; and a page drawing
 * nothing looks wonderfully healthy on every other measure.</p>
 *
 * <p>See `docs/agents/dashboard-probes.md`.</p>
 */
const DEFAULT_COLLECTOR = 'http://127.0.0.1:8770/';
const REPORT_EVERY_MS = 15000;
const TICK_MS = 50;

/** Reports every {@link REPORT_EVERY_MS} to the collector, or does nothing at all. */
export function startProbe() {
  const requested = new URLSearchParams(location.search).get('probe');
  if (requested === null) return;
  const collector = requested === '' ? DEFAULT_COLLECTOR : requested;

  const started = performance.now();
  const drifts: number[] = [];
  let last = performance.now();
  let longTasks = 0;
  let longTaskMillis = 0;
  let rafFrames = 0;

  // Congestion without the compositor: an interval that arrives late is a main thread that was
  // busy, which is what a dropped frame is made of.
  setInterval(() => {
    const now = performance.now();
    drifts.push(now - last - TICK_MS);
    last = now;
  }, TICK_MS);

  if (typeof PerformanceObserver !== 'undefined') {
    try {
      new PerformanceObserver((list) => {
        for (const entry of list.getEntries()) {
          longTasks++;
          longTaskMillis += entry.duration;
        }
      }).observe({ entryTypes: ['longtask'] });
    } catch {
      // Not every build reports long tasks; drift carries the signal on its own.
    }
  }

  const beat = () => {
    rafFrames++;
    requestAnimationFrame(beat);
  };
  requestAnimationFrame(beat);

  // A fixed amount of arithmetic, timed. Unlike frame rate this is defined even when nothing is
  // being drawn, so a starved main thread still shows up.
  const workload = () => {
    const at = performance.now();
    let sum = 0;
    for (let index = 0; index < 2_000_000; index++) sum += Math.sqrt(index % 97);
    return { millis: performance.now() - at, sum };
  };

  setInterval(() => {
    const sorted = [...drifts].sort((a, b) => a - b);
    const measured = workload();
    const row = {
      label: import.meta.env.MODE,
      seconds: Math.round((performance.now() - started) / 1000),
      // Grows by ~1,400/s while a DevTools client is attached and not at all otherwise, so this
      // doubles as the check on whether a run is contaminated by its own observer.
      measures: performance.getEntriesByType('measure').length,
      driftP95: Math.round(sorted[Math.floor(sorted.length * 0.95)] ?? 0),
      driftMax: Math.round(sorted[sorted.length - 1] ?? 0),
      driftSamples: sorted.length,
      longTasks,
      longTaskMillis: Math.round(longTaskMillis),
      workloadMillis: +measured.millis.toFixed(1),
      // 900 per 15 s is 60 fps. Zero means the tab is not being composited, which invalidates
      // every other number in the row rather than being good news.
      rafFrames,
      heapMB:
        'memory' in performance
          ? Math.round(
              (performance as unknown as { memory: { usedJSHeapSize: number } }).memory
                .usedJSHeapSize / 1e6,
            )
          : -1,
    };
    drifts.length = 0;
    void fetch(collector, {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify(row),
    }).catch(() => undefined);
  }, REPORT_EVERY_MS);
}
