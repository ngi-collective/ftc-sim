package org.ngicollective.testframework.camera;

import org.junit.jupiter.api.Test;
import org.ngicollective.testframework.sim.Pose2d;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Moving the camera on the robot moves what it sees, one degree of freedom at a time.
 *
 * <p>Every expectation here is the pinhole model worked out by hand rather than a recording of
 * what the renderer did. A test that only asserted "the picture changed" would pass with a mount
 * wired to the wrong axis or with a sign inverted, and that is the whole family of mistakes worth
 * defending against here: the arithmetic is six lines long and its symptom is "the detector sees
 * nothing", four minutes later, in an emulator.</p>
 *
 * <p>The robot stays at the field origin throughout, so the only thing moving is the mount.
 * {@code TagProjectionTest} covers the other half &mdash; a mount composed with a robot that has
 * driven somewhere.</p>
 */
class CameraMountTest {

    /** Round numbers, so every expected pixel below can be arrived at with a calculator. */
    private static final CameraIntrinsics LENS = CameraIntrinsics.of(640, 480, 500.0);

    private static final double FOCAL_PIXELS = 500.0;
    private static final double CENTRE_X = 320.0;
    private static final double CENTRE_Y = 240.0;

    /** On the camera's axis, two metres out the robot's nose, at the height of an unmoved mount. */
    private static final Vec3 AHEAD = new Vec3(2.0, 0.0, 0.0);

    /** The same distance away, 200 mm to the robot's left: an offset a roll can be seen to turn. */
    private static final Vec3 AHEAD_AND_LEFT = new Vec3(2.0, 0.2, 0.0);

    /** Exact arithmetic on both sides, so this is a floating-point tolerance, not a fudge. */
    private static final double PIXEL = 1e-6;

    @Test
    void movingTheCameraForwardOnTheRobotMakesATagFillMorePixels() {
        // 100 mm tag, two metres out the nose, facing back down the camera's axis.
        FieldTag tag = new FieldTag(30, 0.1, Pose3d.ofDegrees(AHEAD, 180.0, 0.0, 0.0));

        double fromTheCentre = tagWidthInPixels(tag, mount(0.0, 0.0, 0.0, 0.0, 0.0, 0.0));
        double fromTheNose = tagWidthInPixels(tag, mount(1.0, 0.0, 0.0, 0.0, 0.0, 0.0));

        // 0.1 m of tag at 2 m through a 500 px focal length is 25 px across. Bolting the camera a
        // metre further forward halves the range, so it is 50 px across: this is the degree of
        // freedom that cannot be seen by watching the image centre, which never moves.
        assertEquals(25.0, fromTheCentre, PIXEL);
        assertEquals(50.0, fromTheNose, PIXEL);
    }

    @Test
    void mountingTheCameraToTheRobotsLeftMovesWhatItSeesToTheRight() {
        Pixel seen = sees(mount(0.0, 0.2, 0.0, 0.0, 0.0, 0.0), AHEAD);

        // The point did not move, the camera did: 0.2 m of offset at 2 m of depth is
        // 500 * 0.2 / 2 = 50 px, and a camera that steps to its own left sees the world step right.
        assertEquals(CENTRE_X + 50.0, seen.x(), PIXEL);
        assertEquals(CENTRE_Y, seen.y(), PIXEL, "a sideways mount cannot raise or lower the view");
    }

    @Test
    void raisingTheCameraOnTheRobotMovesWhatItSeesDown() {
        Pixel seen = sees(mount(0.0, 0.0, 0.2, 0.0, 0.0, 0.0), AHEAD);

        // Same 50 px, now in the axis that matters on BioBuzz: the tags are overhead, and a mount
        // height that did nothing would be a camera aimed at the wrong part of the field.
        assertEquals(CENTRE_Y + 50.0, seen.y(), PIXEL);
        assertEquals(CENTRE_X, seen.x(), PIXEL, "raising a camera cannot move the view sideways");
    }

    @Test
    void yawingTheCameraToTheRobotsLeftMovesWhatItSeesToTheRight() {
        Pixel seen = sees(mount(0.0, 0.0, 0.0, 10.0, 0.0, 0.0), AHEAD);

        // A turn, not a translation: the shift is f * tan(angle) and depends on nothing else, so
        // the same ten degrees moves a point at any range by the same 88.16 px.
        assertEquals(CENTRE_X + FOCAL_PIXELS * Math.tan(Math.toRadians(10.0)), seen.x(), PIXEL);
        assertEquals(CENTRE_Y, seen.y(), PIXEL, "a level camera cannot tilt the view by yawing");
    }

    @Test
    void pitchingTheCameraUpMovesWhatItSeesDown() {
        Pixel seen = sees(mount(0.0, 0.0, 0.0, 0.0, 10.0, 0.0), AHEAD);

        // Pitch is positive upward, which is what lets a mount aim at tags on the underside of a
        // CELL. Get this sign backwards and the configured 35 degrees points at the floor.
        assertEquals(CENTRE_Y + FOCAL_PIXELS * Math.tan(Math.toRadians(10.0)), seen.y(), PIXEL);
        assertEquals(CENTRE_X, seen.x(), PIXEL, "pitching a camera cannot move the view sideways");
    }

    @Test
    void rollingTheCameraTurnsTheImageAboutItsCentre() {
        Pixel upright = sees(mount(0.0, 0.0, 0.0, 0.0, 0.0, 0.0), AHEAD_AND_LEFT);
        Pixel rolled = sees(mount(0.0, 0.0, 0.0, 0.0, 0.0, 90.0), AHEAD_AND_LEFT);

        // 0.2 m to the left at 2 m is 50 px left of the centre.
        assertEquals(CENTRE_X - 50.0, upright.x(), PIXEL);
        assertEquals(CENTRE_Y, upright.y(), PIXEL);

        // A quarter turn about the optical axis carries that offset from left to down while keeping
        // its distance from the centre. A roll turns the picture; it does not move the camera.
        assertEquals(CENTRE_X, rolled.x(), PIXEL);
        assertEquals(CENTRE_Y + 50.0, rolled.y(), PIXEL);
    }

    private static Pixel sees(Pose3d mount, Vec3 fieldPoint) {
        return new SimulatedCamera("Webcam 1", LENS, mount)
                .viewFrom(Pose2d.ORIGIN)
                .project(fieldPoint);
    }

    /** Top-right corner to top-left corner: how wide the tag is in the image. */
    private static double tagWidthInPixels(FieldTag tag, Pose3d mount) {
        Pixel[] corners = new SimulatedCamera("Webcam 1", LENS, mount)
                .viewFrom(Pose2d.ORIGIN)
                .project(tag)
                .corners();
        return corners[0].x() - corners[1].x();
    }

    /** A mount in the units the configuration file uses: metres on the robot, then degrees. */
    private static Pose3d mount(double forwardMetres, double leftMetres, double heightMetres,
                                double yawDegrees, double pitchDegrees, double rollDegrees) {
        return Pose3d.ofDegrees(new Vec3(forwardMetres, leftMetres, heightMetres),
                yawDegrees, pitchDegrees, rollDegrees);
    }
}
