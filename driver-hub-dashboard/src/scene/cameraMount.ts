import * as THREE from 'three';
import type { ScenePoint } from './frame';

/**
 * The camera's mount as geometry: where the lens sits on the chassis model and where it looks.
 *
 * <p>Two frames meet here, which is the entire reason this is a module and not four lines inside a
 * component. The mount is quoted in the <em>robot frame</em> the configuration file uses —
 * {@code +X} out the nose, {@code +Y} to the robot's left, {@code +Z} up — while the chassis model
 * is drawn in its own local frame, with its nose along local {@code -Z} and up along local
 * {@code +Y} (see {@code NOSE_OFFSET_RADIANS} in {@link ./frame} for why the model points that
 * way). Both frames are right-handed, so the third axis is not a choice: in the robot frame
 * {@code up × nose = left}, and in the model's frame {@code (0,1,0) × (0,0,-1) = (-1,0,0)}, so the
 * robot's left is local {@code -X}. That gives the whole mapping —</p>
 *
 * <pre>
 *   forward -> local -Z      left -> local -X      height -> local +Y
 * </pre>
 *
 * <p>— and a crossed sign in it draws a camera on the wrong side of a symmetric robot, which looks
 * entirely plausible until someone trusts it. {@link cameraFrustum} is asserted against the three
 * cases that catch it: forward, pitched up, yawed left.</p>
 */

/** Metres from the lens to the far face of the drawn pyramid. Long enough to read, short enough not to fill the field. */
export const FRUSTUM_LENGTH_METRES = 0.35;

/** Where the camera is and where it points, in the units {@code sim/camera} carries. */
export interface MountAim {
  forwardMetres: number;
  leftMetres: number;
  heightMetres: number;
  /** CCW from the nose, looking down on the robot. */
  yawDegrees: number;
  /** Positive tips the lens up towards the ceiling. */
  pitchDegrees: number;
  /** About the optical axis, right-handed: positive leans the picture's top towards the right. */
  rollDegrees: number;
  horizontalFovDegrees: number;
  verticalFovDegrees: number;
}

export interface CameraFrustum {
  /** The lens, in the chassis model's local frame. */
  apex: ScenePoint;
  /** Unit vector the camera looks along, in the same frame. */
  axis: ScenePoint;
  /**
   * The four rays along the corners of the picture, unit length, ordered
   * {@code [topLeft, topRight, bottomRight, bottomLeft]} as the camera itself sees them — so
   * joining them in order draws the far face once, without a diagonal.
   */
  corners: [ScenePoint, ScenePoint, ScenePoint, ScenePoint];
}

/** A point on the robot, in the frame the configuration file quotes, as a chassis-local point. */
export function mountPoint(
  forwardMetres: number,
  leftMetres: number,
  heightMetres: number,
): ScenePoint {
  return [-leftMetres, heightMetres, -forwardMetres];
}

/**
 * The pyramid a mount and a field of view describe, in the chassis model's local frame.
 *
 * <p>The three angles are applied as the mount reads them: yaw swings the aim about the robot's up
 * axis, pitch lifts it about the camera's own right axis, and roll spins the picture about the
 * optical axis without moving the axis at all. Rolling last is what makes roll mean "rotate the
 * picture" rather than "aim somewhere else".</p>
 */
export function cameraFrustum(mount: MountAim): CameraFrustum {
  const apex = mountPoint(mount.forwardMetres, mount.leftMetres, mount.heightMetres);

  // Chassis-local axes of the robot frame, from the mapping in this module's header. The mapping
  // is a proper rotation (its determinant is +1), so a rotation sense survives it: yaw stays
  // positive-CCW about local up, and a pitch that lifts the nose stays positive about local right.
  const nose = new THREE.Vector3(0, 0, -1);
  const up = new THREE.Vector3(0, 1, 0);
  // The robot's left is local -X, so the robot's right — the axis pitch turns about — is local +X.
  const right = new THREE.Vector3(1, 0, 0);

  // Pitch first, then yaw: yawing about up afterwards carries the pitched axis with it, which is
  // the same thing as pitching about the yawed right axis, and avoids naming a second basis.
  const aim = new THREE.Quaternion()
    .setFromAxisAngle(up, THREE.MathUtils.degToRad(mount.yawDegrees))
    .multiply(
      new THREE.Quaternion().setFromAxisAngle(right, THREE.MathUtils.degToRad(mount.pitchDegrees)),
    );

  const axis = nose.clone().applyQuaternion(aim);
  const rolled = new THREE.Quaternion().setFromAxisAngle(
    axis,
    THREE.MathUtils.degToRad(mount.rollDegrees),
  );
  const pictureUp = up.clone().applyQuaternion(aim).applyQuaternion(rolled);
  const pictureRight = right.clone().applyQuaternion(aim).applyQuaternion(rolled);

  // Half-angles: the corner is a tangent out to each edge of the picture, not an angle to be added.
  const acrossPicture = Math.tan(THREE.MathUtils.degToRad(mount.horizontalFovDegrees) / 2);
  const downPicture = Math.tan(THREE.MathUtils.degToRad(mount.verticalFovDegrees) / 2);
  const corner = (rightward: number, upward: number): ScenePoint => {
    const ray = axis
      .clone()
      .addScaledVector(pictureRight, rightward * acrossPicture)
      .addScaledVector(pictureUp, upward * downPicture)
      .normalize();
    return [ray.x, ray.y, ray.z];
  };

  return {
    apex,
    axis: [axis.x, axis.y, axis.z],
    corners: [corner(-1, 1), corner(1, 1), corner(1, -1), corner(-1, -1)],
  };
}
