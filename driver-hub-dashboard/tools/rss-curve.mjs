// Renderer memory over time, read from outside the browser.
//
// The JS heap is not where a dashboard's memory goes: a renderer was once observed at 2.6 GB with
// its JS heap reporting 24 MB, so `performance.memory` and every CDP heap counter said "no leak"
// while the process was drowning. Decoded images, canvas backing stores and GL resources all live
// outside the heap, and `ps` is the only instrument that sees them.
//
// No debugger is attached, deliberately: see docs/agents/dashboard-probes.md.
//
//   bun tools/rss-curve.mjs                 # 30 minutes against the dev server
//   MINUTES=5 URL=http://127.0.0.1:4173/ bun tools/rss-curve.mjs
const CHROMIUM =
  process.env.CHROMIUM ??
  '/Users/jsoverson/Library/Caches/ms-playwright/chromium-1223/chrome-mac-arm64/Google Chrome for Testing.app/Contents/MacOS/Google Chrome for Testing';
const URL_UNDER_TEST = process.env.URL ?? 'http://127.0.0.1:5183/';
const MINUTES = Number(process.env.MINUTES ?? 30);
const EVERY_SECONDS = Number(process.env.EVERY ?? 45);
const PROFILE = process.env.PROFILE ?? '/tmp/rss-curve-profile';
const OUT = process.env.OUT ?? '/tmp/rss-curve.json';

await Bun.$`rm -rf ${PROFILE}`.quiet();
const proc = Bun.spawn(
  [
    CHROMIUM,
    '--headless=new',
    '--enable-unsafe-swiftshader',
    '--use-angle=swiftshader',
    '--window-size=1600,1000',
    `--user-data-dir=${PROFILE}`,
    URL_UNDER_TEST,
  ],
  { stdout: 'pipe', stderr: 'pipe' },
);
await Bun.sleep(8000);

const sample = async () => {
  const text = await Bun.$`ps -Ao pid,rss,command`.text();
  let renderer = 0;
  let gpu = 0;
  let total = 0;
  for (const line of text.split('\n')) {
    // The profile path is what distinguishes this browser's processes from any other Chromium.
    if (!line.includes(PROFILE)) continue;
    const rss = Number(line.trim().split(/\s+/)[1]);
    if (!Number.isFinite(rss)) continue;
    total += rss;
    if (line.includes('--type=renderer')) renderer = Math.max(renderer, rss);
    if (line.includes('--type=gpu-process')) gpu = Math.max(gpu, rss);
  }
  return {
    rendererMB: +(renderer / 1024).toFixed(1),
    gpuMB: +(gpu / 1024).toFixed(1),
    totalMB: +(total / 1024).toFixed(1),
  };
};

console.log(`${URL_UNDER_TEST} for ${MINUTES} min, software GL, no debugger attached`);
console.log('minutes   renderer     gpu    total');
const history = [];
const started = Date.now();
while ((Date.now() - started) / 60000 < MINUTES) {
  const row = await sample();
  const minutes = +((Date.now() - started) / 60000).toFixed(1);
  history.push({ minutes, ...row });
  console.log(
    `${String(minutes).padStart(7)}   ${String(row.rendererMB).padStart(8)} ${String(row.gpuMB).padStart(7)} ${String(row.totalMB).padStart(8)}`,
  );
  await Bun.write(OUT, JSON.stringify(history, null, 2));
  await Bun.sleep(EVERY_SECONDS * 1000);
}

const first = history[0];
const last = history[history.length - 1];
console.log(
  `\nover ${last.minutes} min: renderer ${first.rendererMB} -> ${last.rendererMB} MB ` +
    `(${((last.rendererMB - first.rendererMB) / Math.max(last.minutes, 0.1)).toFixed(1)} MB/min), ` +
    `gpu ${first.gpuMB} -> ${last.gpuMB} MB, total ${first.totalMB} -> ${last.totalMB} MB`,
);
console.log(`rows: ${OUT}`);
proc.kill();
