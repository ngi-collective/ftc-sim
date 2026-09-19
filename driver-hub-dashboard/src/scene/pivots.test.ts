import { describe, expect, it } from 'vitest';
import * as THREE from 'three';
import type { SceneCorner, ScenePivot, SceneSolid, SceneStructure, SceneTag } from '../protocol';
import { scenePoint } from './frame';
import {
  pivotLocal,
  pivotPlacement,
  pivotTurnRadians,
  splitByPivot,
  type PivotPlacement,
} from './pivots';

/**
 * The arithmetic behind a tipping HIVE, for the reason {@code bodies.test.ts} gives: nothing here
 * can assert what the canvas drew, so the numbers it is driven with are kept pure and checked.
 *
 * <p>Every failure these guard against looks almost right. A HIVE turned by the absolute angle
 * instead of the difference is tipped twice over — at the few degrees it spends most of a match at,
 * indistinguishable from correct. A mirrored axis tips it the right amount the wrong way. A tag
 * assigned to the wrong hinge swings with the other alliance's HIVE, which is only visible when
 * both are moving.</p>
 */

/**
 * The red HIVE's hinge as Java quotes it: a point on the axis, the axis along field {@code +X},
 * and the solids published unturned.
 */
const HINGE: ScenePivot = {
  x: -0.32385,
  y: 0,
  z: 1.11633,
  axisX: 1,
  axisY: 0,
  axisZ: 0,
  angleRadians: 0,
};

/** The sixty degrees a HIVE swings through between its two stable states. */
const TIP = Math.PI / 3;

/** How far out along the hinge's {@code +Y} the middle of a CELL sits. */
const REACH = 0.2;

/** A field point offset from the hinge itself, which is what a CELL is. */
function fromHinge(y: number, z: number): SceneCorner {
  return { x: HINGE.x, y: HINGE.y + y, z: HINGE.z + z };
}

/**
 * Where a child of the hinge's group is actually drawn: the group's own transform applied to a
 * child positioned relative to it, which is the composition three.js performs and therefore the
 * only composition worth asserting.
 */
function drawnAt(
  placement: PivotPlacement,
  point: SceneCorner,
  liveRadians: number | undefined,
): THREE.Vector3 {
  const turn = new THREE.Quaternion().setFromAxisAngle(
    new THREE.Vector3(...placement.axis),
    pivotTurnRadians(placement, liveRadians),
  );
  return new THREE.Vector3(...pivotLocal(scenePoint(point), placement))
    .applyQuaternion(turn)
    .add(new THREE.Vector3(...placement.position));
}

describe('a hinge as a group transform', () => {
  it('leaves the published pose alone when the live angle is the one it was drawn at', () => {
    // The double-rotation bug, twice: once for a HIVE published upright and once for one published
    // already tipped. Turning by the absolute angle instead of the difference draws the second of
    // these at thirty degrees when the server said fifteen — and a HIVE tipped twice over is the
    // kind of wrong that reads as physics being slightly off rather than as a rendering bug.
    const cell = fromHinge(REACH, 0);
    for (const published of [0, TIP / 4, TIP]) {
      const placement = pivotPlacement({ ...HINGE, angleRadians: published });
      const drawn = drawnAt(placement, cell, published);

      expect(drawn.distanceTo(new THREE.Vector3(...scenePoint(cell)))).toBeCloseTo(0, 12);
    }
  });

  it('turns by the difference between the live angle and the published one', () => {
    const placement = pivotPlacement({ ...HINGE, angleRadians: 0.4 });

    expect(pivotTurnRadians(placement, 0.9)).toBeCloseTo(0.5, 12);
    expect(pivotTurnRadians(placement, 0.1)).toBeCloseTo(-0.3, 12);
  });

  it('draws the published pose while no angle has arrived', () => {
    // A field at rest has never reported a pivot, and the solids already carry the right angle.
    // Anything but zero here would tip every HIVE the moment the scene loaded.
    const placement = pivotPlacement({ ...HINGE, angleRadians: TIP });

    expect(pivotTurnRadians(placement, undefined)).toBe(0);
    // NaN would multiply out into a quaternion of NaNs, which removes the whole HIVE from the
    // picture rather than drawing it at the wrong angle.
    expect(pivotTurnRadians(placement, Number.NaN)).toBe(0);
  });

  it('swings a CELL sixty degrees about the field axis, in the direction the field turns', () => {
    // A HIVE's two CELLs are sixty degrees apart about the hinge, so a full tip puts the raised one
    // exactly where the lowered one was. The expectation is worked out in the field frame and
    // converted once, so a mirrored axis — the failure that tips the HIVE the right amount the
    // wrong way, and looks entirely plausible in a still picture — cannot pass it.
    const placement = pivotPlacement(HINGE);
    const raised = fromHinge(REACH, 0);
    const lowered = fromHinge(REACH * Math.cos(TIP), -REACH * Math.sin(TIP));

    const drawn = drawnAt(placement, raised, -TIP);
    const expected = new THREE.Vector3(...scenePoint(lowered));

    expect(drawn.distanceTo(expected)).toBeCloseTo(0, 12);
  });

  it('turns about the axis the wire named, whichever way it points', () => {
    // A HIVE's hinge lies along field +X, where the conversion is the identity and a passed-through
    // axis cannot be told from a converted one. The axis is on the wire precisely so the next hinge
    // on this field need not be parallel to the audience wall, so this one is slanted through all
    // three axes and the expectation is computed the other way round: turned in the field frame
    // about the field axis, then converted once. The two agreeing is the property the whole
    // approach rests on — that frame.ts' conversion is a rotation, not a mirror, so a turn survives
    // being carried across it.
    const axis = new THREE.Vector3(0.3, -0.6, 0.74).normalize();
    const slanted: ScenePivot = {
      x: 0.2,
      y: -0.4,
      z: 0.9,
      axisX: axis.x,
      axisY: axis.y,
      axisZ: axis.z,
      angleRadians: 0.1,
    };
    const point: SceneCorner = { x: 0.7, y: 0.5, z: 1.4 };

    const turned = new THREE.Vector3(point.x - slanted.x, point.y - slanted.y, point.z - slanted.z)
      .applyAxisAngle(axis, TIP)
      .add(new THREE.Vector3(slanted.x, slanted.y, slanted.z));
    const expected = new THREE.Vector3(
      ...scenePoint({ x: turned.x, y: turned.y, z: turned.z }),
    );

    const drawn = drawnAt(pivotPlacement(slanted), point, slanted.angleRadians + TIP);

    expect(drawn.distanceTo(expected)).toBeCloseTo(0, 12);
  });

  it('normalises the published axis so a tip cannot scale the HIVE', () => {
    // three.js trusts the axis handed to setFromAxisAngle: a length of two yields a quaternion of
    // length greater than one, and a non-unit quaternion on a group is a scale. The HIVE would
    // swell and shrink as it tipped, which is a stranger failure than any wrong angle.
    const doubled = pivotPlacement({ ...HINGE, axisX: 2 });
    const turn = new THREE.Quaternion().setFromAxisAngle(
      new THREE.Vector3(...doubled.axis),
      pivotTurnRadians(doubled, TIP),
    );

    expect(Math.hypot(...doubled.axis)).toBeCloseTo(1, 12);
    expect(turn.length()).toBeCloseTo(1, 12);
  });

  it('refuses to turn a hinge that arrived without an axis', () => {
    // A structure carrying a pivot whose axis fields never made it onto the wire. There is no
    // rotation to perform, and inventing one about a made-up axis would move a HIVE — and the tags
    // bolted to it — somewhere the camera view disagrees with.
    const placement = pivotPlacement({ ...HINGE, axisX: 0, axisY: 0, axisZ: 0 });

    expect(placement.fixed).toBe(true);
    expect(pivotTurnRadians(placement, TIP)).toBe(0);
    expect(Math.hypot(...placement.axis)).toBeCloseTo(1, 12);
  });

  it('measures a child from the hinge rather than from the field origin', () => {
    // What the group's children are positioned by. Left absolute, the group's own offset is applied
    // to them a second time and the HIVE is drawn a metre and a half off the field's centre line;
    // converted with the wrong sign, it is drawn upside down.
    const placement = pivotPlacement(HINGE);

    expect(pivotLocal(scenePoint(fromHinge(0, 0)), placement)).toEqual([0, 0, 0]);
    // A metre straight up in the field frame is a metre up scene Y, and half a metre along field
    // +Y is half a metre along negated scene Z.
    expect(pivotLocal(scenePoint(fromHinge(0, 1)), placement)).toEqual([0, 1, 0]);
    expect(pivotLocal(scenePoint(fromHinge(0.5, 0)), placement)).toEqual([0, 0, -0.5]);
  });
});

describe('dividing the scene into what swings and what does not', () => {
  const solid = (): SceneSolid => ({
    shape: 'box',
    x: 0,
    y: 0,
    z: 0,
    yawDegrees: 0,
    pitchDegrees: 0,
    rollDegrees: 0,
    lengthX: 0.1,
    lengthY: 0.1,
    lengthZ: 0.1,
    radiusMetres: 0,
    lengthMetres: 0,
    red: 0,
    green: 0,
    blue: 0,
  });

  const structure = (name: string, pivot?: ScenePivot): SceneStructure => ({
    name,
    solids: [solid()],
    ...(pivot ? { pivot } : {}),
  });

  const tag = (id: number, attachedTo?: string): SceneTag => ({
    id,
    cluster: `CLUSTER ${id}`,
    sizeMetres: 0.04,
    corners: [
      { x: 0, y: 0, z: 1 },
      { x: 0, y: 0.04, z: 1 },
      { x: 0, y: 0.04, z: 0.96 },
      { x: 0, y: 0, z: 0.96 },
    ],
    cells: ['BB', 'BW'],
    ...(attachedTo ? { attachedTo } : {}),
  });

  it('gives each hinge the tags that name it, and the field the ones that name nothing', () => {
    // Listed so that matching by position instead of by name would hand the red HIVE's tags to the
    // blue one: the two HIVEs are interchangeable except for which the server is talking about, and
    // list order is not something sim/scene promises.
    const split = splitByPivot(
      [structure('HIVE FRAME'), structure('HIVE BLUE', HINGE), structure('HIVE RED', HINGE)],
      [tag(1, 'HIVE RED'), tag(2), tag(3, 'HIVE BLUE'), tag(4, 'HIVE RED')],
    );

    const tagsOf = (name: string) =>
      split.groups.find((group) => group.name === name)?.tags.map((each) => each.id);

    expect(tagsOf('HIVE RED')).toEqual([1, 4]);
    expect(tagsOf('HIVE BLUE')).toEqual([3]);
    // Bolted to the field: it swings with nothing, and a tag that moved when a HIVE tipped would
    // be a tag the camera view and this view disagree about.
    expect(split.fixedTags.map((each) => each.id)).toEqual([2]);
    expect(split.fixed.map((each) => each.name)).toEqual(['HIVE FRAME']);
  });

  it('leaves a tag with the field when the structure it names cannot move', () => {
    // The A-frame carries tags and never tips, and a scene can name a structure that a later
    // payload no longer has. Either way the corners the server measured are still where the tag is,
    // so the field is the only honest parent for it.
    const split = splitByPivot(
      [structure('HIVE FRAME'), structure('HIVE RED', HINGE)],
      [tag(1, 'HIVE FRAME'), tag(2, 'HIVE GREEN')],
    );

    expect(split.groups.flatMap((group) => group.tags)).toEqual([]);
    expect(split.fixedTags.map((each) => each.id)).toEqual([1, 2]);
  });
});
