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
   part of what it is measuring. Reproduce anything it suggests with nothing attached. This is why
   `mise run dashboard` serves a production build and `--dev` is the opt-in: measured at 6× CPU
   throttle, dev ran at 27–30 fps with a 100 ms p95 where production held 60 fps at 16.7 ms. If a
   session is choppy, check which of the two is being served **before** looking at the scene.

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

## What the 3D view costs

Measured on an M1 Max, Chrome for Testing, `tipping-hive` loaded, a 1214×725 canvas, production
build. Absolute numbers scale with the GPU; the *ratios* are the useful part, and an older GPU at
5K makes every fill-rate row several times worse.

| | before | now |
| --- | --- | --- |
| draw calls per frame (`info.render.calls`) | 283 | 140 |
| meshes in the scene graph | 214 | 109 |
| distinct materials | 126 | 81 |
| triangles per frame | 12,470 | 12,468 |
| GPU per frame, dpr 2 | 2.19 ms | 1.54 ms |
| GPU per frame, as shipped | 2.19 ms (dpr 2) | 0.97 ms (dpr 1.5) |
| CPU inside `renderer.render` | 0.89 ms | 0.56 ms |

**Twelve thousand triangles is nothing and never was the problem.** This scene is bound by the
number of things drawn and the number of pixels shaded, in that order, so the levers are draw
calls and resolution — never tessellation.

**Resolution is the biggest single lever, and it is one line.** Fill scales as you would expect:
0.57 ms at dpr 1, 1.21 at 1.5, 2.19 at 2, 5.69 at 4 (0.88 / 3.5 / 14 / 32 Mpx, 4× MSAA). R3F's
default is the display's own ratio, so a 5K panel asks for 14.7 Mpx of MSAA'd PBR per frame; the
`<Canvas dpr={[1, 1.5]}>` cap in `RobotScene.tsx` is what keeps that honest.

**Static geometry is merged, not instanced.** The floor is two buffers rather than 36 tile meshes,
each structure's solids are one buffer per colour rather than ~70 meshes, and a wheel's seven
plain tread blocks and three spokes are one buffer each. `Field.floor` and
`FieldContents.useStructureMeshes` bake the transforms in; `parts.tsx` bakes the wheel at module
load. The merge is exact — checked against the per-mesh poses vertex by vertex, worst deviation
0 m — and it halves the shadow pass along with the camera pass, because that pass draws the same
meshes again.

**A `<ringGeometry>` whose `args` change is a geometry rebuilt and re-uploaded.** The motor power
arc was doing that for every motor on every committed frame. It now draws a prefix of one static
ring with `setDrawRange`, which is the same picture without the churn — watch for the same shape
of bug anywhere a geometry's `args` carry live data.

### Levers that were measured and left alone

- **Shadow map size.** 2048 → 1024 changed nothing (2.18 vs 2.19 ms): the shadow pass is bound by
  how many meshes it redraws, not by texels. Cutting casters is what moves it — all casters
  2.22 ms, field furniture not casting 2.07, nothing casting 1.60 — and merging the furniture took
  the same ground without giving up the shadows.
- **The floor receiving shadows** costs 0.35 ms of fragment work. Kept: an unshadowed floor is
  exactly the depth cue a driver reads the robot's position from.
- **Swapping `MeshStandardMaterial` for a cheaper model** is worth about 0.4 ms at dpr 2 — a real
  number, and the whole look of the scene. Not taken.
- **`frameloop="demand"`** buys nothing during a session: the simulation streams a pose at 50 Hz,
  so almost every frame has something new in it. It would only help a tab left open on a stopped
  robot, at the risk of a view that silently stops updating.
- **The 36 drei `<Html>` labels** are DOM nodes reprojected every frame. Hiding them changed no
  measurable number at 6× CPU throttle. Suspect them again only if the label count grows.
