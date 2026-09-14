package org.ngicollective.testframework.camera;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.ngicollective.testframework.sim.FieldConfig;
import org.ngicollective.testframework.sim.Pose2d;

/**
 * What the background beyond the field has to do, and what it must not.
 *
 * <p>It exists so that a frame with no tag in it still says the robot is moving. That is a real
 * requirement rather than decoration: this camera is pitched up at the overhead tags, so most of
 * every frame is past the perimeter, and a background that cannot change reads as a frozen
 * view.</p>
 */
class GymSurroundingsTest {

    private static final CameraIntrinsics LENS = CameraIntrinsics.approximate(320, 240);

    /** The team's camera: aimed up at the tags, which is what makes the background so prominent. */
    private static final SimulatedCamera CAMERA = new SimulatedCamera("Webcam 1", LENS,
            Pose3d.ofDegrees(new Vec3(0.16, 0.0, 0.2), 0.0, 35.0, 0.0));

    private static SyntheticFrame renderedFrom(Pose2d pose) {
        SyntheticFrame frame = new SyntheticFrame(LENS.width(), LENS.height());
        SimulatedScene.of().on(FieldConfig.standard())
                .renderInto(frame, CAMERA.viewFrom(pose));
        return frame;
    }

    /**
     * The complaint this scenery answers: a driver turning on the spot with no tag in shot could
     * not tell the view was live.
     */
    @Test
    void turningTheRobotChangesTheBackground() {
        SyntheticFrame straight = renderedFrom(Pose2d.ORIGIN);
        SyntheticFrame turned = renderedFrom(new Pose2d(0.0, 0.0, Math.toRadians(12.0)));

        assertTrue(changedFraction(straight, turned) > 0.2,
                "a twelve degree turn changed only "
                        + Math.round(changedFraction(straight, turned) * 100)
                        + "% of the frame, which is not enough for a person to see motion");
    }

    /** Driving changes it too, not just turning: parallax, not a pattern painted on the lens. */
    @Test
    void drivingChangesTheBackground() {
        SyntheticFrame near = renderedFrom(Pose2d.ORIGIN);
        SyntheticFrame far = renderedFrom(new Pose2d(1.2, 0.0, 0.0));

        assertTrue(changedFraction(near, far) > 0.1,
                "driving 1.2 m changed only "
                        + Math.round(changedFraction(near, far) * 100) + "% of the frame");
    }

    /**
     * Scenery must stay invisible to the pipeline. A saturated backdrop would hand
     * {@code ColorBlobLocatorProcessor} a blob the size of a game element that no OpMode could
     * know was wallpaper.
     *
     * <p>Asserted on the panels themselves rather than on a rendered frame: what is in shot
     * depends on where the camera points, and a test that passed only because the field happened
     * to be out of frame would stop meaning this the day somebody re-aimed the camera.</p>
     */
    @Test
    void everyPanelIsNeutralSoAColourProcessorSeesNothing() {
        for (Surface panel : GymSurroundings.of(FieldConfig.standard())) {
            int spread = Math.max(panel.red(), Math.max(panel.green(), panel.blue()))
                    - Math.min(panel.red(), Math.min(panel.green(), panel.blue()));
            assertTrue(spread <= 4, "a panel at (" + panel.red() + ", " + panel.green() + ", "
                    + panel.blue() + ") is saturated enough to be mistaken for a game element");
        }
    }

    /**
     * And invisible to a threshold. Below 60 a panel reads as a tag's black square and above 200
     * as its white quiet zone, to every test and OpMode in this repo that thresholds.
     */
    @Test
    void everyPanelStaysClearOfTheTagThresholds() {
        for (Surface panel : GymSurroundings.of(FieldConfig.standard())) {
            int luminance = (panel.red() + panel.green() + panel.blue()) / 3;
            assertTrue(luminance > 70 && luminance < 190,
                    "a panel at luminance " + luminance + " sits near where a tag's own cells are "
                            + "thresholded");
        }
    }

    /**
     * Texture means adjacent panels differ, and differ by little. Identical panels are the flat
     * backdrop this replaced; boldly different ones feed the tag detector quad candidates that can
     * never carry a codeword.
     */
    @Test
    void panelsDifferEnoughToSeeAndLittleEnoughToIgnore() {
        int darkest = 255;
        int brightest = 0;
        for (Surface panel : GymSurroundings.of(FieldConfig.standard())) {
            darkest = Math.min(darkest, panel.red());
            brightest = Math.max(brightest, panel.red());
        }
        assertTrue(brightest - darkest >= 20,
                "the room's panels span only " + (brightest - darkest) + " levels, which reads as "
                        + "one flat colour");
        assertTrue(brightest - darkest <= 90,
                "the room's panels span " + (brightest - darkest) + " levels, enough contrast to "
                        + "hand the tag detector candidate quads");
    }

    /** Fraction of pixels whose luminance moved enough for an eye to notice. */
    private static double changedFraction(SyntheticFrame before, SyntheticFrame after) {
        int changed = 0;
        int total = 0;
        for (int y = 0; y < before.height(); y++) {
            for (int x = 0; x < before.width(); x++) {
                total++;
                if (Math.abs(before.luminanceAt(x, y) - after.luminanceAt(x, y)) > 4) {
                    changed++;
                }
            }
        }
        return (double) changed / total;
    }
}
