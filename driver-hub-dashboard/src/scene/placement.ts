import * as THREE from 'three';

/**
 * Where a drag of the chassis puts the robot.
 *
 * <p>All of it is field-frame arithmetic — see {@code frame.ts} for the conversion that gets a
 * pointer hit here — and none of it needs a pointer, a canvas or a GPU. It used to live inside the
 * pointer handlers, which meant the three decisions a driver actually feels could not be exercised
 * at all: the dead zone before a turn is read, the seam at &plusmn;180&deg;, and the wall.</p>
 */

const SNAP_METRES = 0.01;
/** A robot placed to the nearest degree; finer than a driver can see on a field. */
const SNAP_DEGREES = 1;

/** Field-frame metres and degrees: what gets sent to the sim as a pose. */
export interface Placement {
  x: number;
  y: number;
  headingDegrees: number;
}

/**
 * What the drag has to remember between moves.
 *
 * <p>{@code grabBearing} is null until the pointer is far enough from the robot to mean a
 * direction; the first move that clears the dead zone records the bearing it was grabbed at and the
 * heading the robot was at, and every later move turns the robot by the difference.</p>
 */
export interface DragGrab {
  grabBearing: number | null;
  startHeading: number;
}

export interface DragState extends DragGrab {
  /** Shift-drag swings the heading; a plain drag translates. */
  turning: boolean;
  /** Where the body was grabbed, relative to its centre, so it does not jump to the pointer. */
  offsetX: number;
  offsetY: number;
}

export interface PlacementResult {
  /** The pose to send, or null when this move has not said anything yet. */
  placement: Placement | null;
  /** The grab to carry into the next move: unchanged unless a turn just acquired its bearing. */
  grab: DragGrab;
}

/** A drag across the &plusmn;180&deg; seam is the same heading, not another lap around the circle. */
export function wrapDegrees(degrees: number): number {
  return ((((degrees + 180) % 360) + 360) % 360) - 180;
}

/**
 * The pose a pointer at {@code pointer} means, given where the robot is now and how it was grabbed.
 *
 * <p>Returns no placement for a move that is only gathering information: a turn whose pointer is
 * still inside the dead zone, and the move that first records the grab bearing. A null placement is
 * "nothing to send yet", never "the robot stays where it is" — the caller must not send a pose it
 * did not get back.</p>
 */
export function placementFromDrag({
  drag,
  pointer,
  pose,
  chassis,
  fieldSize,
  snap,
}: {
  drag: DragState;
  /** The floor point under the pointer, field frame. */
  pointer: { x: number; y: number };
  /** Where the sim says the robot is; the drag is always measured against the live pose. */
  pose: Placement;
  chassis: { width: number; depth: number };
  fieldSize: number;
  snap: boolean;
}): PlacementResult {
  const grab: DragGrab = { grabBearing: drag.grabBearing, startHeading: drag.startHeading };

  if (drag.turning) {
    // Near the centre of rotation the pointer has no lever arm, and its bearing is all noise:
    // wait until the drag is clear of the robot before reading a heading out of it.
    const lever = Math.hypot(pointer.x - pose.x, pointer.y - pose.y);
    if (lever < Math.max(chassis.width, chassis.depth) / 2) return { placement: null, grab };

    const bearing = Math.atan2(pointer.y - pose.y, pointer.x - pose.x);
    if (grab.grabBearing === null) {
      return { placement: null, grab: { grabBearing: bearing, startHeading: pose.headingDegrees } };
    }

    const turned = grab.startHeading + THREE.MathUtils.radToDeg(bearing - grab.grabBearing);
    const heading = snap ? Math.round(turned / SNAP_DEGREES) * SNAP_DEGREES : turned;
    return {
      placement: { x: pose.x, y: pose.y, headingDegrees: wrapDegrees(heading) },
      grab,
    };
  }

  // Whatever the heading, the robot's diagonal is the most of it that can reach a wall.
  const reach = fieldSize / 2 - Math.hypot(chassis.width, chassis.depth) / 2;
  const place = (value: number) => {
    const snapped = snap ? Math.round(value / SNAP_METRES) * SNAP_METRES : Number(value.toFixed(4));
    return THREE.MathUtils.clamp(snapped, -reach, reach);
  };

  return {
    placement: {
      x: place(pointer.x + drag.offsetX),
      y: place(pointer.y + drag.offsetY),
      headingDegrees: pose.headingDegrees,
    },
    grab,
  };
}
