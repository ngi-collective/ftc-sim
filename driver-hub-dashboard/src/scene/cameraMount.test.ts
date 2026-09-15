import { describe, expect, it } from 'vitest';
import * as THREE from 'three';
import simCameraFrame from '../../../protocol-fixtures/sim-camera.json';
import { cameraFrustum, mountPoint, type MountAim } from './cameraMount';

/**
 * The one risk in drawing a camera from a mount is a crossed axis, and the reason it is a risk is
 * that a crossed axis looks fine. A robot is nearly symmetric, so a camera drawn on the wrong side
 * or aimed at the ceiling instead of the floor is a plausible-looking picture that answers "is this
 * a good angle?" with a lie — and the whole point of the live editor is to answer that question by
 * eye. So these tests are about direction, in the words the mount is written in.
 *
 * <p>The chassis model's nose is its own local {@code -Z} and its up is local {@code +Y}; the mount
 * is quoted in the robot frame. Every expectation below is stated against those two facts rather
 * than against the mapping the module derives from them.</p>
 */

/** The chassis model's own nose direction, from {@code NOSE_OFFSET_RADIANS} in frame.ts. */
const NOSE = new THREE.Vector3(0, 0, -1);
const UP = new THREE.Vector3(0, 1, 0);
/** Right-handed with the two above: the robot's left, which is where a +90° yaw must look. */
const LEFT = UP.clone().cross(NOSE);

const LEVEL: MountAim = {
  forwardMetres: 0,
  leftMetres: 0,
  heightMetres: 0.2,
  yawDegrees: 0,
  pitchDegrees: 0,
  rollDegrees: 0,
  horizontalFovDegrees: 60,
  verticalFovDegrees: 46.8,
};

function vector(point: [number, number, number]): THREE.Vector3 {
  return new THREE.Vector3(...point);
}

describe('the mount as a point on the chassis model', () => {
  it('puts a camera mounted forward ahead of the chassis centre, on the side the nose points', () => {
    const mount = simCameraFrame.payload;
    const { apex } = cameraFrustum(mount);
    expect(mount.forwardMetres).toBeGreaterThan(0);
    // Projected onto the model's own nose direction, so a swapped or unnegated Z fails here
    // instead of hiding a camera inside the robot's tail.
    expect(vector(apex).dot(NOSE)).toBeCloseTo(mount.forwardMetres, 9);
    expect(apex[1]).toBeCloseTo(mount.heightMetres, 9);
  });

  it('puts a camera mounted left on the same side of the model that a +90° yaw looks at', () => {
    // The two halves of the left/right story, checked against each other: a sign error in either
    // one alone would leave a camera that is drawn on the port side and aims to starboard.
    const apex = vector(mountPoint(0, 0.22, 0.1));
    expect(apex.dot(LEFT)).toBeCloseTo(0.22, 9);

    const { axis } = cameraFrustum({ ...LEVEL, yawDegrees: 90 });
    expect(vector(axis).dot(LEFT)).toBeCloseTo(1, 9);
  });
});

describe('where the mount aims', () => {
  it('aims a pitched-up mount above the horizon, by the angle the mount names', () => {
    // "pitch positive upward" is the promise the configuration file makes, and the sign of a
    // rotation about the camera's right axis is where it is kept or broken.
    const { axis } = cameraFrustum(simCameraFrame.payload);
    expect(simCameraFrame.payload.pitchDegrees).toBe(35);
    expect(vector(axis).dot(UP)).toBeCloseTo(Math.sin(THREE.MathUtils.degToRad(35)), 9);
    // Still looking out the front: pitching up must not tip the aim over the top.
    expect(vector(axis).dot(NOSE)).toBeGreaterThan(0);
  });

  it('leaves the aim alone when the camera is rolled, and turns the picture instead', () => {
    // Squared off on purpose: only a square picture has corners a quarter turn apart, which makes
    // "the picture turned 90°" an expectation about one named corner rather than about a tangent.
    const square = { ...LEVEL, horizontalFovDegrees: 50, verticalFovDegrees: 50 };
    const level = cameraFrustum(square);
    const rolled = cameraFrustum({ ...square, rollDegrees: 90 });
    expect(vector(rolled.axis).angleTo(vector(level.axis))).toBeCloseTo(0, 9);
    // A quarter turn leans the picture's top to the right, so its top-left corner lands where its
    // top-right corner was.
    expect(vector(rolled.corners[0]).angleTo(vector(level.corners[1]))).toBeCloseTo(0, 9);
  });
});

describe('the drawn frustum', () => {
  it('opens to the field of view the camera reports, wider across than it is tall', () => {
    // Swapping the two angles is the other plausible mistake, and a 60×46.8 camera drawn 46.8
    // wide would make every framing decision taken through this view slightly wrong.
    const mount = simCameraFrame.payload;
    const { axis, corners } = cameraFrustum(mount);
    const [topLeft, topRight, bottomRight] = corners.map(vector);
    const topEdge = topLeft.clone().add(topRight).normalize();
    const rightEdge = topRight.clone().add(bottomRight).normalize();

    expect(THREE.MathUtils.radToDeg(vector(axis).angleTo(topEdge))).toBeCloseTo(
      mount.verticalFovDegrees / 2,
      6,
    );
    expect(THREE.MathUtils.radToDeg(vector(axis).angleTo(rightEdge))).toBeCloseTo(
      mount.horizontalFovDegrees / 2,
      6,
    );
  });
});
