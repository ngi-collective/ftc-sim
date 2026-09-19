import { describe, expect, it } from 'vitest';
import {
  INTERPOLATION_DELAY_MS,
  STALE_FRAME_MS,
  createBodyBuffer,
  interpolateBody,
} from './bodies';
import type { SimBodies, SimBody, SimPivot } from '../protocol';

/**
 * The arithmetic behind the moving balls.
 *
 * <p>Worth testing here because it cannot be tested where it is used: nothing in this repository
 * can assert what the WebGL canvas draws, so what the canvas is told is kept pure and checked
 * instead. The failures these guard against are all invisible in a screenshot and obvious in
 * motion — a ball drawn half a field away, a ball that unrolls backwards, a ball that glides
 * smoothly to a position it actually jumped to.</p>
 */
describe('body interpolation', () => {
  const body = (over: Partial<SimBody> = {}): SimBody => ({
    id: 0,
    x: 0,
    y: 0,
    z: 0.0355,
    qx: 0,
    qy: 0,
    qz: 0,
    qw: 1,
    ...over,
  });

  it('places a half-interpolated body halfway, in the scene frame', () => {
    const sampled = interpolateBody(
      body({ x: 1, y: 2, z: 0.5 }),
      body({ x: 2, y: 4, z: 1.5 }),
      0.5,
    );

    // Scene Y is the field's height and scene Z is the negated field Y: the same single mirror
    // frame.ts owns. Getting it wrong here draws the balls on the wrong side of the field while
    // the tags, which go through frame.ts, stay put.
    expect(sampled.position).toEqual([1.5, 1, -3]);
  });

  it('refuses to extrapolate past either end', () => {
    const from = body({ x: 0 });
    const to = body({ x: 1 });

    // Clamped, not extended. Extrapolation is prediction, and a browser that predicts is a second
    // simulation that can disagree with the server about where a ball is.
    expect(interpolateBody(from, to, 2).position[0]).toBe(1);
    expect(interpolateBody(from, to, -1).position[0]).toBe(0);
  });

  it('turns a spinning body the short way round when the quaternion sign flips', () => {
    // A quarter turn about field +Z, and the same orientation written with every sign flipped:
    // q and -q are the same rotation, so the interpolated midpoint must be the same either way.
    const quarter = Math.SQRT1_2;
    const from = body({ qz: 0, qw: 1 });
    const positive = body({ qz: quarter, qw: quarter });
    const negated = body({ qz: -quarter, qw: -quarter });

    const viaPositive = interpolateBody(from, positive, 0.5).quaternion;
    const viaNegated = interpolateBody(from, negated, 0.5).quaternion;

    // An eighth of a turn, reached the short way in both cases. Without the sign fix the negated
    // pair travels the other seven eighths and the ball is drawn rolling backwards.
    expect(viaNegated[2]).toBeCloseTo(Math.abs(viaPositive[2]), 12);
    expect(Math.abs(viaNegated[3])).toBeCloseTo(Math.abs(viaPositive[3]), 12);
    expect(Math.hypot(...viaNegated)).toBeCloseTo(1, 12);
  });

  it('keeps quaternions unit length so a ball is never drawn squashed', () => {
    const sampled = interpolateBody(
      body({ qx: 0.5, qy: 0.5, qz: 0.5, qw: 0.5 }),
      body({ qz: 1, qw: 0 }),
      0.37,
    );

    expect(Math.hypot(...sampled.quaternion)).toBeCloseTo(1, 12);
  });
});

describe('the body buffer', () => {
  const ball = (id: number, x: number): SimBody => ({
    id,
    x,
    y: 0,
    z: 0.0355,
    qx: 0,
    qy: 0,
    qz: 0,
    qw: 1,
  });

  const frame = (timestampMillis: number, bodies: SimBody[]): SimBodies => ({
    timestampMillis,
    elapsedSeconds: timestampMillis / 1000,
    bodies,
  });

  it('draws nothing of its own until a frame has arrived', () => {
    // The scene payload already carries every ball's position, so an untouched field must be left
    // to it. Answering with an empty list is what lets that happen.
    expect(createBodyBuffer().sample(1000)).toEqual([]);
  });

  it('draws a body between the two frames that straddle the sampled moment', () => {
    const buffer = createBodyBuffer();
    buffer.accept(frame(1000, [ball(0, 0)]));
    buffer.accept(frame(1020, [ball(0, 1)]));

    // Sampling is deliberately behind the newest frame: the frame after it does not exist yet.
    const sampled = buffer.sample(1020 + INTERPOLATION_DELAY_MS / 2);

    expect(sampled[0].position[0]).toBeCloseTo(0.5, 12);
  });

  it('holds at the newest frame rather than running past it', () => {
    const buffer = createBodyBuffer();
    buffer.accept(frame(1000, [ball(0, 0)]));
    buffer.accept(frame(1020, [ball(0, 1)]));

    // A slow tick, a stalled server, a tab that was in the background: the sampler must stop at
    // the last thing it was told rather than carry the ball onwards at its last speed.
    expect(buffer.sample(5000)[0].position[0]).toBe(1);
  });

  it('jumps rather than glides when the field has been at rest in between', () => {
    const buffer = createBodyBuffer();
    buffer.accept(frame(1000, [ball(0, 0)]));
    // Bodies are published only while something moves, so the gap means the ball sat still for a
    // second and was then hit. Interpolating across it would draw a smooth second-long drift that
    // never happened.
    buffer.accept(frame(1000 + STALE_FRAME_MS + 100, [ball(0, 1)]));

    expect(buffer.sample(1000 + STALE_FRAME_MS + 100)[0].position[0]).toBe(1);
  });

  it('ignores a frame that arrives out of order', () => {
    const buffer = createBodyBuffer();
    buffer.accept(frame(1020, [ball(0, 1)]));
    buffer.accept(frame(1000, [ball(0, 0)]));

    expect(buffer.sample(1020 + INTERPOLATION_DELAY_MS)[0].position[0]).toBe(1);
  });

  it('draws a newly arrived body at its own position rather than from another ball', () => {
    const buffer = createBodyBuffer();
    buffer.accept(frame(1000, [ball(0, 0)]));
    // Ball 1 was at rest and has only now been hit, so it appears in the newer frame alone. Keyed
    // by array position instead of id, it would be interpolated from ball 0's place and shoot
    // across the field on its first moving frame.
    buffer.accept(frame(1020, [ball(0, 0.5), ball(1, -1.5)]));

    const sampled = buffer.sample(1020 + INTERPOLATION_DELAY_MS / 2);
    const arrived = sampled.find((each) => each.id === 1);

    expect(arrived?.position[0]).toBe(-1.5);
  });

  it('matches bodies by id, not by where they sit in the frame', () => {
    const buffer = createBodyBuffer();
    // The same two balls, listed in opposite orders. The payload says the id is the identity, so
    // this has to work; matched by array position the two would be interpolated from each other
    // and both would be drawn crossing the field.
    buffer.accept(frame(1000, [ball(1, -1), ball(0, 0)]));
    buffer.accept(frame(1020, [ball(0, 1), ball(1, -2)]));

    const sampled = buffer.sample(1020 + INTERPOLATION_DELAY_MS / 2);
    const found = (id: number) => sampled.find((each) => each.id === id)?.position[0];

    expect(found(0)).toBeCloseTo(0.5, 12);
    expect(found(1)).toBeCloseTo(-1.5, 12);
  });

  it('forgets every frame when the field is rearranged', () => {
    const buffer = createBodyBuffer();
    buffer.accept(frame(1000, [ball(0, 1.2)]));
    buffer.accept(frame(1020, [ball(0, 1.4)]));

    buffer.reset();

    // Answering with nothing is what hands the drawing back to sim/scene, which carries the
    // positions the rebuilt world actually has. Found by pressing INIT and watching the balls stay
    // where they had been shoved to: the render loop kept writing the last body frame over the
    // scene's own positions, and they stayed wrong until something moved them again.
    expect(buffer.sample(1040)).toEqual([]);
  });
});

/**
 * The tipping HIVEs travel the same path as the balls: same frames, same interpolation, same
 * clock. What is different is the join — a name rather than an id — and the fact that a HIVE goes
 * on swinging after the last ball has come to rest, which makes a frame with no bodies in it an
 * ordinary frame rather than a broken one.
 */
describe('the pivot angles in the body buffer', () => {
  const frame = (
    timestampMillis: number,
    bodies: SimBody[],
    pivots: SimPivot[] = [],
  ): SimBodies => ({
    timestampMillis,
    elapsedSeconds: timestampMillis / 1000,
    bodies,
    pivots,
  });

  it('draws a hinge between the two frames that straddle the sampled moment', () => {
    const buffer = createBodyBuffer();
    buffer.accept(frame(1000, [], [{ name: 'HIVE RED', angleRadians: 0 }]));
    buffer.accept(frame(1020, [], [{ name: 'HIVE RED', angleRadians: 1 }]));

    // The same interpolation the balls get, for the same reason: frames arrive at 50 Hz and the
    // display refreshes faster, so a HIVE drawn from the newest frame alone visibly steps.
    expect(buffer.samplePivots(1020 + INTERPOLATION_DELAY_MS / 2).get('HIVE RED')).toBeCloseTo(
      0.5,
      12,
    );
  });

  it('keeps each HIVE its own angle however the frame lists them', () => {
    const buffer = createBodyBuffer();
    // Listed in opposite orders between the two frames, which the scene is free to do. Joined by
    // position instead of by name, each HIVE would be interpolated towards the other's angle and
    // both would be drawn swinging through states neither is in.
    buffer.accept(
      frame(1000, [], [
        { name: 'HIVE BLUE', angleRadians: 1 },
        { name: 'HIVE RED', angleRadians: 0 },
      ]),
    );
    buffer.accept(
      frame(1020, [], [
        { name: 'HIVE RED', angleRadians: 0.2 },
        { name: 'HIVE BLUE', angleRadians: 0.8 },
      ]),
    );

    const angles = buffer.samplePivots(1020 + INTERPOLATION_DELAY_MS / 2);

    expect(angles.get('HIVE RED')).toBeCloseTo(0.1, 12);
    expect(angles.get('HIVE BLUE')).toBeCloseTo(0.9, 12);
  });

  it('reports a HIVE that has only just started tipping at the angle it actually has', () => {
    const buffer = createBodyBuffer();
    buffer.accept(frame(1000, [], [{ name: 'HIVE RED', angleRadians: 0.3 }]));
    // The blue HIVE was still when the older frame was sent, so it appears in the newer one alone.
    // Interpolated from an angle that does not exist, it is drawn at NaN — which is to say not
    // drawn at all, along with every tag bolted to it.
    buffer.accept(
      frame(1020, [], [
        { name: 'HIVE RED', angleRadians: 0.5 },
        { name: 'HIVE BLUE', angleRadians: 0.05 },
      ]),
    );

    expect(buffer.samplePivots(1020 + INTERPOLATION_DELAY_MS / 2).get('HIVE BLUE')).toBe(0.05);
  });

  it('counts a frame that carries only a swinging HIVE as the server having spoken', () => {
    const buffer = createBodyBuffer();
    buffer.accept(frame(1000, [], [{ name: 'HIVE RED', angleRadians: 0.4 }]));

    // Every ball has come to rest and the HIVE is still moving, so the frame has no bodies in it.
    // Read as silence — which is what an empty sample() used to mean — the render loop falls back
    // to the scene's own positions and throws every ball back to where the scenario placed it,
    // mid-match, for as long as the HIVE keeps swinging.
    expect(buffer.hasFrame()).toBe(true);
    expect(buffer.sample(1020)).toEqual([]);

    buffer.reset();

    // And a rearranged field is silence again: the scene carries both the ball positions and the
    // angle its HIVEs were published at.
    expect(buffer.hasFrame()).toBe(false);
    expect(buffer.samplePivots(1020).size).toBe(0);
  });

  it('says nothing about a hinge on a server that does not send pivots at all', () => {
    const buffer = createBodyBuffer();
    // An older server: bodies and nothing else. Every HIVE must then be drawn at the angle
    // sim/scene published, which is what an absent angle means downstream.
    buffer.accept({
      timestampMillis: 1000,
      elapsedSeconds: 1,
      bodies: [{ id: 0, x: 0.5, y: 0, z: 0.0355, qx: 0, qy: 0, qz: 0, qw: 1 }],
    });

    expect(buffer.samplePivots(1020).size).toBe(0);
  });
});
