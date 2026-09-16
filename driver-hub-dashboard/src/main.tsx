import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { App } from './App';
import './index.css';
import { startProbe } from './probe';

// No-ops unless the page was opened with `?probe`; see docs/agents/dashboard-probes.md.
startProbe();

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <App />
  </StrictMode>,
);
