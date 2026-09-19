import * as THREE from 'three';
import type { SceneSolid } from '../protocol';
import { scenePoint, type ScenePoint } from './frame';

/**
 * A published solid — one box or one cylinder out of a structure — as the numbers three.js draws
 * it from.
 *
 * <p>Two conventions meet here and neither of them is negotiable, which is why this is a module
 * and not four lines inside a component. The server quotes a solid in the FTC field frame, turned
 * by the yaw/pitch/roll the camera mount is also quoted in; three.js draws with {@code Y} up and
 * builds its cylinders about that {@code Y}. So a solid's orientation is three rotations composed
 * in a documented order, carried through the field-to-scene rotation {@code frame.ts} owns, and
 * then — for a cylinder only — stood on end, because the wire says a cylinder's axis is its own
 * local {@code +Z} and {@code CylinderGeometry} says otherwise.</p>
 *
 * <p>None of that can be checked by looking at the canvas. A FLOWER tube lying on its side, a
 * pitch applied the wrong way up, a box whose depth and height are swapped: every one of them
 * draws something, and something is what a developer will believe. This repository has no way to
 * assert what WebGL painted, so the arithmetic behind the painting lives here, pure and exported,
 * and is asserted in Node — the same reasoning that put the frame conversion in {@code frame.ts}
 * and the body interpolation in {@code bodies.ts}.</p>
 *
 * <p>The mesh's local frame <em>is</em> the solid's local frame: a box's {@code lengthX} is drawn
 * along mesh {@code X} and the whole field-to-scene rotation is paid for once, in the quaternion.
 * Swapping the box's dimensions instead and leaving the rotation alone draws the same thing only
 * while every angle is zero, which is exactly the case a screenshot is taken of.</p>
 */

/** A quaternion in the {@code (x, y, z, w)} order three.js and R3F props take. */
export type SceneQuaternion = [x: number, y: number, z: number, w: number];

/** Where a solid sits and which way it is turned, ready for a mesh's two props. */
export interface SolidPose {
  position: ScenePoint;
  quaternion: SceneQuaternion;
}

/**
 * The geometry a solid is drawn as, discriminated exactly as the wire is.
 *
 * <p>Plain numbers rather than a {@code THREE.BufferGeometry}: what is worth asserting is which
 * published length became which dimension, and a built geometry answers that question only by
 * reading its vertices back.</p>
 */
export type SolidGeometry =
  | { shape: 'box'; sizeMetres: [x: number, y: number, z: number] }
  | { shape: 'cylinder'; radiusMetres: number; lengthMetres: number };

/**
 * The field frame's axes, as the three angles turn about them.
 *
 * <p>Yaw is CCW-positive about field {@code +Z} and pitch is positive <em>upward</em>, which means
 * pitch turns about the solid's rightward axis — field {@code -Y} at zero yaw, since the field
 * frame is right-handed with {@code +Y} to the left. Stating it as a positive rotation about the
 * right rather than a negative one about the left is the same rotation and one fewer minus sign to
 * lose; Java writes the same thing as {@code Ry(-pitch)}.</p>
 */
const FIELD_UP = new THREE.Vector3(0, 0, 1);
const FIELD_RIGHT = new THREE.Vector3(0, -1, 0);
const FIELD_FORWARD = new THREE.Vector3(1, 0, 0);

/**
 * The rotation that carries the field frame onto the scene frame.
 *
 * <p>{@link scenePoint} maps a field point {@code (x, y, z)} to {@code (x, z, -y)}, and that map
 * is a proper rotation — a quarter turn about {@code X}, determinant {@code +1}, not a mirror — so
 * an orientation may be carried across by composing with it and a rotation sense survives the
 * crossing. A minus in the wrong place here is the one failure this module cannot draw its way out
 * of: the field would be mirrored and every structure on it would look entirely reasonable.</p>
 */
const FIELD_TO_SCENE = new THREE.Quaternion().setFromAxisAngle(
  new THREE.Vector3(1, 0, 0),
  -Math.PI / 2,
);

/**
 * Stands a {@code CylinderGeometry} up: three.js builds it about mesh {@code Y}, the wire says the
 * axis is the solid's local {@code +Z}, and a quarter turn about {@code X} is the difference.
 *
 * <p>Applied last, inside the solid's own frame, so it cannot disturb the pose the server sent.
 * Note what it composes to for an upright cylinder: this and {@link FIELD_TO_SCENE} cancel, and a
 * ring lying flat on the field is drawn with no rotation at all — which is the sanity check that
 * the two quarter turns are not the same quarter turn twice.</p>
 */
const CYLINDER_AXIS = new THREE.Quaternion().setFromAxisAngle(
  new THREE.Vector3(1, 0, 0),
  Math.PI / 2,
);

/**
 * Where and how a solid is drawn, in the scene frame.
 *
 * <p>Yaw, then pitch, then roll, each about the axis the previous ones left it pointing at —
 * composing them right-to-left in that order is what makes roll mean "spin this in place" and
 * pitch mean "lift its nose", rather than three turns about the field's own axes that happen to
 * agree while two of the three angles are zero.</p>
 */
export function solidPose(solid: SceneSolid): SolidPose {
  const turn = new THREE.Quaternion();
  const oriented = FIELD_TO_SCENE.clone()
    .multiply(turn.setFromAxisAngle(FIELD_UP, THREE.MathUtils.degToRad(solid.yawDegrees)))
    .multiply(turn.setFromAxisAngle(FIELD_RIGHT, THREE.MathUtils.degToRad(solid.pitchDegrees)))
    .multiply(turn.setFromAxisAngle(FIELD_FORWARD, THREE.MathUtils.degToRad(solid.rollDegrees)));
  if (solid.shape === 'cylinder') oriented.multiply(CYLINDER_AXIS);

  return {
    position: scenePoint(solid),
    quaternion: [oriented.x, oriented.y, oriented.z, oriented.w],
  };
}

/**
 * Which of the published lengths a solid is actually sized by.
 *
 * <p>The unused pair is zero on the wire, so consulting the wrong one costs a solid that is drawn
 * with no extent rather than one drawn with someone else's — invisible, which is at least
 * honest.</p>
 */
export function solidGeometry(solid: SceneSolid): SolidGeometry {
  if (solid.shape === 'cylinder') {
    return {
      shape: 'cylinder',
      radiusMetres: solid.radiusMetres,
      lengthMetres: solid.lengthMetres,
    };
  }
  return { shape: 'box', sizeMetres: [solid.lengthX, solid.lengthY, solid.lengthZ] };
}

/**
 * Identity of a geometry, for sharing one buffer between every solid that wants it.
 *
 * <p>Worth the string: a structure is the same few primitives repeated. The captured field's four
 * FLOWERs are two dozen cylinders between them, in a handful of distinct sizes, and one buffer per
 * solid would be two dozen uploads of the same ring — and two dozen things to dispose on the next
 * scene republish, which arrives on every INIT.</p>
 */
export function solidGeometryKey(geometry: SolidGeometry): string {
  return geometry.shape === 'box'
    ? `box:${geometry.sizeMetres.join(',')}`
    : `cylinder:${geometry.radiusMetres},${geometry.lengthMetres}`;
}
