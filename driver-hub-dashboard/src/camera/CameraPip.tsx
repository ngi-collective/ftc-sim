import { useEffect } from 'react';
import type { CameraStreamInfo, SimPose } from '../protocol';
import { describe, describePose, useCameraFeed } from './CameraView';

/**
 * The camera view over the corner of the Dashboard Field View.
 *
 * Test driving is a loop of "move the robot, look at what it sees", and a loop that crosses a tab
 * boundary is a loop nobody runs. So the camera lives on the same page as the field: small in the
 * corner while you drive, expanded over the scene when you want to read pixels, and never a
 * navigation away from the robot you are steering.
 *
 * One `<img>` across both sizes, deliberately. Expanding restyles its container rather than
 * mounting a second element, so the stream is not dropped and re-established every time somebody
 * zooms in — an MJPEG reconnect costs a round trip and a frame.
 *
 * Collapsed, it is the picture and nothing else: the click target is the image, and the caption
 * that used to sit above it said the pose the field view already draws and a stream state that is
 * only interesting when it is wrong. That line cost a tenth of the panel's height permanently to
 * report something twice, so it is gone and the one fact the picture cannot show on its own — a
 * feed that is not arriving — appears over the image only while that is true.
 */
export function CameraPip({
  stream,
  pose,
  expanded,
  onExpandedChange,
}: {
  stream: CameraStreamInfo | null;
  pose: SimPose | null;
  expanded: boolean;
  onExpandedChange: (expanded: boolean) => void;
}) {
  const feed = useCameraFeed(stream);

  // Escape collapses, because an expanded view covers the field and clicking "somewhere else" is
  // the other thing hands try; both work.
  useEffect(() => {
    if (!expanded) return;
    const onKey = (event: KeyboardEvent) => {
      if (event.key === 'Escape') onExpandedChange(false);
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [expanded, onExpandedChange]);

  if (!stream) return null;

  return (
    <>
      {expanded && <div style={backdrop} onClick={() => onExpandedChange(false)} />}
      <div style={expanded ? expandedFrame : pipFrame}>
        {expanded && (
          <div style={bar}>
            <span style={label}>CAMERA</span>
            <span style={feed.state === 'streaming' ? live : stale}>{describe(feed.state)}</span>
            <span style={hint}>
              {stream.width}&times;{stream.height}
            </span>
            <span style={hint}>{stream.framesPerSecond} fps</span>
            <span style={hint}>sampled view, not every frame</span>
            <span style={poseText}>{pose ? describePose(pose) : 'no pose'}</span>
            <button
              style={zoomButton}
              onClick={() => onExpandedChange(false)}
              title="Collapse (Esc)"
            >
              collapse
            </button>
          </div>
        )}
        <img
          {...feed.imageProps}
          alt="Simulated camera view"
          style={expanded ? { ...picture, cursor: 'zoom-out' } : picture}
          title={expanded ? 'Collapse (Esc)' : 'Expand'}
          onClick={() => onExpandedChange(!expanded)}
        />
        {/* Only while something is wrong. A frame with nothing in it is the commonest thing this
            panel shows — the robot is aimed where the tags are not — and without this there is no
            way to tell that from a stream that stopped arriving. */}
        {!expanded && feed.state !== 'streaming' && (
          <div style={feedBadge}>{describe(feed.state)}</div>
        )}
      </div>
    </>
  );
}

/**
 * Sits in the top-left corner of the field view, opposite the gamepad panel. 4:3, as the camera is.
 *
 * Both panels are up here because the bottom half of the scene is where the robot is driven: a
 * panel over the near half of the field hides the tiles a driver is aiming across, and the two of
 * them stacked in one corner hid a quarter of it.
 *
 * The z-index is not decoration: the scene's own labels are drei `<Html>` overlays with a real
 * stacking order (5 for station names, 10 for device tags), and anything left on `auto` paints
 * below them however late it appears in the DOM. Without this, wall and motor labels draw across
 * the camera image.
 */
const pipFrame: React.CSSProperties = {
  position: 'absolute',
  left: 12,
  top: 12,
  width: 260,
  display: 'flex',
  flexDirection: 'column',
  border: '1px solid #1e2a1e',
  background: 'rgba(8, 12, 16, 0.85)',
  zIndex: 12,
};

/**
 * Big enough to read a tag's cells, and still inside the scene: expanding must not feel like
 * leaving the page, or it is no better than the tab it exists to replace.
 */
const expandedFrame: React.CSSProperties = {
  position: 'absolute',
  top: '50%',
  left: '50%',
  transform: 'translate(-50%, -50%)',
  width: 'min(92%, 960px)',
  display: 'flex',
  flexDirection: 'column',
  border: '1px solid #2a4a2a',
  background: 'rgba(6, 10, 8, 0.97)',
  boxShadow: '0 12px 48px rgba(0, 0, 0, 0.6)',
  zIndex: 21,
};

const backdrop: React.CSSProperties = {
  position: 'absolute',
  inset: 0,
  background: 'rgba(0, 0, 0, 0.45)',
  zIndex: 20,
};

const bar: React.CSSProperties = {
  display: 'flex',
  gap: 8,
  alignItems: 'center',
  padding: '3px 6px',
  borderBottom: '1px solid #1e2a1e',
  color: '#7fbf7f',
  fontFamily: 'monospace',
  fontSize: 10,
};

const label: React.CSSProperties = { color: '#7CFC00', letterSpacing: 1 };

const live: React.CSSProperties = { color: '#7CFC00' };

const stale: React.CSSProperties = { color: '#d8d84a' };

const hint: React.CSSProperties = { color: '#5f7a5f' };

/** Pushed right so the zoom control stays in the same place whatever the pose reads. */
const poseText: React.CSSProperties = { ...hint, marginLeft: 'auto' };

/**
 * Over the picture rather than above it, so a healthy feed pays nothing for it. `pointerEvents`
 * off because the image underneath is the expand control and a badge that swallowed the click
 * would make the panel stop responding exactly when something is already wrong.
 */
const feedBadge: React.CSSProperties = {
  position: 'absolute',
  left: 0,
  bottom: 0,
  padding: '2px 6px',
  background: 'rgba(8, 12, 16, 0.85)',
  color: '#d8d84a',
  fontFamily: 'monospace',
  fontSize: 10,
  pointerEvents: 'none',
};

const picture: React.CSSProperties = {
  display: 'block',
  width: '100%',
  // The camera is 4:3 and so is every frame it sends; fixing the ratio here keeps the panel from
  // resizing as the first frame lands.
  aspectRatio: '4 / 3',
  objectFit: 'contain',
  imageRendering: 'pixelated',
  background: '#000',
  cursor: 'zoom-in',
};

const zoomButton: React.CSSProperties = {
  background: '#132313',
  color: '#7CFC00',
  border: '1px solid #2a4a2a',
  padding: '0 5px',
  fontSize: 10,
  fontFamily: 'monospace',
  cursor: 'pointer',
};
