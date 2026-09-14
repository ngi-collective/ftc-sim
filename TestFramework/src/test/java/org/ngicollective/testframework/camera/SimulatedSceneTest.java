package org.ngicollective.testframework.camera;

import org.junit.jupiter.api.Test;
import org.ngicollective.testframework.sim.FieldConfig;
import org.ngicollective.testframework.sim.Pose2d;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** How a whole scene composes: colour, size, the field it stands on, and what hides what. */
class SimulatedSceneTest {

    private static final CameraIntrinsics LENS = CameraIntrinsics.of(640, 480, 500.0);
    private static final SimulatedCamera CAMERA =
            new SimulatedCamera("Webcam 1", LENS, Pose3d.facingForward(new Vec3(0.0, 0.0, 0.05)));

    /** Distance from the field's centre to its perimeter, for the competition field. */
    private static final double HALF_FIELD = FieldConfig.standard().halfExtentMetres();

    /**
     * The grey beyond the perimeter, for the scenes below.
     *
     * <p>Brighter than any tile or wall, so "this pixel is not the field" needs no tolerance.</p>
     */
    private static final int SKY = 200;

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

    @Test
    void aCameraPitchedDownSeesTheFloorBelowTheHorizonAndNothingAboveIt() {
        SimulatedCamera downward = new SimulatedCamera("Webcam 1", LENS,
                Pose3d.ofDegrees(new Vec3(0.0, 0.0, 0.5), 0.0, -20.0, 0.0));
        CameraView view = downward.viewFrom(Pose2d.ORIGIN);
        SyntheticFrame frame = new SyntheticFrame(LENS.width(), LENS.height());
        fieldWith().renderInto(frame, view);

        // Where a horizontal ray vanishes. Every part of the field is below this row, because the
        // perimeter wall is lower than the camera, so anything drawn above it is a floor plane
        // that was projected without being clipped first.
        int horizon = (int) Math.round(view.project(new Vec3(1e6, 0.0, 0.5)).y());
        for (int y = 0; y < horizon - 2; y++) {
            for (int x = 0; x < frame.width(); x += 7) {
                assertEquals(SKY, frame.luminanceAt(x, y),
                        "pixel (" + x + ", " + y + ") is above the horizon");
            }
        }

        // Two tiles side by side, sampled where the geometry says their centres project.
        double pitch = FieldConfig.standard().tileMetres();
        int[] near = pixelOf(view, new Vec3(1.5 * pitch, 0.5 * pitch, 0.0));
        int[] far = pixelOf(view, new Vec3(2.5 * pitch, 0.5 * pitch, 0.0));

        assertTrue(Math.abs(frame.luminanceAt(near[0], near[1])
                        - frame.luminanceAt(far[0], far[1])) > 15,
                "adjacent tiles must differ, or a driver counting tiles has nothing to count");
        for (int[] tile : new int[][] {near, far}) {
            int luminance = frame.luminanceAt(tile[0], tile[1]);
            assertTrue(luminance > 60,
                    "a floor as dark as a tag's black square makes every threshold ambiguous; "
                            + "was " + luminance);
            assertTrue(frame.blueAt(tile[0], tile[1]) > frame.redAt(tile[0], tile[1]) + 20,
                    "the floor should be the field view's slate, not grey");
        }
    }

    @Test
    void eachAllianceEndCarriesItsOwnColour() {
        // The stations are on the X axis, red at -X: the field CAD, via BioBuzzField, which is the
        // same authority the season's tags are placed from. A camera that disagreed would tell an
        // OpMode it was facing the wrong alliance.
        assertEndColour(180.0, -HALF_FIELD, "red");
        assertEndColour(0.0, HALF_FIELD, "blue");
    }

    @Test
    void aTagInFrontOfAWallDrawsOverIt() {
        // The field's surfaces take part in the same depth sort as everything else. Get that
        // backwards and the perimeter paints over the tags hanging in front of it, which reads as
        // a detector that has stopped working.
        FieldTag tag = new FieldTag(30, 0.2,
                Pose3d.ofDegrees(new Vec3(-HALF_FIELD + 0.3, 0.0, 0.15), 0.0, 0.0, 0.0));
        TagCluster cluster = new TagCluster("TEST", tag.pose(),
                Collections.singletonList(new TagCluster.Member(30, 0.0, 0.0, 0.0, 0.2)));
        CameraView view = CAMERA.viewFrom(new Pose2d(0.0, 0.0, Math.toRadians(180.0)));

        SyntheticFrame withTag = new SyntheticFrame(LENS.width(), LENS.height());
        assertEquals(1, fieldWith(cluster).renderInto(withTag, view));
        SyntheticFrame wallOnly = new SyntheticFrame(LENS.width(), LENS.height());
        fieldWith().renderInto(wallOnly, view);

        int[] cell = pixelOf(view, aBlackCellCentre(tag));
        assertTrue(withTag.luminanceAt(cell[0], cell[1]) < 55,
                "a black cell in front of the wall should be black, was "
                        + withTag.luminanceAt(cell[0], cell[1]));
        assertTrue(wallOnly.redAt(cell[0], cell[1]) > 2 * wallOnly.greenAt(cell[0], cell[1]),
                "without the tag that pixel is the red wall, which is what the tag covered");
    }

    /** That a camera turned to the given heading sees an alliance colour on the wall behind it. */
    private static void assertEndColour(double headingDegrees, double wallX, String alliance) {
        CameraView view = CAMERA.viewFrom(new Pose2d(0.0, 0.0, Math.toRadians(headingDegrees)));
        SyntheticFrame frame = new SyntheticFrame(LENS.width(), LENS.height());
        fieldWith().renderInto(frame, view);

        int[] pixel = pixelOf(view, new Vec3(wallX, 0.0, 0.15));
        int red = frame.redAt(pixel[0], pixel[1]);
        int green = frame.greenAt(pixel[0], pixel[1]);
        int blue = frame.blueAt(pixel[0], pixel[1]);
        String seen = "(" + red + ", " + green + ", " + blue + ")";
        if ("red".equals(alliance)) {
            assertTrue(red > 2 * green && red > 2 * blue, "the red end read as " + seen);
        } else {
            assertTrue(blue > 2 * red && blue > green, "the blue end read as " + seen);
        }
    }

    /** The competition field with these clusters on it and nothing else. */
    private static SimulatedScene fieldWith(TagCluster... clusters) {
        return new SimulatedScene(Arrays.asList(clusters),
                Collections.<GameElement>emptyList(), SKY);
    }

    private static int[] pixelOf(CameraView view, Vec3 fieldPoint) {
        Pixel pixel = view.project(fieldPoint);
        return new int[] {(int) Math.round(pixel.x()), (int) Math.round(pixel.y())};
    }

    /**
     * The centre of one of the tag's inner black cells, in the field frame.
     *
     * <p>Inner, so the pixel it lands on is well away from the tag's antialiased edge.</p>
     */
    private static Vec3 aBlackCellCentre(FieldTag tag) {
        int cells = Tag36h11.CELLS_ACROSS;
        double cellSize = tag.sizeMetres() / cells;
        double half = tag.sizeMetres() / 2.0;
        for (int row = 1; row < cells - 1; row++) {
            for (int column = 1; column < cells - 1; column++) {
                if (!Tag36h11.isWhite(tag.id(), row, column)) {
                    return tag.pose().position()
                            .plus(tag.tagX().scaled(half - (column + 0.5) * cellSize))
                            .plus(tag.tagY().scaled(half - (row + 0.5) * cellSize));
                }
            }
        }
        throw new IllegalStateException("tag " + tag.id() + " has no inner black cell");
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
