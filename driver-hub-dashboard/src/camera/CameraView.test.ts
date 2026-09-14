import { describe, expect, it } from 'vitest';
import pose from '../../../protocol-fixtures/sim-pose.json';
import { describePose } from './CameraView';

/**
 * The one line of numbers the camera panel puts under the picture.
 *
 * <p>Worth pinning because it is read against the picture: a driver checking whether the tag they
 * can see is the tag they expect is comparing this readout to the field, and two of its three
 * numbers are metres on axes that look alike. The feed's status word is not tested — it says
 * "live" or "no signal" next to a picture that is either there or not.</p>
 */
describe('describePose', () => {
  it('reads the position and heading off the pose, in that order', () => {
    expect(describePose({ ...pose.payload, x: 1.234, y: -0.567, headingDegrees: 89.6 })).toBe(
      'at 1.23, -0.57 facing 90°',
    );
  });

  it('tells the axes apart', () => {
    // x and y are both field-frame metres, so a swap reads as a plausible position on the other
    // side of the field rather than as a broken readout.
    expect(describePose({ ...pose.payload, x: 1, y: 2 })).not.toBe(
      describePose({ ...pose.payload, x: 2, y: 1 }),
    );
  });

  it('describes a captured pose without losing its sign', () => {
    // The capture has the robot behind and left of centre; a readout that dropped a minus would
    // put it diagonally across the field from where it is.
    expect(describePose(pose.payload)).toBe('at -0.33, -0.37 facing 30°');
  });
});
