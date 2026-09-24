import { defineConfig } from 'vitest/config';
import react from '@vitejs/plugin-react';

/**
 * Where the simulation's WebSocket is, for the proxy below.
 *
 * Set by `tools/dashboard.sh`, which is the one place the port is decided: it starts the JVM with
 * this same number and this dev server pointed at it, so the two cannot disagree. Defaulted here
 * only so that `bun run dev` on its own still reaches a dashboard on the usual port.
 */
const simulationPort = Number(process.env.DASHBOARD_PORT ?? 8765);

export default defineConfig({
  plugins: [react()],
  server: {
    // Bind the IPv4 loopback explicitly, and proxy the socket to a concrete 127.0.0.1 too:
    // `DashboardServer` binds one on purpose (see its constructor for what a dual-stack bind does
    // to Java-WebSocket on macOS), so a target of `localhost` on a machine whose hosts file
    // answers `::1` first is a connection it can never accept.
    // strictPort because the alternative is worse than a failed start: Vite's default is to take
    // the next free port and say so in one line of scrollback, so a stale server keeps 5183 and
    // the URL everyone types quietly serves someone else's build.
    host: '127.0.0.1',
    port: 5183,
    strictPort: true,
    // The page connects to its own origin at `/ws` and this carries it to the JVM. That is what
    // makes the pair impossible to misconfigure from the browser's side: there is no port for the
    // UI to get wrong, because it never names one. `/ws` cannot collide with Vite's own HMR
    // socket, which lives at the root under the `vite-hmr` subprotocol.
    proxy: { '/ws': { target: `ws://127.0.0.1:${simulationPort}`, ws: true } },
  },
  // `vite preview` serves the production build, which is what a long practice session wants (the
  // dev build's React instrumentation is half its main thread). It does not inherit `server`, so
  // without this the preview is a dashboard that can never reach a simulation: same page, same
  // `/ws`, nothing listening.
  preview: {
    host: '127.0.0.1',
    proxy: { '/ws': { target: `ws://127.0.0.1:${simulationPort}`, ws: true } },
  },
  // Node by default: the logic worth testing is coordinate maths, wire parsing and axis mapping,
  // none of which wants a DOM. A file that does need one asks for it with
  // `// @vitest-environment happy-dom` at the top.
  test: { environment: 'node', include: ['src/**/*.test.ts', 'src/**/*.test.tsx'] },
});
