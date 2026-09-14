import * as THREE from 'three';
import type { SceneCorner } from '../protocol';

/**
 * The one conversion between the FTC field frame and the three.js scene frame.
 *
 * <p>Everything the server sends is in the field frame the autonomous code plans in: origin at the
 * centre of the field, metres, {@code +X} across the field, {@code +Y} ninety degrees
 * counter-clockwise from it, {@code +Z} straight up, and a heading in degrees measured
 * CCW-positive from {@code +X}. three.js draws with {@code Y} up, so the field's two ground axes
 * cannot both stay where they are: the scene keeps the field's {@code X}, lifts the field's
 * {@code Z} into scene {@code Y}, and takes scene {@code Z} as the <em>negated</em> field
 * {@code Y}. That one negation is the whole handedness story — both frames are right-handed, but
 * with different axes pointing up, and mirroring a single axis is what reconciles them. Drop the
 * minus and the field is mirrored: every autonomous path drawn against it comes out reflected, and
 * a robot commanded to strafe left is drawn strafing right.</p>
 *
 * <p>The heading needs a second term, and only one. A field heading is CCW-positive about field
 * {@code +Z}, and a positive rotation about scene {@code +Y} carries scene {@code +X} to scene
 * {@code -Z} — which is exactly where field {@code +Y} went — so the field's rotation direction
 * survives the axis mirror and the heading keeps its sign. What does not survive is where the
 * chassis model points: its nose is modelled along its own local {@code -Z}, and a rotation of
 * {@code theta} about {@code +Y} carries local {@code -Z} to {@code (-sin theta, 0, -cos theta)}.
 * Heading 0 means facing field {@code +X}, so the nose has to land on scene {@code +X}, and only
 * {@code theta = -pi/2} does that. So {@link sceneYaw} is a unit change plus the model's nose
 * offset, nothing more — the handedness flip is already paid for.</p>
 *
 * <p>This module exists because that reasoning used to be re-derived at four call sites inside
 * {@code useFrame} callbacks and pointer handlers, where it could not be tested and where a
 * disagreement between copies would show up only as a robot driving sideways relative to its own
 * planned path.</p>
 */

/** A point in the scene frame, in the {@code [x, y, z]} order three.js and R3F props take. */
export type ScenePoint = [x: number, y: number, z: number];

/**
 * Radians the chassis model is turned by at heading 0, because its nose is modelled along local
 * {@code -Z} rather than along the scene's {@code +X}.
 */
export const NOSE_OFFSET_RADIANS = -Math.PI / 2;

/** A field point — tag corner, element centre, anything with a height — as a scene point. */
export function scenePoint(point: SceneCorner): ScenePoint {
  return [point.x, point.z, -point.y];
}

/** The inverse of {@link scenePoint}: scene {@code Y} is height, scene {@code Z} is negated. */
export function fieldPoint(point: ScenePoint): SceneCorner {
  return { x: point[0], y: -point[2], z: point[1] };
}

/** A field position on the floor — a robot pose, a ground hit — as a scene point at {@code y = 0}. */
export function sceneGround(x: number, y: number): ScenePoint {
  return [x, 0, -y];
}

/**
 * The inverse of {@link sceneGround}, dropping the height: what a pointer ray that hit the floor
 * plane means in the frame the robot is placed in.
 */
export function fieldGround(sceneX: number, sceneZ: number): { x: number; y: number } {
  return { x: sceneX, y: -sceneZ };
}

/** The rotation about scene {@code +Y} that aims the chassis model along a field heading. */
export function sceneYaw(headingDegrees: number): number {
  return THREE.MathUtils.degToRad(headingDegrees) + NOSE_OFFSET_RADIANS;
}
