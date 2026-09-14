package org.ngicollective.testframework.camera;

import org.junit.jupiter.api.Test;
import org.ngicollective.testframework.sim.Pose2d;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The geometry the whole simulated camera rests on, asserted against numbers worked out by hand.
 *
 * <p>These run on a plain JVM in milliseconds, which is the point: a sign error in a rotation is
 * cheap to find here and expensive to find in an emulator, where it arrives as "the detector
 * returned nothing" four minutes later.</p>
 */
class TagProjectionTest {

    private static final double INCH = 0.0254;

    /** Round numbers, so every expected pixel below can be arrived at with a calculator. */
    private static final CameraIntrinsics LENS = CameraIntrinsics.of(640, 480, 500.0);

    /** At the robot's origin, level, pointing out the nose. */
    private static final SimulatedCamera NOSE_CAM =
            new SimulatedCamera("Webcam 1", LENS, Pose3d.facingForward(Vec3.ZERO));

    /**
     * BioBuzz's cluster geometry, mirroring {@code AprilTagGameDatabase.getBioBuzzCluster}: four
     * 3.25 in tags, unevenly spaced because they straddle the CELL's centre post.
     */
    private static final double TAG_SIZE = 3.25 * INCH;
    private static final double[] MEMBER_OFFSETS_X =
            {-6.50 * INCH, -2.75 * INCH, 2.75 * INCH, 6.50 * INCH};
    private static final double MEMBER_OFFSET_Y = 7.1874 * INCH;
    private static final double MEMBER_OFFSET_Z = -5.622 * INCH;

    @Test
    void aTagStraightAheadProjectsToTheCornersTheDetectorWouldReport() {
        // 100 mm tag, 2 m ahead, facing back down the camera's axis.
        FieldTag tag = new FieldTag(30, 0.1,
                Pose3d.ofDegrees(new Vec3(2.0, 0.0, 0.0), 180.0, 0.0, 0.0));

        TagQuad quad = NOSE_CAM.viewFrom(Pose2d.ORIGIN).project(tag);

        assertTrue(quad.isVisible());
        Pixel[] corners = quad.corners();
        // 0.05 m of tag at 2 m through a 500 px focal length is 12.5 px from centre. The ordering
        // is the load-bearing part: feeding a canonical tag36h11 image to the apriltag library
        // puts corners[0] at the image's top-RIGHT and corners[1] at the top-LEFT, because the
        // SDK maps corners[0] to tag (-s/2, +s/2) and tag +X points leftward as seen.
        assertPixel(332.5, 227.5, corners[0], "corners[0] belongs at the top right");
        assertPixel(307.5, 227.5, corners[1], "corners[1] belongs at the top left");
        assertPixel(307.5, 252.5, corners[2], "corners[2] belongs at the bottom left");
        assertPixel(332.5, 252.5, corners[3], "corners[3] belongs at the bottom right");
    }

    @Test
    void aTagTurnedAwayFromTheCameraIsNotVisible() {
        // Same place, but facing +X: the camera is behind the plate, looking at its blank back.
        FieldTag facingAway = new FieldTag(30, 0.1,
                Pose3d.facingForward(new Vec3(2.0, 0.0, 0.0)));

        assertFalse(NOSE_CAM.viewFrom(Pose2d.ORIGIN).project(facingAway).isVisible(),
                "a tag printed on the far side of its plate cannot be seen through it");
    }

    @Test
    void aTagBehindTheCameraIsNotVisible() {
        FieldTag behind = new FieldTag(30, 0.1,
                Pose3d.facingForward(new Vec3(-2.0, 0.0, 0.0)));

        assertFalse(NOSE_CAM.viewFrom(Pose2d.ORIGIN).project(behind).isVisible());
    }

    @Test
    void onlyTheCameraRelativeGeometryDecidesThePixels() {
        // BioBuzz tags move, so FIRST says they are unsuitable for absolute localization. The
        // renderer had better agree: identical relative geometry must give identical pixels,
        // wherever on the field it happens.
        FieldTag near = new FieldTag(30, 0.1,
                Pose3d.ofDegrees(new Vec3(2.0, 0.0, 0.0), 180.0, 0.0, 0.0));
        Pixel[] reference = NOSE_CAM.viewFrom(Pose2d.ORIGIN).project(near).corners();

        FieldTag translated = new FieldTag(30, 0.1,
                Pose3d.ofDegrees(new Vec3(3.0, 0.0, 0.0), 180.0, 0.0, 0.0));
        Pixel[] shifted = NOSE_CAM.viewFrom(new Pose2d(1.0, 0.0, 0.0))
                .project(translated).corners();

        // Robot spun a quarter turn, tag moved to match: also the same image.
        FieldTag rotated = new FieldTag(30, 0.1,
                Pose3d.ofDegrees(new Vec3(0.0, 2.0, 0.0), -90.0, 0.0, 0.0));
        Pixel[] turned = NOSE_CAM.viewFrom(new Pose2d(0.0, 0.0, Math.PI / 2))
                .project(rotated).corners();

        for (int i = 0; i < 4; i++) {
            assertPixel(reference[i].x(), reference[i].y(), shifted[i],
                    "driving forward one metre toward a tag one metre further away");
            assertPixel(reference[i].x(), reference[i].y(), turned[i],
                    "turning the robot and the tag together");
        }
    }

    @Test
    void aMountedCameraTurnsAndTranslatesWithTheRobot() {
        // 200 mm ahead of the robot's origin, 250 mm up: a mast, not a pinhole at the centre.
        SimulatedCamera mast = new SimulatedCamera("Webcam 1", LENS,
                Pose3d.facingForward(new Vec3(0.2, 0.0, 0.25)));

        Pose3d onField = mast.viewFrom(new Pose2d(1.0, 2.0, Math.PI / 2)).pose();

        // Quarter turn left puts the nose along +Y, so the mast offset swings from +X to +Y.
        assertEquals(1.0, onField.position().x(), 1e-9);
        assertEquals(2.2, onField.position().y(), 1e-9);
        assertEquals(0.25, onField.position().z(), 1e-9, "a robot on the floor cannot change its"
                + " camera's height by turning");
        assertEquals(0.0, onField.forward().x(), 1e-9);
        assertEquals(1.0, onField.forward().y(), 1e-9);
    }

    @Test
    void aBioBuzzClusterPlacesItsTagsInOneUnevenlySpacedRow() {
        TagCluster cluster = redAudienceCluster(new Vec3(0.0, 0.5, 1.2));

        List<FieldTag> tags = cluster.tags();

        assertEquals(Arrays.asList(34, 35, 36, 37),
                Arrays.asList(tags.get(0).id(), tags.get(1).id(), tags.get(2).id(),
                        tags.get(3).id()));
        // The plate faces -Y and downward, so its +X axis lies along the field's -X: ids ascend
        // toward -X. Worked by hand from the SDK's offsets.
        double[] expectedX = {0.1651, 0.06985, -0.06985, -0.1651};
        for (int i = 0; i < 4; i++) {
            FieldTag tag = tags.get(i);
            assertEquals(expectedX[i], tag.pose().position().x(), 1e-4,
                    "tag " + tag.id() + " sits across the row");
            // One row: every member shares the height and depth its offsets put it at.
            assertEquals(0.270499, tag.pose().position().y(), 1e-4);
            assertEquals(1.167651, tag.pose().position().z(), 1e-4);
            assertEquals(TAG_SIZE, tag.sizeMetres(), 1e-9);
        }

        // Manual figure 9-15: the inner pair straddles the centre post, so the gaps are 3.75 in,
        // 5.5 in, 3.75 in. Even spacing here would mean the offsets had been "tidied up".
        assertEquals(3.75 * INCH, gapBetween(tags, 0, 1), 1e-6);
        assertEquals(5.50 * INCH, gapBetween(tags, 1, 2), 1e-6);
        assertEquals(3.75 * INCH, gapBetween(tags, 2, 3), 1e-6);
    }

    @Test
    void aBioBuzzCameraHasToAimUpToSeeACluster() {
        // Clusters hang under the CELLs facing the floor. A camera that only yaws is useless here,
        // which is the whole reason the mount carries six degrees of freedom.
        TagCluster cluster = redAudienceCluster(new Vec3(0.0, 0.5, 1.2));
        List<FieldTag> tags = cluster.tags();

        SimulatedCamera level = new SimulatedCamera("Webcam 1", LENS,
                Pose3d.ofDegrees(new Vec3(0.0, 0.0, 0.3), 90.0, 0.0, 0.0));
        CameraView levelView = level.viewFrom(Pose2d.ORIGIN);
        for (FieldTag tag : tags) {
            TagQuad quad = levelView.project(tag);
            assertTrue(quad.isVisible(), "the plate still faces the lens");
            assertFalse(levelView.boundsOverlapFrame(quad),
                    "but tag " + tag.id() + " is far above a level camera's frame");
        }

        SimulatedCamera aimedUp = new SimulatedCamera("Webcam 1", LENS,
                Pose3d.ofDegrees(new Vec3(0.0, 0.0, 0.3), 90.0, 60.0, 0.0));
        CameraView upView = aimedUp.viewFrom(Pose2d.ORIGIN);
        for (FieldTag tag : tags) {
            assertTrue(upView.boundsOverlapFrame(upView.project(tag)),
                    "tag " + tag.id() + " should be in frame once the camera aims up");
        }
    }

    @Test
    void tagIdsAscendRightToLeftInTheImage() {
        // The consequence of tag +X pointing leftward as seen. It looks like a mirroring bug the
        // first time anyone sees it, so it is pinned here on purpose: if a future change makes
        // ids read left-to-right, either this convention or the renderer has been flipped.
        TagCluster cluster = redAudienceCluster(new Vec3(0.0, 0.5, 1.2));
        CameraView view = new SimulatedCamera("Webcam 1", LENS,
                Pose3d.ofDegrees(new Vec3(0.0, 0.0, 0.3), 90.0, 60.0, 0.0))
                .viewFrom(Pose2d.ORIGIN);

        double previousCentreX = Double.MAX_VALUE;
        for (FieldTag tag : cluster.tags()) {
            double centreX = centreOf(view.project(tag)).x();
            assertTrue(centreX < previousCentreX,
                    "tag " + tag.id() + " should appear to the left of the previous id");
            previousCentreX = centreX;
        }
    }

    @Test
    void theApproximateWebcamIsTheSixtyDegreeLensItClaimsToBe() {
        // The renderer projects through these intrinsics and the SDK's pose solver is handed the
        // matching calibration. If the two ways of describing "an ordinary webcam" drift apart,
        // every simulated tag sits at the wrong range, consistently and silently.
        CameraIntrinsics approximate = CameraIntrinsics.approximate(640, 480);
        CameraIntrinsics fromFov = CameraIntrinsics.fromHorizontalFieldOfView(640, 480, 60.0);

        // 0.02 px: the 0.866 ratio is a four-digit rounding of sqrt(3)/2, and nothing more.
        assertEquals(fromFov.focalX(), approximate.focalX(), 0.02);
        assertEquals(fromFov.focalY(), approximate.focalY(), 0.02);
    }

    /**
     * The Red Audience cluster's orientation, from the field CAD: the plate faces the audience
     * side and thirty degrees off horizontal, which is a normal sixty degrees below the horizon.
     */
    private static TagCluster redAudienceCluster(Vec3 origin) {
        Pose3d pose = Pose3d.ofDegrees(origin, -90.0, -60.0, 0.0);
        List<TagCluster.Member> members = Arrays.asList(
                new TagCluster.Member(34, MEMBER_OFFSETS_X[0], MEMBER_OFFSET_Y, MEMBER_OFFSET_Z,
                        TAG_SIZE),
                new TagCluster.Member(35, MEMBER_OFFSETS_X[1], MEMBER_OFFSET_Y, MEMBER_OFFSET_Z,
                        TAG_SIZE),
                new TagCluster.Member(36, MEMBER_OFFSETS_X[2], MEMBER_OFFSET_Y, MEMBER_OFFSET_Z,
                        TAG_SIZE),
                new TagCluster.Member(37, MEMBER_OFFSETS_X[3], MEMBER_OFFSET_Y, MEMBER_OFFSET_Z,
                        TAG_SIZE));
        return new TagCluster("RED AUDIENCE", pose, members);
    }

    private static double gapBetween(List<FieldTag> tags, int first, int second) {
        return tags.get(first).pose().position()
                .minus(tags.get(second).pose().position()).length();
    }

    private static Pixel centreOf(TagQuad quad) {
        Pixel[] corners = quad.corners();
        double x = 0.0;
        double y = 0.0;
        for (Pixel corner : corners) {
            x += corner.x();
            y += corner.y();
        }
        return new Pixel(x / corners.length, y / corners.length);
    }

    private static void assertPixel(double expectedX, double expectedY, Pixel actual,
                                    String what) {
        assertEquals(expectedX, actual.x(), 1e-9, what + ": x");
        assertEquals(expectedY, actual.y(), 1e-9, what + ": y");
    }
}
