import { defineConfig } from 'vitest/config';
import react from '@vitejs/plugin-react';

export default defineConfig({
  plugins: [react()],
  // Bind the IPv4 loopback explicitly. Vite's default host is the name `localhost`, which on a
  // machine whose hosts file answers with ::1 first binds IPv6 only -- and then useDashboard's
  // `ws://${location.hostname}:8765` becomes `ws://[::1]:8765`, which DashboardServer can never
  // accept: it binds a concrete 127.0.0.1 on purpose (see DashboardServer's constructor for what a
  // dual-stack bind does to Java-WebSocket on macOS). The page would load and never connect.
  // Browsers still resolve http://localhost:5183 here, falling back to IPv4 when ::1 refuses.
  server: { host: '127.0.0.1', port: 5183 },
  // Node by default: the logic worth testing is coordinate maths, wire parsing and axis mapping,
  // none of which wants a DOM. A file that does need one asks for it with
  // `// @vitest-environment happy-dom` at the top.
  test: { environment: 'node', include: ['src/**/*.test.ts', 'src/**/*.test.tsx'] },
});
