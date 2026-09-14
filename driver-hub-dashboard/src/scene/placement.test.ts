import { describe, expect, it } from 'vitest';
import { STANDARD_FIELD } from './Field';
import { CHASSIS } from './layout';
import { placementFromDrag, wrapDegrees, type DragState } from './placement';

/** An 18" chassis on a standard field: the robot and the wall this all has to agree with. */
const CHASSIS_SHAPE = { width: CHASSIS.width, depth: CHASSIS.depth };
const FIELD_SIZE = STANDARD_FIELD.sizeMetres;
const DIAGONAL = Math.hypot(CHASSIS.width, CHASSIS.depth);

/** A drag that has not moved yet: grabbed exactly at the robot's centre. */
function grabbedAtCentre(turning: boolean): DragState {
  return { turning, offsetX: 0, offsetY: 0, grabBearing: null, startHeading: 0 };
}

describe('a heading dragged round the circle', () => {
  it('comes back as the same heading on the other side of the seam, not another lap', () => {
    expect(wrapDegrees(181)).toBe(-179);
    expect(wrapDegrees(-181)).toBe(179);
    expect(wrapDegrees(359)).toBe(-1);
    expect(wrapDegrees(-359)).toBe(1);
    expect(wrapDegrees(720)).toBe(0);
  });

  it('leaves a heading already inside the seam alone', () => {
    expect(wrapDegrees(0)).toBe(0);
    expect(wrapDegrees(-179)).toBe(-179);
    expect(wrapDegrees(wrapDegrees(1000))).toBe(wrapDegrees(1000));
  });
});

describe('turning the robot by dragging past it', () => {
  const pose = { x: 0, y: 0, headingDegrees: 0 };

  it('reads no heading at all while the pointer is still on top of the robot', () => {
    // Just inside the dead zone: half the longer chassis dimension.
    const result = placementFromDrag({
      drag: grabbedAtCentre(true),
      pointer: { x: Math.max(CHASSIS.width, CHASSIS.depth) / 2 - 0.001, y: 0 },
      pose,
      chassis: CHASSIS_SHAPE,
      fieldSize: FIELD_SIZE,
      snap: true,
    });

    expect(result.placement).toBeNull();
    // Nothing was learned either: the next move still has to establish a reference bearing.
    expect(result.grab.grabBearing).toBeNull();
  });

  it('takes the first bearing clear of the robot as a reference, not as a turn', () => {
    const result = placementFromDrag({
      drag: grabbedAtCentre(true),
      pointer: { x: 0.8, y: 0 },
      pose: { x: 0, y: 0, headingDegrees: 45 },
      chassis: CHASSIS_SHAPE,
      fieldSize: FIELD_SIZE,
      snap: true,
    });

    expect(result.placement).toBeNull();
    expect(result.grab.grabBearing).toBeCloseTo(0, 10);
    expect(result.grab.startHeading).toBe(45);
  });

  it('turns the robot by the angle the pointer swept, once it has a reference', () => {
    const result = placementFromDrag({
      // Grabbed due east of the robot at heading 10, now dragged round to due north.
      drag: { turning: true, offsetX: 0, offsetY: 0, grabBearing: 0, startHeading: 10 },
      pointer: { x: 0, y: 0.8 },
      pose,
      chassis: CHASSIS_SHAPE,
      fieldSize: FIELD_SIZE,
      snap: true,
    });

    expect(result.placement?.headingDegrees).toBe(100);
    // A turn is a turn: the body does not creep towards the pointer while it happens.
    expect(result.placement?.x).toBe(0);
    expect(result.placement?.y).toBe(0);
  });

  it('crosses the seam as a continuous turn: past 180 comes out just past -180', () => {
    const result = placementFromDrag({
      drag: { turning: true, offsetX: 0, offsetY: 0, grabBearing: 0, startHeading: 170 },
      pointer: { x: Math.cos(Math.PI / 9) * 0.8, y: Math.sin(Math.PI / 9) * 0.8 },
      pose,
      chassis: CHASSIS_SHAPE,
      fieldSize: FIELD_SIZE,
      snap: true,
    });

    // 170 + 20 = 190, which is -170 and not a robot that has spun most of a lap.
    expect(result.placement?.headingDegrees).toBe(-170);
  });
});

describe('translating the robot across the floor', () => {
  it('keeps the grab point under the pointer instead of jumping the body to it', () => {
    const result = placementFromDrag({
      // Grabbed 10 cm behind and left of the centre, then moved 20 cm along each axis.
      drag: { turning: false, offsetX: 0.1, offsetY: 0.1, grabBearing: null, startHeading: 0 },
      pointer: { x: 0.6, y: 0.3 },
      pose: { x: 0.5, y: 0.2, headingDegrees: 30 },
      chassis: CHASSIS_SHAPE,
      fieldSize: FIELD_SIZE,
      snap: true,
    });

    expect(result.placement?.x).toBeCloseTo(0.7, 10);
    expect(result.placement?.y).toBeCloseTo(0.4, 10);
    // Translating never turns the robot; the heading it had is the heading it keeps.
    expect(result.placement?.headingDegrees).toBe(30);
  });

  it('snaps to the centimetre when snapping is on, and keeps the millimetres when it is off', () => {
    const drag = grabbedAtCentre(false);
    const pointer = { x: 0.4237, y: -0.4237 };
    const shared = { drag, pointer, pose: { x: 0, y: 0, headingDegrees: 0 } };

    const snapped = placementFromDrag({
      ...shared,
      chassis: CHASSIS_SHAPE,
      fieldSize: FIELD_SIZE,
      snap: true,
    });
    expect(snapped.placement?.x).toBeCloseTo(0.42, 10);
    expect(snapped.placement?.y).toBeCloseTo(-0.42, 10);

    const free = placementFromDrag({
      ...shared,
      chassis: CHASSIS_SHAPE,
      fieldSize: FIELD_SIZE,
      snap: false,
    });
    expect(free.placement?.x).toBe(0.4237);
    expect(free.placement?.y).toBe(-0.4237);
  });

  it('stops the robot with its corner at the wall, however far past it the pointer goes', () => {
    const result = placementFromDrag({
      drag: grabbedAtCentre(false),
      pointer: { x: 40, y: -40 },
      pose: { x: 0, y: 0, headingDegrees: 0 },
      chassis: CHASSIS_SHAPE,
      fieldSize: FIELD_SIZE,
      snap: true,
    });

    // Whatever the heading, the diagonal is the most of the robot that can reach a wall, so a
    // robot pushed into the corner sits exactly half a diagonal short of it — inside the
    // perimeter, and touching it.
    expect(result.placement?.x).toBeCloseTo(FIELD_SIZE / 2 - DIAGONAL / 2, 10);
    expect(result.placement?.y).toBeCloseTo(-(FIELD_SIZE / 2 - DIAGONAL / 2), 10);
  });
});
