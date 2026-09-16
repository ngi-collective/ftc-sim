// Does processing frames retain anything? Seven seconds for an answer.
//
// Runs the client's own message pipeline over a million frames with the socket, React and three.js
// left out, forcing a full collection between samples so what is left is retention rather than
// garbage waiting to be collected. A browser cannot answer this: its heap sawtooths by 100 MB and
// buries a few bytes per frame in the noise.
//
//   bun tools/retention.mjs
//
// Reads "0.0 bytes retained per frame" when the pipeline is clean. See
// docs/agents/dashboard-probes.md.
const HERE = import.meta.dir;
const SRC = `${HERE}/../src`;
const FIXTURES = `${HERE}/../../protocol-fixtures`;
const BATCHES = Number(process.env.BATCHES ?? 10);
const BATCH = Number(process.env.BATCH ?? 100_000);

const { applyMessage, createThrottledPoseSink } = await import(`${SRC}/messages.ts`);
const { createBodyBuffer } = await import(`${SRC}/scene/bodies.ts`);
const { parseEnvelope } = await import(`${SRC}/protocol.ts`);

const raw = {};
for (const name of ['sim-pose', 'sim-bodies', 'telemetry-frame', 'device-state']) {
  raw[name] = JSON.parse(await Bun.file(`${FIXTURES}/${name}.json`).text());
}

// Stands in for React's setState: keeps only the newest value, which is what useState does.
const latest = {};
const keep = (key) => (value) =>
  (latest[key] = typeof value === 'function' ? value(latest[key]) : value);

const bodyBuffer = createBodyBuffer();
const poseListeners = new Set();
poseListeners.add((pose) => (latest.livePose = pose));

const sinks = {
  setError: keep('error'),
  setOpModes: keep('opModes'),
  setStatus: keep('status'),
  setTelemetry: keep('telemetry'),
  setDevices: keep('devices'),
  setLayouts: keep('layouts'),
  setLayoutDirectory: keep('layoutDirectory'),
  setLoadedLayout: keep('loadedLayout'),
  setSavedLayout: keep('savedLayout'),
  setSimConfig: keep('simConfig'),
  setSimScene: keep('simScene'),
  setCameraStream: keep('cameraStream'),
  setCameraMount: keep('cameraMount'),
  setSavedCameraMount: keep('savedCameraMount'),
  setSimStatus: keep('simStatus'),
  pose: createThrottledPoseSink({
    publish: (next) => {
      for (const listener of poseListeners) listener(next);
    },
    commit: keep('pose'),
    now: () => Date.now(),
  }),
  bodies: (frame) => bodyBuffer.accept(frame),
};

const frame = (kind, index) => {
  const stamp = 1789485640144 + index * 20;
  if (kind === 'sim-pose') {
    return JSON.stringify({
      namespace: 'sim',
      type: 'pose',
      payload: { ...raw['sim-pose'].payload, timestampMillis: stamp, x: Math.sin(index / 100) },
    });
  }
  if (kind === 'sim-bodies') {
    return JSON.stringify({
      namespace: 'sim',
      type: 'bodies',
      payload: {
        ...raw['sim-bodies'].payload,
        timestampMillis: stamp,
        bodies: raw['sim-bodies'].payload.bodies.map((body, slot) => ({
          ...body,
          x: body.x + Math.sin(index / 50 + slot) * 0.2,
        })),
      },
    });
  }
  if (kind === 'telemetry-frame') {
    return JSON.stringify({
      namespace: 'telemetry',
      type: 'frame',
      payload: {
        timestamp: stamp,
        lines: raw['telemetry-frame'].payload.lines.map((line, slot) =>
          slot === 0 ? `f ${index}` : line,
        ),
      },
    });
  }
  return JSON.stringify({
    namespace: 'device',
    type: 'state',
    payload: {
      devices: raw['device-state'].payload.devices.map((device) => ({
        ...device,
        position: index,
      })),
    },
  });
};

const settled = () => {
  Bun.gc(true);
  Bun.gc(true);
  return process.memoryUsage().heapUsed;
};

let index = 0;
let sampled = 0;
const baseline = settled();
const rows = [];
for (let batch = 0; batch < BATCHES; batch++) {
  for (let inBatch = 0; inBatch < BATCH; inBatch++) {
    index++;
    applyMessage(parseEnvelope(frame('sim-pose', index)), sinks);
    applyMessage(parseEnvelope(frame('sim-bodies', index)), sinks);
    if (index % 5 === 0) applyMessage(parseEnvelope(frame('device-state', index)), sinks);
    if (index % 12 === 0) applyMessage(parseEnvelope(frame('telemetry-frame', index)), sinks);
    // The sampling path allocates a Map and an array every time the 3D view draws, so exercise it.
    sampled += bodyBuffer.sample(1789485640144 + index * 20).length;
  }
  const heap = settled();
  rows.push({ frames: index, heap });
  console.log(
    `frames=${String(index).padStart(9)} heap=${(heap / 1e6).toFixed(2)}MB ` +
      `grown=${((heap - baseline) / 1e6).toFixed(2)}MB poseListeners=${poseListeners.size} sampled=${sampled}`,
  );
}

const last = rows[rows.length - 1];
console.log(
  `\nbaseline ${(baseline / 1e6).toFixed(2)}MB -> ${(last.heap / 1e6).toFixed(2)}MB after ` +
    `${last.frames} frames (${((last.heap - baseline) / last.frames).toFixed(1)} bytes retained per frame)`,
);
