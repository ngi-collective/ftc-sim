package org.ngicollective.testframework.camera;

import org.ngicollective.testframework.sim.FieldConfig;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The room the field stands in: four far walls and a ceiling, in panels.
 *
 * <h2>Why the room exists at all</h2>
 *
 * <p>Nothing under test is beyond the perimeter, so this is scenery. It is here because a flat
 * backdrop made the camera view unreadable: this camera is aimed up at the overhead tags, so most
 * of every frame is whatever is past the field, and a single flat colour there does not change
 * when the robot drives. A driver turning on the spot saw a still picture and concluded the view
 * had frozen. Panels give the background parallax, which is the only thing that says "this is
 * live" when no tag happens to be in shot.</p>
 *
 * <p>It has to be geometry rather than a pattern drawn over the image: a screen-space texture
 * would sit still exactly when the robot moves, which is worse than flat grey because it looks
 * deliberate.</p>
 *
 * <h2>Why it is so drab</h2>
 *
 * <p>Every panel is a neutral grey &mdash; red, green and blue within a couple of levels of each
 * other &mdash; and every panel's luminance sits in a narrow band well inside the range where
 * nothing else in the pipeline is looking. That is not timidity, it is the constraint this scenery
 * has to meet:</p>
 *
 * <ul>
 *   <li>{@code ColorBlobLocatorProcessor} hunts for saturated colour. A gym with a real team
 *       banner on the wall would hand it a blob the size of a NECTAR, and the OpMode would have no
 *       way to know the blob was wallpaper.</li>
 *   <li>The AprilTag detector hunts for quadrilaterals of strong gradient, and a panel <em>is</em>
 *       a quadrilateral. Kept to a dozen or so levels of contrast, a panel edge is far below a
 *       tag's black-on-white and decodes to nothing; made bold, it would cost the detector real
 *       work chasing candidates that can never carry a codeword.</li>
 *   <li>A panel darker than luminance 60 would read as a tag's black square, and one brighter than
 *       about 200 as its white quiet zone, to any test or OpMode that thresholds.</li>
 * </ul>
 *
 * <p>So the background is legible motion and nothing else. Anything that wants to be seen belongs
 * on the field, where it can be measured.</p>
 */
public final class GymSurroundings {

    /**
     * Run-off between the field perimeter and the walls, in metres: enough that the room reads as
     * a room rather than a box, and that its panels move with parallax instead of sweeping past.
     */
    private static final double RUN_OFF_METRES = 2.5;

    /** Ceiling height in metres, off a school gym rather than a competition venue. */
    private static final double CEILING_METRES = 5.0;

    /** Where the lower band of wall panelling stops: about the top of a set of bleachers. */
    private static final double DADO_METRES = 2.2;

    /** Panels across one wall, and cells across the ceiling in each direction. */
    private static final int PANELS_PER_WALL = 8;
    private static final int CEILING_CELLS = 6;

    /**
     * The four greys, as neutral triples. Two for the wall above the dado, two for below it, and
     * two for the ceiling, alternating panel by panel.
     *
     * <p>The spread within a pair is what a person sees as texture; the gap between the wall pairs
     * and the ceiling pair is what keeps a corner of the room readable as a corner.</p>
     */
    private static final int WALL_LIGHT = 112;
    private static final int WALL_DARK = 98;
    private static final int DADO_LIGHT = 84;
    private static final int DADO_DARK = 72;
    private static final int CEILING_LIGHT = 132;
    private static final int CEILING_DARK = 120;

    private GymSurroundings() {
    }

    /**
     * Every surface of the room around one field.
     *
     * <p>Built once per scene, like the field's own surfaces, and depth-sorted with them: the room
     * is farther away than anything on the field, so it loses every comparison and paints
     * first.</p>
     */
    public static List<Surface> of(FieldConfig field) {
        double reach = field.halfExtentMetres() + RUN_OFF_METRES;

        List<Surface> surfaces = new ArrayList<>();
        for (int panel = 0; panel < PANELS_PER_WALL; panel++) {
            double from = -reach + 2.0 * reach * panel / PANELS_PER_WALL;
            double to = -reach + 2.0 * reach * (panel + 1) / PANELS_PER_WALL;
            boolean light = panel % 2 == 0;

            // Both bands of all four walls. The alternation is taken off the panel index rather
            // than the coordinate so that adjacent walls meet on a corner with two panels of the
            // same shade, which is what makes the corner itself visible.
            addWallPanel(surfaces, reach, from, to, DADO_METRES, CEILING_METRES, light, false);
            addWallPanel(surfaces, reach, from, to, 0.0, DADO_METRES, light, true);
        }
        addCeiling(surfaces, reach);
        return Collections.unmodifiableList(surfaces);
    }

    /** One panel on each of the four walls, between two heights. */
    private static void addWallPanel(List<Surface> surfaces, double reach, double from, double to,
            double bottom, double top, boolean light, boolean dado) {
        int shade = dado
                ? (light ? DADO_LIGHT : DADO_DARK)
                : (light ? WALL_LIGHT : WALL_DARK);

        // The walls at constant x span y, and those at constant y span x.
        surfaces.add(grey(shade,
                new Vec3(-reach, from, bottom), new Vec3(-reach, to, bottom),
                new Vec3(-reach, to, top), new Vec3(-reach, from, top)));
        surfaces.add(grey(shade,
                new Vec3(reach, from, bottom), new Vec3(reach, to, bottom),
                new Vec3(reach, to, top), new Vec3(reach, from, top)));
        surfaces.add(grey(shade,
                new Vec3(from, -reach, bottom), new Vec3(to, -reach, bottom),
                new Vec3(to, -reach, top), new Vec3(from, -reach, top)));
        surfaces.add(grey(shade,
                new Vec3(from, reach, bottom), new Vec3(to, reach, bottom),
                new Vec3(to, reach, top), new Vec3(from, reach, top)));
    }

    /**
     * The ceiling, in panels both ways.
     *
     * <p>A grid rather than stripes because this camera is pitched up: for a robot spinning on the
     * spot the ceiling is most of the frame, and stripes running one way would stand still for
     * exactly the half of that spin where they run along the turn.</p>
     */
    private static void addCeiling(List<Surface> surfaces, double reach) {
        for (int column = 0; column < CEILING_CELLS; column++) {
            double x0 = -reach + 2.0 * reach * column / CEILING_CELLS;
            double x1 = -reach + 2.0 * reach * (column + 1) / CEILING_CELLS;
            for (int row = 0; row < CEILING_CELLS; row++) {
                double y0 = -reach + 2.0 * reach * row / CEILING_CELLS;
                double y1 = -reach + 2.0 * reach * (row + 1) / CEILING_CELLS;
                int shade = (column + row) % 2 == 0 ? CEILING_LIGHT : CEILING_DARK;
                surfaces.add(grey(shade,
                        new Vec3(x0, y0, CEILING_METRES), new Vec3(x1, y0, CEILING_METRES),
                        new Vec3(x1, y1, CEILING_METRES), new Vec3(x0, y1, CEILING_METRES)));
            }
        }
    }

    /** One neutral-grey quad: red, green and blue all the same, for the reasons in the class doc. */
    private static Surface grey(int shade, Vec3 first, Vec3 second, Vec3 third, Vec3 fourth) {
        return new Surface(new Vec3[] {first, second, third, fourth}, shade, shade, shade);
    }
}
