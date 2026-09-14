import { useEffect, useState } from 'react';
import type { CameraStreamInfo, SimPose } from '../protocol';

/**
 * The Dashboard Camera View: what the simulated robot's camera sees, live.
 *
 * An `<img>` and nothing more. The stream is MJPEG over HTTP, so the browser decodes every frame
 * itself — no canvas, no codec, no per-frame JavaScript — and the pose the frame was rendered from
 * is read on the server at render time, so what shows here is where the robot is now.
 *
 * Live whenever the simulation is, with or without an OpMode. Real hardware produces nothing until
 * a `VisionPortal` opens the camera; this diverges deliberately, because "can the camera see the
 * tag from here" is a question you ask before writing the OpMode that depends on the answer.
 */
export function CameraView({
  stream,
  pose,
}: {
  stream: CameraStreamInfo | null;
  pose: SimPose | null;
}) {
  const feed = useCameraFeed(stream);

  if (!stream) {
    return (
      <div style={frame}>
        <p style={notice}>
          No camera on this robot.
          <br />
          <span style={hint}>
            The camera view renders whatever webcam the simulated robot declares; this one declares
            none.
          </span>
        </p>
      </div>
    );
  }

  return (
    <div style={frame}>
      <div style={bar}>
        <span style={label}>CAMERA</span>
        <span>
          {stream.width}&times;{stream.height}
        </span>
        <span>{stream.framesPerSecond} fps</span>
        <span style={feed.state === 'streaming' ? live : stale}>{describe(feed.state)}</span>
        {/* Where the camera is standing. A frame with nothing in it is the commonest thing this
            panel shows — the robot is somewhere the tags are not — and the pose is the only
            readout that distinguishes "aimed at nothing" from "broken". */}
        <span style={hint}>{pose ? describePose(pose) : 'no pose'}</span>
        {/* Said out loud, because a panel that looks like a recording invites conclusions about
            frames it never showed: the detector runs faster than this view samples it. */}
        <span style={hint}>sampled view, not every frame</span>
      </div>
      <div style={stage}>
        <img {...feed.imageProps} alt="Simulated camera view" style={picture} />
      </div>
      {feed.state === 'failed' && (
        <div style={errorLine}>
          {stream.url} is not answering. Retrying; check the dashboard server is still up.
        </div>
      )}
    </div>
  );
}

type FeedState = 'connecting' | 'streaming' | 'failed';

/**
 * One MJPEG connection, with the reconnect that a dead stream needs.
 *
 * Shared by the tab and the picture-in-picture so that both are literally the same feed logic; the
 * two never render at once, so there is only ever one connection to the server.
 */
export function useCameraFeed(stream: CameraStreamInfo | null) {
  // Bumped to force a fresh connection: a dead MJPEG stream never recovers on its own, and the
  // server outliving a page (or the other way round) is normal here.
  const [attempt, setAttempt] = useState(0);
  const [state, setState] = useState<FeedState>('connecting');

  useEffect(() => {
    if (state !== 'failed') return;
    const retry = setTimeout(() => {
      setState('connecting');
      setAttempt((count) => count + 1);
    }, 2000);
    return () => clearTimeout(retry);
  }, [state]);

  return {
    state,
    imageProps: {
      // The query string is the retry: without it a browser serves the dead stream from cache.
      src: !stream ? undefined : attempt === 0 ? stream.url : `${stream.url}?retry=${attempt}`,
      onLoad: () => setState('streaming'),
      onError: () => setState('failed'),
    },
  };
}

export function describe(state: FeedState) {
  return state === 'streaming' ? 'live' : state === 'connecting' ? 'connecting' : 'no signal';
}

/** Metres and degrees, matching the field readouts everywhere else. */
export function describePose(pose: SimPose) {
  return `at ${pose.x.toFixed(2)}, ${pose.y.toFixed(2)} facing ${pose.headingDegrees.toFixed(0)}°`;
}

const frame: React.CSSProperties = {
  flex: 1,
  display: 'flex',
  flexDirection: 'column',
  background: '#0a0f0a',
  minHeight: 0,
};

const bar: React.CSSProperties = {
  display: 'flex',
  gap: 12,
  alignItems: 'center',
  padding: '4px 10px',
  borderBottom: '1px solid #1c2a1c',
  color: '#7fbf7f',
  fontFamily: 'monospace',
  fontSize: 11,
};

const label: React.CSSProperties = { color: '#7CFC00', letterSpacing: 1 };

const live: React.CSSProperties = { color: '#7CFC00' };

const stale: React.CSSProperties = { color: '#d8d84a' };

const hint: React.CSSProperties = { color: '#5f7a5f' };

const stage: React.CSSProperties = {
  flex: 1,
  display: 'flex',
  alignItems: 'center',
  justifyContent: 'center',
  minHeight: 0,
  padding: 12,
};

/**
 * Scaled to fill the panel, and `pixelated` on purpose: this view exists to show how many pixels
 * across a tag actually is, and a smoothing filter invents detail the detector never had.
 */
const picture: React.CSSProperties = {
  width: '100%',
  height: '100%',
  objectFit: 'contain',
  imageRendering: 'pixelated',
  border: '1px solid #1c2a1c',
  background: '#000',
};

const notice: React.CSSProperties = {
  margin: 'auto',
  color: '#7fbf7f',
  fontFamily: 'monospace',
  textAlign: 'center',
  lineHeight: 1.6,
};

const errorLine: React.CSSProperties = {
  padding: '4px 10px',
  borderTop: '1px solid #4a2a2a',
  color: '#ffb3b3',
  fontFamily: 'monospace',
  fontSize: 11,
};
