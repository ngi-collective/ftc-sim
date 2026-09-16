// Receives what `src/probe.ts` posts, and serves it back at /rows.
//
// Exists so a run needs no debugger attached: React's development build emits well over a thousand
// User Timing entries a second while a DevTools client is listening and none when one is not, so
// watching the page through DevTools changes the thing being watched. The page posts here instead.
//
//   bun tools/probe-collector.mjs      then open the UI with ?probe
//   curl -s 127.0.0.1:8770/rows | jq
//
// See docs/agents/dashboard-probes.md.
const PORT = Number(process.env.PORT ?? 8770);
const rows = [];

Bun.serve({
  port: PORT,
  hostname: '127.0.0.1',
  async fetch(request) {
    const url = new URL(request.url);
    if (url.pathname === '/rows') {
      return new Response(JSON.stringify(rows, null, 2), {
        headers: { 'content-type': 'application/json', 'access-control-allow-origin': '*' },
      });
    }
    if (request.method === 'OPTIONS') {
      return new Response(null, {
        headers: {
          'access-control-allow-origin': '*',
          'access-control-allow-methods': 'POST, OPTIONS',
          'access-control-allow-headers': 'content-type',
        },
      });
    }
    if (request.method === 'POST') {
      const row = await request.json();
      rows.push(row);
      console.log(
        `[${row.label}] t=${String(row.seconds).padStart(4)}s measures=${String(row.measures).padStart(8)} ` +
          `driftP95=${String(row.driftP95).padStart(6)}ms driftMax=${String(row.driftMax).padStart(6)}ms ` +
          `longTasks=${String(row.longTasks).padStart(4)} longMs=${String(row.longTaskMillis).padStart(6)} ` +
          `workload=${String(row.workloadMillis).padStart(6)}ms frames=${row.rafFrames}`,
      );
      return new Response('ok', { headers: { 'access-control-allow-origin': '*' } });
    }
    return new Response('leak-collector', { headers: { 'access-control-allow-origin': '*' } });
  },
});

console.log(`leak-collector listening on 127.0.0.1:${PORT}`);
