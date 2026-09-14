package org.ngicollective.testframework.camera;

import org.junit.jupiter.api.Test;
import org.ngicollective.testframework.sim.Pose2d;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What happens to a surface that does not fit in front of the camera.
 *
 * <p>The cases here are the ones a rasteriser that projected its corners and filled the result
 * would get catastrophically wrong rather than slightly wrong: a plane running back underneath the
 * lens has corners whose projection is meaningless, and using them anyway paints a plausible
 * polygon over entirely the wrong half of the image. Every assertion is against pixels the
 * geometry says must or must not be touched, not against the clipper's own idea of its output.</p>
 */
class SurfaceRasteriserTest {

    private static final CameraIntrinsics LENS = CameraIntrinsics.of(640, 480, 500.0);

    /** Level, at the field origin, half a metre above the surfaces used here. */
    private static final SimulatedCamera CAMERA =
            new SimulatedCamera("Webcam 1", LENS, Pose3d.facingForward(new Vec3(0.0, 0.0, 0.5)));

    private static final int BACKGROUND = 128;

    /** Nothing like the background or the frame's grey, so a stray fill is unmistakable. */
    private static final int RED = 10;
    private static final int GREEN = 200;
    private static final int BLUE = 90;

    /** A 6 m square of floor, centred on the origin: it runs back well behind the camera. */
    private static Surface floor() {
        return new Surface(new Vec3[] {
                new Vec3(-3.0, -3.0, 0.0), new Vec3(3.0, -3.0, 0.0),
                new Vec3(3.0, 3.0, 0.0), new Vec3(-3.0, 3.0, 0.0),
        }, RED, GREEN, BLUE);
    }

    @Test
    void aSurfaceEntirelyBehindTheCameraDrawsNothing() {
        Surface behind = new Surface(new Vec3[] {
                new Vec3(-3.0, -1.0, 0.0), new Vec3(-1.0, -1.0, 0.0),
                new Vec3(-1.0, 1.0, 0.0), new Vec3(-3.0, 1.0, 0.0),
        }, RED, GREEN, BLUE);
        CameraView view = CAMERA.viewFrom(Pose2d.ORIGIN);
        SyntheticFrame frame = blank();

        assertFalse(SurfaceRasteriser.draw(frame, view, behind));
        assertUntouched(frame, 0, frame.height() - 1);
    }

    @Test
    void aSurfaceStraddlingTheNearPlaneDrawsOnlyThePartInFront() {
        // Two headings, because how many corners are behind the camera changes with it: square on,
        // two of the floor's corners are behind and the clipped shape is another quad; at
        // forty-five degrees only one is, and the clip has to hand back a five-sided polygon. A
        // clipper that dropped the corner it gained would leave a wedge of background across the
        // floor, and one that projected the corners as given would paint the floor over the sky.
        double[] headings = {0.0, 45.0};
        for (double heading : headings) {
            CameraView view = CAMERA.viewFrom(new Pose2d(0.0, 0.0, Math.toRadians(heading)));
            SyntheticFrame frame = blank();

            assertTrue(SurfaceRasteriser.draw(frame, view, floor()),
                    "the floor in front of the camera should be drawn at " + heading + " degrees");

            // The camera is level and the floor is below it, so every pixel of it belongs below
            // the horizon, which for a centred principal point is the middle row.
            assertUntouched(frame, 0, (int) LENS.centreY() - 1);

            // Three points on the floor straight ahead, and the pixel directly under the camera,
            // which exists in the image only because the part behind the near plane was clipped
            // away rather than the whole surface being abandoned.
            for (double ahead : new double[] {1.5, 2.0, 2.5}) {
                Vec3 point = view.pose().position()
                        .plus(view.pose().forward().scaled(ahead))
                        .plus(new Vec3(0.0, 0.0, -0.5));
                Pixel pixel = view.project(point);
                assertFill(frame, (int) Math.round(pixel.x()), (int) Math.round(pixel.y()),
                        "the floor " + ahead + " m ahead at " + heading + " degrees");
            }
            assertFill(frame, frame.width() / 2, frame.height() - 1,
                    "the floor under the camera at " + heading + " degrees");
        }
    }

    @Test
    void aSurfaceSeenExactlyEdgeOnDrawsNothing() {
        // A camera lying on the floor sees it as a line. There is nothing there to draw, and the
        // homography-free fill would otherwise walk a bounding box spanning the whole frame.
        SimulatedCamera onTheFloor =
                new SimulatedCamera("Webcam 1", LENS, Pose3d.facingForward(Vec3.ZERO));
        SyntheticFrame frame = blank();

        assertFalse(SurfaceRasteriser.draw(frame, onTheFloor.viewFrom(Pose2d.ORIGIN), floor()));
        assertUntouched(frame, 0, frame.height() - 1);
    }

    private static SyntheticFrame blank() {
        SyntheticFrame frame = new SyntheticFrame(LENS.width(), LENS.height());
        frame.fillGrey(BACKGROUND);
        return frame;
    }

    private static void assertFill(SyntheticFrame frame, int x, int y, String what) {
        assertEquals(RED, frame.redAt(x, y), what + ": red at (" + x + ", " + y + ")");
        assertEquals(GREEN, frame.greenAt(x, y), what + ": green at (" + x + ", " + y + ")");
        assertEquals(BLUE, frame.blueAt(x, y), what + ": blue at (" + x + ", " + y + ")");
    }

    private static void assertUntouched(SyntheticFrame frame, int firstRow, int lastRow) {
        for (int y = firstRow; y <= lastRow; y++) {
            for (int x = 0; x < frame.width(); x++) {
                assertEquals(BACKGROUND, frame.luminanceAt(x, y),
                        "pixel (" + x + ", " + y + ") should be untouched");
            }
        }
    }
}
