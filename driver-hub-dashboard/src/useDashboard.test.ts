import { describe, expect, it } from 'vitest';
import { socketUrl } from './useDashboard';

/**
 * Where the page looks for the simulation.
 *
 * The bug this pins is not a parsing mistake, it is an address the page could not be talked out
 * of: the URL used to carry a hardcoded `8765`, so a simulation started anywhere else was
 * unreachable and the page silently connected to whatever occupied that port instead. Deriving it
 * from the origin is what makes a mismatch unrepresentable, so what these assert is that nothing
 * but the origin is ever used.
 */
describe('socketUrl', () => {
  it('follows the port the page was served from', () => {
    expect(socketUrl({ protocol: 'http:', host: '127.0.0.1:5183' })).toBe(
      'ws://127.0.0.1:5183/ws',
    );
    // A second dev server on a second port is reached without configuring anything, which is the
    // case that used to be impossible.
    expect(socketUrl({ protocol: 'http:', host: '127.0.0.1:6001' })).toBe(
      'ws://127.0.0.1:6001/ws',
    );
  });

  it('follows the host the page was served from, so a dashboard on the bench is reachable', () => {
    expect(socketUrl({ protocol: 'http:', host: 'driver-hub.local:5183' })).toBe(
      'ws://driver-hub.local:5183/ws',
    );
  });

  it('upgrades to wss when the page is secure', () => {
    // A ws:// socket from an https:// page is blocked as mixed content, so getting this wrong is a
    // dashboard that cannot connect at all rather than one that connects insecurely.
    expect(socketUrl({ protocol: 'https:', host: 'driver-hub.local' })).toBe(
      'wss://driver-hub.local/ws',
    );
  });
});
