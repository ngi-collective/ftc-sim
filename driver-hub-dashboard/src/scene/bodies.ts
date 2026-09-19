import type { SimBodies, SimBody } from '../protocol';
import { scenePoint, type ScenePoint } from './frame';

/**
 * Smooths the server's body frames into something to draw every animation frame.
 *
 * <p>The server is authoritative and the browser predicts nothing: there is no second simulation
 * here, no replay of the physics, no guessing where a ball is going. That is the same decision
 * ADR-0002 made about the camera view, for the same reason — a client that predicts is a client
 * that can disagree with the server, and then a driver cannot tell which of the two pictures is
 * the robot's reality. What this does is strictly weaker and entirely safe: it draws a point
 * <em>between two positions the server has already sent</em>.</p>
 *
 * <p>That interpolation is not cosmetic. Bodies arrive on the 50 Hz control cycle and the display
 * refreshes at 60 Hz or more, so drawing the newest frame each time shows each position for one or
 * two frames at random — a rolling ball visibly stutters, and a stuttering ball reads as physics
 * that is struggling rather than as a display that is faster than its source.</p>
 *
 * <p>Pure, exported, and free of three.js objects on the way in, so every bit of it is testable in
 * Node. That is deliberate: this repository has no way to assert what the WebGL canvas actually
 * draws, so the arithmetic behind the drawing is kept somewhere that can be checked without one.
 * The same reasoning put the field-frame conversion in {@code frame.ts}.</p>
 */

/** How one body should be drawn right now. */
export interface SampledBody {
  id: number;
  /** three.js scene frame, ready for a mesh's {@code position}. */
  position: ScenePoint;
  /** three.js quaternion order, ready for a mesh's {@code quaternion}. */
  quaternion: [x: number, y: number, z: number, w: number];
}

/**
 * How far behind the newest frame to draw, in milliseconds.
 *
 * <p>Interpolation needs two frames that straddle the moment being drawn, and the future one does
 * not exist yet — so the moment being drawn has to be in the past. One control cycle of delay is
 * the least that can work and is what this uses: 20 ms, which is under two display frames and
 * a third of the 60 ms a person begins to notice as lag.</p>
 *
 * <p>Too small and the sampler runs past the newest frame and holds still until the next one
 * arrives, which is the stutter it exists to remove. Too large and the balls visibly trail the
 * robot that is pushing them.</p>
 */
export const INTERPOLATION_DELAY_MS = 20;

/**
 * Frames older than this are dropped rather than interpolated from.
 *
 * <p>Bodies stop arriving the moment the field comes to rest, so the previous pair of frames can be
 * arbitrarily old by the time anything moves again. Interpolating across that gap would walk each
 * ball smoothly from where it stopped to where it now is, over a period that has nothing to do with
 * how long the move took. Beyond this age the newer frame is simply shown.</p>
 */
export const STALE_FRAME_MS = 250;

/**
 * Interpolates a single body between two frames.
 *
 * @param fraction 0 gives {@code from}, 1 gives {@code to}; values outside are clamped, because
 *     extrapolating is prediction and this module does not predict
 */
export function interpolateBody(from: SimBody, to: SimBody, fraction: number): SampledBody {
  const t = fraction <= 0 ? 0 : fraction >= 1 ? 1 : fraction;
  return {
    id: to.id,
    position: [
      from.x + (to.x - from.x) * t,
      from.z + (to.z - from.z) * t,
      -(from.y + (to.y - from.y) * t),
    ],
    quaternion: slerp(from, to, t),
  };
}

/**
 * Shortest-arc quaternion interpolation.
 *
 * <p>The negation is the part that matters: {@code q} and {@code -q} are the same orientation, so
 * two consecutive frames of a spinning ball can arrive with opposite signs and the straight line
 * between them goes the long way round — the ball visibly unrolls backwards through most of a
 * turn. Flipping one of them when their dot product is negative takes the short arc instead.</p>
 *
 * <p>Linear rather than spherical, normalised afterwards. Over a single 20 ms control cycle even a
 * ball spinning at 20 rad/s turns 23 degrees, where the angular error of a normalised lerp is
 * under a fifth of a degree — invisible on a sphere, and it costs two trigonometric functions per
 * body per animation frame to do better.</p>
 */
function slerp(from: SimBody, to: SimBody, t: number): [number, number, number, number] {
  const dot = from.qx * to.qx + from.qy * to.qy + from.qz * to.qz + from.qw * to.qw;
  const sign = dot < 0 ? -1 : 1;
  const x = from.qx + (to.qx * sign - from.qx) * t;
  const y = from.qy + (to.qy * sign - from.qy) * t;
  const z = from.qz + (to.qz * sign - from.qz) * t;
  const w = from.qw + (to.qw * sign - from.qw) * t;
  const length = Math.hypot(x, y, z, w);
  // A zero-length result means both inputs were zero quaternions, which is not an orientation at
  // all; the identity is the only answer that leaves the ball drawn rather than vanished.
  return length === 0 ? [0, 0, 0, 1] : [x / length, y / length, z / length, w / length];
}

/** Where a body is according to one frame alone, with no interpolation to do. */
export function bodyAt(body: SimBody): SampledBody {
  return {
    id: body.id,
    position: scenePoint({ x: body.x, y: body.y, z: body.z }),
    quaternion: [body.qx, body.qy, body.qz, body.qw],
  };
}

/**
 * Keeps the last two body frames and answers where everything is at a given moment.
 *
 * <p>Two frames, not a history: this interpolates between the pair that straddles the sample time
 * and has no use for anything older. A buffer would be a replay system, and a replay system that
 * the physics does not know about is the second simulation this deliberately does not have.</p>
 */
export interface BodyBuffer {
  /** Takes a server frame. Out-of-order frames are dropped; the server's clock decides. */
  accept: (frame: SimBodies) => void;
  /**
   * Where every known body is at {@code wallClockMillis}.
   *
   * <p>Wall clock, as in {@code Date.now()} — the same clock the server stamps frames with, and
   * the reason this is a parameter rather than read in here: a test has to be able to say when
   * "now" is. It is emphatically <em>not</em> {@code performance.now()}, which counts from when
   * the page loaded and is around twelve orders of magnitude smaller. Passing that samples a
   * moment in 1970, every interpolation clamps to the older of the two frames, and the balls are
   * drawn stale for the rest of the session. Both processes are on one machine, so the two
   * {@code Date.now()} readings are the same clock.</p>
   *
   * <p>Returns an empty array until a frame has arrived, so a session whose field has never moved
   * draws its balls from {@code sim/scene} and nothing here overrides them — and also for a frame
   * that moved only a HIVE, which is a different thing entirely: {@link hasFrame} is how a caller
   * tells those two apart.</p>
   */
  sample: (wallClockMillis: number) => SampledBody[];
  /**
   * How far each pivoting structure has swung at {@code wallClockMillis}, keyed by the structure
   * name {@code sim/scene} gave it.
   *
   * <p>A map rather than a list because the consumer joins on the name and does it every animation
   * frame: handing back an array only moves the same {@code Map} build into the render loop. The
   * angles are absolute in the pivot's own convention — what to do with one is
   * {@code scene/pivots.ts}' problem, not this module's.</p>
   *
   * <p>Interpolated linearly and never wrapped. A pivot angle is a hinge travelling a bounded
   * sixty degrees, not a heading: the shortest-arc reasoning {@link interpolateBody} needs for a
   * spinning ball would here be a way to invent a HIVE swinging the wrong way through its own
   * frame.</p>
   *
   * <p>Empty until a frame carrying pivots has arrived, so a structure nobody has reported is
   * drawn at the angle {@code sim/scene} published it at.</p>
   */
  samplePivots: (wallClockMillis: number) => Map<string, number>;
  /**
   * Whether the server has sent any body frame since the last {@link reset}.
   *
   * <p>Not the same question as an empty {@link sample}, and conflating the two is a visible bug: a
   * frame carrying no bodies at all is ordinary now that a HIVE goes on swinging after the last
   * ball has come to rest. Read as "nothing has ever been said" it sends every ball back to where
   * the scenario placed it — backwards, and for as long as the HIVE keeps tipping.</p>
   */
  hasFrame: () => boolean;
  /**
   * Forgets every frame, so the next {@link sample} answers with nothing until the server speaks
   * again.
   *
   * <p>Called when a new {@code sim/scene} arrives, which is the server saying the field has been
   * rearranged: INIT rebuilds the robot, and with it the physics world, putting every ball back
   * where the scenario placed it. The scene carries those positions, but the render loop writes
   * over them from this buffer — so without this, pressing INIT leaves the balls drawn wherever
   * they last rolled to, and they stay wrong until something moves them again. Which is to say
   * they look right the moment you touch them and wrong for as long as you do not.</p>
   */
  reset: () => void;
}

/**
 * How far between {@code previous} and {@code latest} the sampled moment falls, or null when there
 * is nothing to interpolate from and the newest frame is the whole answer.
 *
 * <p>Shared by the two samplers so the delay, the staleness cutoff and the clamp are decided once:
 * bodies and pivots arrive in the same frame and drawing them a control cycle apart would show a
 * ball resting against a HIVE it is not touching.</p>
 */
function blendFraction(
  previous: SimBodies | null,
  latest: SimBodies,
  nowMillis: number,
): number | null {
  const target = nowMillis - INTERPOLATION_DELAY_MS;

  // Nothing to interpolate from, or a gap that means the field was at rest in between: show the
  // newest truth rather than inventing a slow journey to it.
  if (
    !previous ||
    target >= latest.timestampMillis ||
    latest.timestampMillis - previous.timestampMillis > STALE_FRAME_MS
  ) {
    return null;
  }

  const span = latest.timestampMillis - previous.timestampMillis;
  if (span <= 0) return 1;
  // Clamped for the same reason {@link interpolateBody} clamps its own: a sample older than the
  // pair straddling it would otherwise extrapolate, and extrapolating is predicting.
  const fraction = (target - previous.timestampMillis) / span;
  return fraction <= 0 ? 0 : fraction >= 1 ? 1 : fraction;
}

export function createBodyBuffer(): BodyBuffer {
  let previous: SimBodies | null = null;
  let latest: SimBodies | null = null;

  return {
    accept: (frame) => {
      if (latest && frame.timestampMillis < latest.timestampMillis) return;
      previous = latest;
      latest = frame;
    },
    reset: () => {
      previous = null;
      latest = null;
    },
    hasFrame: () => latest !== null,
    sample: (nowMillis) => {
      if (!latest) return [];
      const fraction = blendFraction(previous, latest, nowMillis);
      if (!previous || fraction === null) return latest.bodies.map(bodyAt);

      const before = new Map<number, SimBody>();
      for (const body of previous.bodies) before.set(body.id, body);

      return latest.bodies.map((body) => {
        const from = before.get(body.id);
        // A body the older frame never mentioned has no journey to draw: it has only just started
        // moving, or has only just arrived in the world.
        return from ? interpolateBody(from, body, fraction) : bodyAt(body);
      });
    },
    samplePivots: (nowMillis) => {
      const angles = new Map<string, number>();
      if (!latest) return angles;
      const fraction = blendFraction(previous, latest, nowMillis);

      const before = new Map<string, number>();
      if (fraction !== null && previous) {
        for (const pivot of previous.pivots ?? []) before.set(pivot.name, pivot.angleRadians);
      }

      for (const pivot of latest.pivots ?? []) {
        const from = before.get(pivot.name);
        // A pivot the older frame never named has no journey either: it has only just begun to
        // tip, so its newest angle is the only one there is.
        angles.set(
          pivot.name,
          fraction === null || from === undefined
            ? pivot.angleRadians
            : from + (pivot.angleRadians - from) * fraction,
        );
      }
      return angles;
    },
  };
}
