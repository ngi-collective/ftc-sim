# Dashboard probes

Three instruments for "the dashboard has gone slow". They exist because the obvious tools answer
confidently and wrongly: a renderer was once observed at **2.6 GB while its JS heap read 24 MB**, so
every heap counter reported a clean bill of health, and a CPU profile taken through DevTools blamed
a cost that only exists *because* DevTools was attached.

Each probe answers one question. Reach for the one that matches the symptom.

## Is the page congested? — `src/probe.ts`

In-page, opt-in, posts to a collector every 15 s. Needs no debugger, which is the point.

```bash
cd driver-hub-dashboard && bun tools/probe-collector.mjs   # :8770, supervise it (hub start)
open 'http://127.0.0.1:5183/?probe'                        # ?probe=<url> for another collector
curl -s 127.0.0.1:8770/rows | jq -r '.[] | "\(.seconds)s raf=\(.rafFrames) drift=\(.driftP95)ms measures=\(.measures) heap=\(.heapMB)MB"'
```

Reading a row:

| field | healthy | what it means |
| --- | --- | --- |
| `rafFrames` | +900 per 15 s | 60 fps. **Zero invalidates the whole row** — see the compositing trap |
| `driftP95` | 1–2 ms, flat | main-thread congestion; defined even with nothing being drawn |
| `longTasks` | constant after startup | a rising count is the choppiness itself |
| `measures` | 0 in a production build | the dev build accumulates these without bound (~1,400/s measured); see trap 1 |
| `workloadMillis` | ~2.5 ms | fixed arithmetic, timed; rises when the thread is starved |
| `heapMB` | sawtooths | JS only, and JS is usually not where the memory went |

Rows from concurrent pages interleave. Segment them on `seconds` decreasing.

## Where did the memory go? — `tools/rss-curve.mjs`

Renderer, GPU and total RSS from `ps`, sampled from outside the browser. The only instrument that
sees decoded images, canvas backing stores and GL resources.

```bash
bun tools/rss-curve.mjs                                   # 30 min against the dev server
MINUTES=5 URL=http://127.0.0.1:4173/ bun tools/rss-curve.mjs
```

A healthy fixed app settles: renderer climbing a couple of MB/min and **decelerating**, GPU flat.
Monotone growth into the GBs is the fault worth chasing. `CHROMIUM=` overrides the browser path.

## Does a frame retain anything? — `tools/retention.mjs`

A million frames through `applyMessage`, the pose sink and the body buffer, with `Bun.gc(true)`
between samples. Seven seconds, deterministic, and it settles per-frame retention in a way a
browser cannot — a browser's heap sawtooths by 100 MB and hides a few bytes per frame in the noise.

```bash
bun tools/retention.mjs      # "0.0 bytes retained per frame" when the pipeline is clean
```

## Traps, all of them measured

1. **The dev build accumulates User Timing entries without bound.** React's development build
   emits `performance.measure` per component per commit — ~1,400/s measured here, past 780,000
   entries in one session — and the production build emits **zero** (verified with no debugger, 75 s,
   `measures=0` throughout). A CPU profile taken through DevTools over that buffer attributes ~80%
   of the main thread to `clearMeasures`; treat that attribution as suspect, because a debugger is
   part of what it is measuring. Reproduce anything it suggests with nothing attached. For a long
   session — a practice run rather than UI work — serve the production build:
   `bun run build && bunx vite preview --port 4173`.

   Unresolved: some dev sessions held `measures` flat instead of climbing, and the trigger for the
   difference is not pinned down. Read the field, do not assume which way it will go.
2. **A headless tab that is not in front is never composited.** `requestAnimationFrame` stops,
   R3F's `ResizeObserver` never fires, its canvas stays at the default 300×150, the scene never
   mounts — and the page then reports flat, healthy numbers for an app doing a fraction of its
   work. Assert `rafFrames > 0` before believing a run. Reuse the tab the browser launched with;
   closing the last page leaves the window with no foreground tab. `Page.captureScreenshot` hanging
   is the unambiguous tell, and compositing can also stop mid-session when the display sleeps.
3. **JS heap is not renderer memory.** `JSHeapUsedSize`, `Nodes`, `JSEventListeners` and three.js's
   `renderer.info` counts can all be exactly flat while the renderer grows by gigabytes.
4. **Compress time along the right axis.** Raising the frame rate to reach an hour of session in
   minutes also saturates the main thread, which manufactures the choppiness being investigated.
   Check `driftP95` against a 1× control, or the experiment measures its own load.

## What three.js is holding

Geometry, texture and draw-call counts need the renderer, which no probe holds deliberately —
production code should not carry a debug handle. Add four lines inside a component under `<Canvas>`,
and delete them with the diagnosis:

```ts
const gl = useThree((state) => state.gl);
useEffect(() => {
  (window as unknown as { __gl?: unknown }).__gl = gl;
}, [gl]);
// then: __gl.info.memory.geometries / .textures / __gl.info.render.calls / __gl.shadowMap.type
```
