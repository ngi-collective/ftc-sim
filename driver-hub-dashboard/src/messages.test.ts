import { describe, expect, it } from 'vitest';
import cameraStreamFrame from '../../protocol-fixtures/camera-stream.json';
import deviceErrorFrame from '../../protocol-fixtures/device-error.json';
import deviceStateFrame from '../../protocol-fixtures/device-state.json';
import handshake from '../../protocol-fixtures/handshake.json';
import layoutListFrame from '../../protocol-fixtures/layout-list.json';
import opModeListFrame from '../../protocol-fixtures/opmode-list.json';
import opModeStatusFrame from '../../protocol-fixtures/opmode-status.json';
import simCameraFrame from '../../protocol-fixtures/sim-camera.json';
import simCameraSavedFrame from '../../protocol-fixtures/sim-camera-saved.json';
import simConfigFrame from '../../protocol-fixtures/sim-config.json';
import simPoseFrame from '../../protocol-fixtures/sim-pose.json';
import simSceneFrame from '../../protocol-fixtures/sim-scene.json';
import simScenariosFrame from '../../protocol-fixtures/sim-scenarios.json';
import simScoreFrame from '../../protocol-fixtures/sim-score.json';
import simStatusFrame from '../../protocol-fixtures/sim-status.json';
import telemetryFrame from '../../protocol-fixtures/telemetry-frame.json';
import unknownErrorFrame from '../../protocol-fixtures/unknown-error.json';
import {
  applyMessage,
  createThrottledPoseSink,
  POSE_COMMIT_INTERVAL_MS,
  type MessageSinks,
} from './messages';
import { type Envelope, type SimPose } from './protocol';

/**
 * What a frame does to the dashboard, with the socket taken out.
 *
 * <p>Frames are the captured ones wherever the behaviour depends on their contents, and hand-built
 * only where the point is a frame no real server sends — a payload missing its wrapper key, a
 * namespace from a version that does not exist yet.</p>
 */

/** Every sink, recording what it was handed. */
function recorder() {
  const calls: Record<string, unknown[]> = {};
  const record =
    (name: string) =>
    (value: unknown): void => {
      (calls[name] ??= []).push(value);
    };
  const sinks: MessageSinks = {
    setError: record('setError'),
    setOpModes: record('setOpModes'),
    setStatus: record('setStatus'),
    setTelemetry: record('setTelemetry'),
    setDevices: record('setDevices'),
    setLayouts: record('setLayouts'),
    setLayoutDirectory: record('setLayoutDirectory'),
    setLoadedLayout: record('setLoadedLayout'),
    setSavedLayout: record('setSavedLayout'),
    setSimConfig: record('setSimConfig'),
    setSimScene: record('setSimScene'),
    setCameraStream: record('setCameraStream'),
    setSimStatus: record('setSimStatus'),
    setSimScore: record('setSimScore'),
    setSimScenarios: record('setSimScenarios'),
    setCameraMount: record('setCameraMount'),
    setSavedCameraMount: record('setSavedCameraMount'),
    pose: { publish: record('publish'), commit: record('commit') },
    bodies: record('bodies'),
  };
  return { sinks, calls };
}

/** A frame as it comes off the wire, including the namespaces the union does not name. */
function frame(namespace: string, type: string, payload: unknown): Envelope {
  return { namespace, type, payload } as Envelope;
}

describe('applyMessage', () => {
  it('routes the camera stream frame to the camera sink', () => {
    // The frame that exposed the drift: sent once on connect, so a dispatch that misses it leaves
    // the camera panel saying "no camera" for the rest of the session.
    const { sinks, calls } = recorder();
    applyMessage(cameraStreamFrame as Envelope, sinks);
    expect(calls.setCameraStream).toEqual([cameraStreamFrame.payload]);
  });

  it('delivers every frame of the connect burst to a sink', () => {
    // Nothing the server sends a fresh browser may land nowhere. Anchored on the captured
    // handshake, so a new frame in DashboardServer.onOpen fails here rather than showing up as a
    // panel that is mysteriously empty until something else happens to broadcast.
    const captured: Record<string, Envelope> = {
      'opmode/list': opModeListFrame as Envelope,
      'opmode/status': opModeStatusFrame as Envelope,
      'sim/status': simStatusFrame as Envelope,
      'sim/config': simConfigFrame as Envelope,
      'sim/scene': simSceneFrame as Envelope,
      'sim/score': simScoreFrame as Envelope,
      'sim/scenarios': simScenariosFrame as Envelope,
      'camera/stream': cameraStreamFrame as Envelope,
      'sim/pose': simPoseFrame as Envelope,
      'sim/camera': simCameraFrame as Envelope,
      'device/state': deviceStateFrame as Envelope,
    };
    for (const name of handshake.observed) {
      const envelope = captured[name];
      expect(envelope, `no fixture for ${name}`).toBeDefined();
      const { sinks, calls } = recorder();
      applyMessage(envelope, sinks);
      expect(Object.keys(calls), `${name} reached no sink`).not.toHaveLength(0);
    }
  });

  it('routes each captured frame to its own sink and no other', () => {
    const routes: [Envelope, string][] = [
      [opModeListFrame as Envelope, 'setOpModes'],
      [opModeStatusFrame as Envelope, 'setStatus'],
      [telemetryFrame as Envelope, 'setTelemetry'],
      [deviceStateFrame as Envelope, 'setDevices'],
      [simConfigFrame as Envelope, 'setSimConfig'],
      [simSceneFrame as Envelope, 'setSimScene'],
      [simStatusFrame as Envelope, 'setSimStatus'],
      [simScoreFrame as Envelope, 'setSimScore'],
      [simScenariosFrame as Envelope, 'setSimScenarios'],
      [cameraStreamFrame as Envelope, 'setCameraStream'],
      [simCameraFrame as Envelope, 'setCameraMount'],
      [simCameraSavedFrame as Envelope, 'setSavedCameraMount'],
    ];
    for (const [envelope, sink] of routes) {
      const { sinks, calls } = recorder();
      applyMessage(envelope, sinks);
      expect(Object.keys(calls), `${envelope.namespace}/${envelope.type}`).toEqual([sink]);
    }
  });

  it('ignores a namespace it has never heard of', () => {
    // A server one version ahead. Half-applying such a frame, or throwing on it, are both worse
    // than not drawing something the browser has no panel for anyway.
    const { sinks, calls } = recorder();
    applyMessage(frame('holonomic', 'state', { anything: 1 }), sinks);
    applyMessage(frame('sim', 'gravity', { g: 9.8 }), sinks);
    expect(calls).toEqual({});
  });

  it('surfaces an error from any namespace, including an unknown one', () => {
    // Errors are checked before the dispatch: the message is the one thing worth reading from a
    // frame this build otherwise cannot use.
    const { sinks, calls } = recorder();
    applyMessage(deviceErrorFrame as Envelope, sinks);
    applyMessage(unknownErrorFrame as Envelope, sinks);
    expect(calls.setError).toEqual([
      deviceErrorFrame.payload.message,
      unknownErrorFrame.payload.message,
    ]);
  });

  it('drops an error frame with no message rather than reporting undefined', () => {
    const { sinks, calls } = recorder();
    applyMessage(frame('opmode', 'error', {}), sinks);
    applyMessage(frame('opmode', 'error', null), sinks);
    expect(calls).toEqual({});
  });

  it('leaves the device list alone when the frame has no devices key', () => {
    // This used to reach setDevices(undefined), and the device rail maps over it on the next
    // render: a malformed frame took out the page instead of being skipped.
    const { sinks, calls } = recorder();
    applyMessage(frame('device', 'state', { device: 'FL' }), sinks);
    expect(calls.setDevices).toBeUndefined();
  });

  it('leaves the OpMode list alone when the frame has no opModes key', () => {
    const { sinks, calls } = recorder();
    applyMessage(frame('opmode', 'list', {}), sinks);
    expect(calls.setOpModes).toBeUndefined();
  });

  it('keeps the pose when an OpMode stops', () => {
    // The robot outlives the OpMode: poses keep arriving between runs, and clearing here would
    // make the field view empty every time a run ends even though the robot is still sitting there.
    const { sinks, calls } = recorder();
    applyMessage(simPoseFrame as Envelope, sinks);
    applyMessage(opModeStatusFrame as Envelope, sinks);
    expect(opModeStatusFrame.payload.state).toBe('STOPPED');
    expect(calls.setStatus).toEqual([opModeStatusFrame.payload]);
    expect(calls.commit).toEqual([simPoseFrame.payload]);
    expect(calls.publish).toEqual([simPoseFrame.payload]);
  });

  it('validates layout frames, which come off disk, and skips the bad ones', () => {
    const { sinks, calls } = recorder();
    applyMessage(layoutListFrame as Envelope, sinks);
    applyMessage(frame('layout', 'list', { layouts: 'vertical-shafts' }), sinks);
    applyMessage(frame('layout', 'data', { name: 'vertical-shafts' }), sinks);
    applyMessage(frame('layout', 'saved', { name: 'a', path: 7 }), sinks);
    expect(calls.setLayouts).toEqual([layoutListFrame.payload.layouts]);
    expect(calls.setLayoutDirectory).toEqual([layoutListFrame.payload.directory]);
    expect(calls.setLoadedLayout).toBeUndefined();
    expect(calls.setSavedLayout).toBeUndefined();
  });

  it('carries the whole score to the score sink, totals and CELLs alike', () => {
    // The strip shows the totals and names the CELLs in its tooltip, so a route that delivered a
    // reshaped object — the cells dropped, the totals swapped — would still render something, and
    // what it rendered would be a plausible-looking wrong score. Equality against the frame is the
    // only assertion that catches that.
    const { sinks, calls } = recorder();
    applyMessage(simScoreFrame as Envelope, sinks);
    expect(calls.setSimScore).toEqual([simScoreFrame.payload]);
  });

  it('drops a score frame with no cells rather than taking every view down with it', () => {
    // Unlike the sim frames beside it, this one is validated: the readout maps over `cells` in the
    // strip that all three views render, so a frame from a server that renamed the key would land
    // as `undefined.map` on the next render — the whole page, not the one number. A total that is
    // not a number is the same kind of thing more quietly: it reaches the strip and stays there
    // reading NaN until something else changes.
    const { sinks, calls } = recorder();
    applyMessage(frame('sim', 'score', { redPoints: 6, bluePoints: 0 }), sinks);
    applyMessage(frame('sim', 'score', { redPoints: 'six', bluePoints: 0, cells: [] }), sinks);
    applyMessage(frame('sim', 'score', null), sinks);
    expect(calls).toEqual({});
  });

  it('carries the whole scenario list to its sink, directory and active one alike', () => {
    // The picker is built from all three fields: it lists the names, selects the active one and
    // shows the directory in its tooltip. A route that delivered a reshaped object would still
    // render a menu, and the menu would be wrong about which field is on the table.
    const { sinks, calls } = recorder();
    applyMessage(simScenariosFrame as Envelope, sinks);
    expect(calls.setSimScenarios).toEqual([simScenariosFrame.payload]);
  });

  it('carries a null active through to the sink instead of dropping the frame', () => {
    // The default session: nothing staged, the robot's own field. The capture cannot show it —
    // it ran with a scenario loaded — and validation that read a null active as a malformed frame
    // would stop the picker appearing in exactly the sessions most people run.
    const { sinks, calls } = recorder();
    const payload = { ...simScenariosFrame.payload, active: null };
    applyMessage(frame('sim', 'scenarios', payload), sinks);
    expect(calls.setSimScenarios).toEqual([payload]);
  });

  it('drops a scenario frame that no longer says which one is loaded', () => {
    const { sinks, calls } = recorder();
    const { scenarios, directory } = simScenariosFrame.payload;
    applyMessage(frame('sim', 'scenarios', { scenarios, directory }), sinks);
    applyMessage(frame('sim', 'scenarios', null), sinks);
    expect(calls).toEqual({});
  });
});

describe('the pose sink', () => {
  /** A pose per frame at 50 Hz, as the server ticks them out. */
  function poses(count: number): Envelope[] {
    return Array.from({ length: count }, (_unused, index) =>
      frame('sim', 'pose', { ...simPoseFrame.payload, elapsedSeconds: index * 0.02 }),
    );
  }

  it('fans every pose out while committing no more than one per interval', () => {
    // The 50 Hz fast path and the React commit are deliberately different rates: listeners write a
    // transform in a render loop, while a commit re-renders the console log and the device rail
    // along with the canvas.
    const published: SimPose[] = [];
    const committed: SimPose[] = [];
    let clock = 1_000;
    const pose = createThrottledPoseSink({
      publish: (next) => published.push(next),
      commit: (next) => committed.push(next),
      now: () => clock,
    });
    const { sinks } = recorder();

    // One second of 50 Hz poses, advancing the clock 20 ms per frame.
    for (const envelope of poses(50)) {
      applyMessage(envelope, { ...sinks, pose });
      clock += 20;
    }

    expect(published).toHaveLength(50);
    // Frames land 20 ms apart against a 50 ms floor, so a commit has to skip two and take the
    // third: poses 0, 3, 6, ... of the 50, which is 17 re-renders in place of 50.
    const frameNumbers = committed.map((next) => Math.round(next.elapsedSeconds / 0.02));
    expect(frameNumbers).toEqual([0, 3, 6, 9, 12, 15, 18, 21, 24, 27, 30, 33, 36, 39, 42, 45, 48]);
  });

  it('commits the first pose of a connection without waiting for the interval', () => {
    // Whatever the clock happens to read on connect, the robot appears on the first pose rather
    // than an interval later.
    const committed: SimPose[] = [];
    const pose = createThrottledPoseSink({
      publish: () => {},
      commit: (next) => committed.push(next),
      now: () => 0,
    });
    pose.commit(simPoseFrame.payload);
    expect(committed).toEqual([simPoseFrame.payload]);
  });

  it('refuses a commit one millisecond inside the interval and allows one exactly on it', () => {
    const committed: SimPose[] = [];
    let clock = 0;
    const pose = createThrottledPoseSink({
      publish: () => {},
      commit: (next) => committed.push(next),
      now: () => clock,
    });
    pose.commit(simPoseFrame.payload);
    clock = POSE_COMMIT_INTERVAL_MS - 1;
    pose.commit(simPoseFrame.payload);
    expect(committed).toHaveLength(1);
    clock = POSE_COMMIT_INTERVAL_MS;
    pose.commit(simPoseFrame.payload);
    expect(committed).toHaveLength(2);
  });
});
