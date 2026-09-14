package org.ngicollective.testframework.camera;

import org.junit.jupiter.api.Test;
import org.ngicollective.testframework.sim.Pose2d;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * What the rasteriser actually puts on the pixels.
 *
 * <p>The cell assertions here deliberately do not ask the rasteriser where its cells went. They
 * work out each cell's centre as a point in space, project it with {@link CameraView}, and look at
 * that pixel. So the rasteriser's two-dimensional homography is checked against independent
 * three-dimensional geometry, and a rasteriser that quietly used an affine map &mdash; which
 * cannot foreshorten a tilted tag &mdash; fails rather than merely looking a bit wrong.</p>
 */
class TagRasteriserTest {

    private static final CameraIntrinsics LENS = CameraIntrinsics.of(640, 480, 500.0);
    private static final SimulatedCamera CAMERA =
            new SimulatedCamera("Webcam 1", LENS, Pose3d.facingForward(Vec3.ZERO));

    /** Mid grey, so both black cells and the white quiet zone stand out from the background. */
    private static final int BACKGROUND = 128;

    @Test
    void everyCellLandsWhereTheGeometryPutsItAndCarriesTheRightShade() {
        FieldTag tag = new FieldTag(30, 0.3,
                Pose3d.ofDegrees(new Vec3(1.0, 0.0, 0.0), 180.0, 0.0, 0.0));

        assertCellsMatch(tag, "a tag square-on to the camera");
    }

    @Test
    void aStronglyTiltedTagKeepsItsPerspective() {
        // Sixty degrees off square: the near edge of this tag projects about 40 px taller than the
        // far edge, so an affine mapping would misplace whole cells near the far edge.
        FieldTag tag = new FieldTag(30, 0.3,
                Pose3d.ofDegrees(new Vec3(1.0, 0.0, 0.0), 240.0, 0.0, 0.0));

        CameraView view = CAMERA.viewFrom(Pose2d.ORIGIN);
        Pixel[] corners = view.project(tag).corners();
        double nearEdge = heightOf(corners[1], corners[2]);
        double farEdge = heightOf(corners[0], corners[3]);
        assertTrue(nearEdge > farEdge * 1.2,
                "the near edge should be visibly taller: " + nearEdge + " vs " + farEdge);

        assertCellsMatch(tag, "a tag tilted sixty degrees");
    }

    @Test
    void theQuietZoneIsWhiteAndStopsThere() {
        FieldTag tag = new FieldTag(30, 0.3,
                Pose3d.ofDegrees(new Vec3(1.0, 0.0, 0.0), 180.0, 0.0, 0.0));
        CameraView view = CAMERA.viewFrom(Pose2d.ORIGIN);
        SyntheticFrame frame = render(view, tag);

        // Square-on at 1 m, a 0.3 m tag spans 150 px, so a cell is 18.75 px. The tag's black
        // square runs 245..395 in x, and the quiet zone is one cell outside that.
        int justOutside = 245 - 9;
        int middle = 240;
        assertTrue(frame.luminanceAt(justOutside, middle) > 200,
                "the margin around the black square must be white, or the detector cannot find "
                        + "the square's edge at all");

        int wellOutside = 245 - 28;
        assertEquals(BACKGROUND, frame.luminanceAt(wellOutside, middle),
                "beyond the quiet zone the tag must leave the background alone");
    }

    @Test
    void aTagFacingAwayDrawsNothing() {
        FieldTag facingAway = new FieldTag(30, 0.3,
                Pose3d.facingForward(new Vec3(1.0, 0.0, 0.0)));
        CameraView view = CAMERA.viewFrom(Pose2d.ORIGIN);
        SyntheticFrame frame = new SyntheticFrame(LENS.width(), LENS.height());
        frame.fillGrey(BACKGROUND);

        assertFalse(TagRasteriser.draw(frame, view.project(facingAway)));
        for (int y = 0; y < frame.height(); y += 16) {
            for (int x = 0; x < frame.width(); x += 16) {
                assertEquals(BACKGROUND, frame.luminanceAt(x, y),
                        "pixel (" + x + ", " + y + ") should be untouched");
            }
        }
    }

    @Test
    void theFieldDrawnBehindATagLeavesItsPixelsAlone() {
        // What the whole renderer rests on: adding a floor and four walls must not disturb one
        // pixel of a tag. Anything that shifted them would move the corners the detector refines
        // to a fraction of a pixel, and with them the range it solves.
        FieldTag tag = new FieldTag(30, 0.3,
                Pose3d.ofDegrees(new Vec3(1.0, 0.0, 0.0), 180.0, 0.0, 0.0));
        TagCluster cluster = new TagCluster("TEST", tag.pose(),
                Collections.singletonList(new TagCluster.Member(30, 0.0, 0.0, 0.0, 0.3)));
        CameraView view = CAMERA.viewFrom(Pose2d.ORIGIN);

        SyntheticFrame overVoid = render(view, tag);
        SyntheticFrame overField = new SyntheticFrame(LENS.width(), LENS.height());
        assertEquals(1, new SimulatedScene(Collections.singletonList(cluster),
                Collections.<GameElement>emptyList()).renderInto(overField, view));

        // There is genuinely a field in the second frame: the perimeter's slate fills the rows
        // above the horizon, where the void render has nothing but grey.
        assertTrue(overField.blueAt(100, 200) > overField.redAt(100, 200) + 20,
                "the perimeter wall should have been drawn behind the tag");

        // Every cell still the shade the three-dimensional geometry says it is.
        assertCellsMatch(tag, overField, view, "a tag over the rendered field");

        // And the pixels themselves. Square on at 1 m the black square runs 245..395 across and
        // 165..315 down; inside it the tag is opaque, so the two frames must agree exactly.
        for (int y = 167; y <= 313; y++) {
            for (int x = 247; x <= 393; x++) {
                if (overVoid.redAt(x, y) != overField.redAt(x, y)
                        || overVoid.greenAt(x, y) != overField.greenAt(x, y)
                        || overVoid.blueAt(x, y) != overField.blueAt(x, y)) {
                    fail("pixel (" + x + ", " + y + ") inside the tag changed when a field was "
                            + "drawn behind it");
                }
            }
        }
    }

    /** Renders the tag over an empty grey frame, then checks every one of its 64 cells. */
    private static void assertCellsMatch(FieldTag tag, String what) {
        CameraView view = CAMERA.viewFrom(Pose2d.ORIGIN);
        assertCellsMatch(tag, render(view, tag), view, what);
    }

    /**
     * Checks each cell of an already-rendered tag by projecting that cell's centre from
     * three-dimensional space and reading the pixel it lands on.
     */
    private static void assertCellsMatch(FieldTag tag, SyntheticFrame frame, CameraView view,
                                         String what) {
        int cells = Tag36h11.CELLS_ACROSS;
        double cellSize = tag.sizeMetres() / cells;
        double half = tag.sizeMetres() / 2.0;

        for (int row = 0; row < cells; row++) {
            for (int column = 0; column < cells; column++) {
                // Cell columns run with the image, and tag +X runs against it, so column 0 sits
                // at +half. Same for rows against tag +Y.
                double alongX = half - (column + 0.5) * cellSize;
                double alongY = half - (row + 0.5) * cellSize;
                Vec3 centre = tag.pose().position()
                        .plus(tag.tagX().scaled(alongX))
                        .plus(tag.tagY().scaled(alongY));

                Pixel pixel = view.project(centre);
                int luminance = frame.luminanceAt((int) Math.round(pixel.x()),
                        (int) Math.round(pixel.y()));
                boolean white = Tag36h11.isWhite(tag.id(), row, column);
                if (white) {
                    assertTrue(luminance > 200, what + ": cell (" + row + ", " + column
                            + ") should be white, was " + luminance);
                } else {
                    assertTrue(luminance < 55, what + ": cell (" + row + ", " + column
                            + ") should be black, was " + luminance);
                }
            }
        }
    }

    private static SyntheticFrame render(CameraView view, FieldTag tag) {
        SyntheticFrame frame = new SyntheticFrame(LENS.width(), LENS.height());
        frame.fillGrey(BACKGROUND);
        assertTrue(TagRasteriser.draw(frame, view.project(tag)), "the tag should have been drawn");
        return frame;
    }

    private static double heightOf(Pixel top, Pixel bottom) {
        double dx = top.x() - bottom.x();
        double dy = top.y() - bottom.y();
        return Math.sqrt(dx * dx + dy * dy);
    }
}
