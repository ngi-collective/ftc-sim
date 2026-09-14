import { defineConfig } from 'vitest/config';
import react from '@vitejs/plugin-react';

export default defineConfig({
  plugins: [react()],
  server: { port: 5183 },
  // Node by default: the logic worth testing is coordinate maths, wire parsing and axis mapping,
  // none of which wants a DOM. A file that does need one asks for it with
  // `// @vitest-environment happy-dom` at the top.
  test: { environment: 'node', include: ['src/**/*.test.ts', 'src/**/*.test.tsx'] },
});
