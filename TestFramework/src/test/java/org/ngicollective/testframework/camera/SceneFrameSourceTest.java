package org.ngicollective.testframework.camera;

import org.junit.jupiter.api.Test;
import org.ngicollective.testframework.sim.Pose2d;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** That the camera obeys the OpMode's choice of resolution, and follows the robot. */
class SceneFrameSourceTest {

    private static TagCluster clusterAhead() {
        return new TagCluster("AHEAD",
                Pose3d.ofDegrees(new Vec3(2.0, 0.0, 0.1), 180.0, 0.0, 0.0),
                Collections.singletonList(new TagCluster.Member(30, 0.0, 0.0, 0.0, 0.15)));
    }

    private static SimulatedCamera camera() {
        return new SimulatedCamera("Webcam 1", CameraIntrinsics.approximate(640, 480),
                Pose3d.facingForward(new Vec3(0.0, 0.0, 0.1)));
    }

    @Test
    void aDifferentResolutionKeepsTheSameLens() {
        // An OpMode picks its own resolution. The lens does not change, so what the camera can see
        // must not either: a tag filling a third of the frame still fills a third of it. Scaling
        // the focal length wrongly would never throw, it would just report every tag at the wrong
        // range, in proportion.
        SceneFrameSource source = new SceneFrameSource(SimulatedScene.of(clusterAhead()), camera());

        double smallFraction = tagWidthAsFractionOfFrame(source, 320, 240);
        double largeFraction = tagWidthAsFractionOfFrame(source, 1280, 960);

        assertEquals(smallFraction, largeFraction, 0.005,
                "the tag should cover the same fraction of the frame at any resolution");
        assertEquals(60.0, camera().intrinsics().resizedTo(1280, 960)
                .horizontalFieldOfViewDegrees(), 0.01);
    }

    @Test
    void theViewFollowsTheRobot() {
        final Pose2d[] pose = {Pose2d.ORIGIN};
        SceneFrameSource source = new SceneFrameSource(SimulatedScene.of(clusterAhead()), camera(),
                new SceneFrameSource.PoseSource() {
                    @Override
                    public Pose2d pose() {
                        return pose[0];
                    }
                });
        SyntheticFrame frame = new SyntheticFrame(640, 480);

        source.render(frame);
        double centred = tagCentreX(frame);

        // Turn the robot a little to the left: the tag ahead should slide to the right of frame.
        pose[0] = new Pose2d(0.0, 0.0, Math.toRadians(10.0));
        source.render(frame);
        double turned = tagCentreX(frame);

        assertTrue(turned > centred + 50.0,
                "turning left should push a tag ahead toward the right of the frame; was "
                        + centred + " then " + turned);
    }

    @Test
    void swappingTheSceneChangesTheNextFrame() {
        // How a HIVE tipping reaches the camera: the camera is never told, it just renders what
        // the scene now says.
        SceneFrameSource source = new SceneFrameSource(SimulatedScene.of(clusterAhead()), camera());
        SyntheticFrame frame = new SyntheticFrame(640, 480);

        source.render(frame);
        assertTrue(hasDarkPixels(frame), "the tag should be in the first frame");

        source.setScene(new SimulatedScene(Collections.<TagCluster>emptyList(),
                Collections.<GameElement>emptyList()));
        source.render(frame);

        assertTrue(!hasDarkPixels(frame), "with the scene emptied, nothing should be drawn");
    }

    /** How wide the tag's dark pixels are, as a fraction of the frame. */
    private static double tagWidthAsFractionOfFrame(SceneFrameSource source, int width,
                                                    int height) {
        SyntheticFrame frame = new SyntheticFrame(width, height);
        source.render(frame);

        int left = width;
        int right = -1;
        for (int x = 0; x < width; x++) {
            if (frame.luminanceAt(x, height / 2) < 60) {
                left = Math.min(left, x);
                right = Math.max(right, x);
            }
        }
        assertTrue(right > left, "the tag should be visible at " + width + "x" + height);
        return (right - left) / (double) width;
    }

    private static double tagCentreX(SyntheticFrame frame) {
        long sum = 0;
        long count = 0;
        for (int y = 0; y < frame.height(); y++) {
            for (int x = 0; x < frame.width(); x++) {
                if (frame.luminanceAt(x, y) < 60) {
                    sum += x;
                    count++;
                }
            }
        }
        assertTrue(count > 0, "expected some dark tag pixels");
        return sum / (double) count;
    }

    private static boolean hasDarkPixels(SyntheticFrame frame) {
        for (int y = 0; y < frame.height(); y += 2) {
            for (int x = 0; x < frame.width(); x += 2) {
                if (frame.luminanceAt(x, y) < 60) {
                    return true;
                }
            }
        }
        return false;
    }
}
