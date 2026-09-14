package org.ngicollective.testframework.camera;

import org.junit.jupiter.api.Test;
import org.ngicollective.testframework.sim.Pose2d;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** How a whole scene composes: colour, size, and what hides what. */
class SimulatedSceneTest {

    private static final CameraIntrinsics LENS = CameraIntrinsics.of(640, 480, 500.0);
    private static final SimulatedCamera CAMERA =
            new SimulatedCamera("Webcam 1", LENS, Pose3d.facingForward(new Vec3(0.0, 0.0, 0.05)));

    @Test
    void eachKindOfBallKeepsItsOwnColour() {
        // The contract the colour processors depend on: a red NECTAR has to come out red enough
        // for a threshold to find it, and not so desaturated that it reads as grey floor.
        GameElement[] balls = {
                GameElement.redNectar(1.0, 0.0),
                GameElement.blueNectar(1.0, 0.0),
                GameElement.pollen(1.0, 0.0),
        };
        String[] expected = {"red", "blue", "yellow"};

        for (int i = 0; i < balls.length; i++) {
            SimulatedScene scene = new SimulatedScene(
                    Collections.<TagCluster>emptyList(), Collections.singletonList(balls[i]));
            SyntheticFrame frame = new SyntheticFrame(LENS.width(), LENS.height());
            scene.renderInto(frame, CAMERA.viewFrom(Pose2d.ORIGIN));

            Pixel centre = CAMERA.viewFrom(Pose2d.ORIGIN).project(balls[i].centre());
            int x = (int) Math.round(centre.x());
            int y = (int) Math.round(centre.y());
            int red = frame.redAt(x, y);
            int green = frame.greenAt(x, y);
            int blue = frame.blueAt(x, y);

            if ("red".equals(expected[i])) {
                assertTrue(red > green * 3 && red > blue * 3,
                        "red NECTAR read as (" + red + ", " + green + ", " + blue + ")");
            } else if ("blue".equals(expected[i])) {
                assertTrue(blue > red * 3 && blue > green * 2,
                        "blue NECTAR read as (" + red + ", " + green + ", " + blue + ")");
            } else {
                assertTrue(red > blue * 3 && green > blue * 3,
                        "POLLEN read as (" + red + ", " + green + ", " + blue + ")");
            }
        }
    }

    @Test
    void aBallCoversTheAreaItsRangeImplies() {
        // 91 mm NECTAR at 1 m through a 500 px lens: a radius of 22.75 px, so about 1626 px of
        // red. A blob detector measures area, so an area that drifts is a silently wrong test
        // fixture rather than a cosmetic issue.
        GameElement nectar = GameElement.redNectar(1.0, 0.0);
        SimulatedScene scene = new SimulatedScene(
                Collections.<TagCluster>emptyList(), Collections.singletonList(nectar));
        SyntheticFrame frame = new SyntheticFrame(LENS.width(), LENS.height());
        scene.renderInto(frame, CAMERA.viewFrom(Pose2d.ORIGIN));

        int reddish = 0;
        for (int y = 0; y < frame.height(); y++) {
            for (int x = 0; x < frame.width(); x++) {
                if (frame.redAt(x, y) > 150 && frame.greenAt(x, y) < 100) {
                    reddish++;
                }
            }
        }

        double expectedRadius = LENS.focalX() * nectar.radiusMetres() / 1.0;
        double expectedArea = Math.PI * expectedRadius * expectedRadius;
        assertEquals(expectedArea, reddish, expectedArea * 0.02,
                "expected about " + Math.round(expectedArea) + " px of ball");
    }

    @Test
    void aBallInFrontOfATagHidesIt() {
        // A robot's own intake blocking its view of a tag is ordinary. An OpMode that assumed a
        // tag stays visible should fail here rather than at a competition.
        FieldTag tag = new FieldTag(30, 0.2,
                Pose3d.ofDegrees(new Vec3(2.0, 0.0, 0.05), 180.0, 0.0, 0.0));
        TagCluster cluster = new TagCluster("TEST", tag.pose(),
                Collections.singletonList(new TagCluster.Member(30, 0.0, 0.0, 0.0, 0.2)));
        GameElement blocker = new GameElement("BLOCKER", new Vec3(0.5, 0.0, 0.05), 0.3,
                200, 30, 40);

        SimulatedScene scene = new SimulatedScene(
                Collections.singletonList(cluster), Collections.singletonList(blocker));
        SyntheticFrame frame = new SyntheticFrame(LENS.width(), LENS.height());
        int tagsDrawn = scene.renderInto(frame, CAMERA.viewFrom(Pose2d.ORIGIN));

        assertEquals(1, tagsDrawn, "the tag is still in frame, just covered");
        // Dead centre is where both the tag and the nearer ball land.
        assertTrue(frame.redAt(320, 240) > 150 && frame.greenAt(320, 240) < 100,
                "the nearer ball should be painted over the tag, not under it");
    }

    @Test
    void tippingAHiveMovesAllOfItsTagsTogether() {
        // BioBuzz's signature event. The cluster is the thing that moves, so its members cannot
        // drift apart: two booleans of tip state can't describe a physically impossible field.
        TagCluster cluster = new TagCluster("RED AUDIENCE",
                Pose3d.ofDegrees(new Vec3(0.0, 0.5, 1.2), -90.0, -60.0, 0.0),
                Arrays.asList(
                        new TagCluster.Member(34, -0.1651, 0.18256, -0.1428, 0.08255),
                        new TagCluster.Member(35, -0.06985, 0.18256, -0.1428, 0.08255)));
        SimulatedScene upright = SimulatedScene.of(cluster);
        double spacingBefore = spacingOf(upright);

        SimulatedScene tipped = upright.withCluster("RED AUDIENCE",
                Pose3d.ofDegrees(new Vec3(0.0, 0.35, 1.1), -90.0, -30.0, 0.0));

        assertEquals(spacingBefore, spacingOf(tipped), 1e-9,
                "tipping must move the plate, not stretch it");
        // What tipping means: the plate's face swings from 60 degrees below horizontal to 30,
        // so every member tag now looks somewhere new. Its height is deliberately not asserted
        // here, because a shallower plate can raise a tag even as the HIVE it hangs from drops.
        assertEquals(-0.866, normalZOf(upright), 1e-3);
        assertEquals(-0.500, normalZOf(tipped), 1e-3);
    }

    private static double spacingOf(SimulatedScene scene) {
        java.util.List<FieldTag> tags = scene.clusters().get(0).tags();
        return tags.get(0).pose().position().minus(tags.get(1).pose().position()).length();
    }

    /** How far below horizontal a cluster's tags face, as the z of their visible normal. */
    private static double normalZOf(SimulatedScene scene) {
        return scene.clusters().get(0).tags().get(0).visibleNormal().z();
    }
}
