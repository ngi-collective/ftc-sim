package org.ngicollective.testframework.season;

import org.ngicollective.testframework.camera.Pivot;
import org.ngicollective.testframework.camera.Pose3d;
import org.ngicollective.testframework.camera.ScoringVolume;
import org.ngicollective.testframework.camera.Solid;
import org.ngicollective.testframework.camera.Structure;
import org.ngicollective.testframework.camera.Vec3;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * The HIVE Structure: the A-frame in the middle of the field and the two tipping HIVEs it carries.
 *
 * <p>Manual &sect;9.6, in its own words: "A frame holds a red HIVE and a blue HIVE. Each HIVE
 * consists of 2 CELLS, one on either end of each HIVE... Each HIVE is on a pivot and can tip so
 * that one of the CELLS is facing upwards at any given time." So a HIVE is one rigid body with two
 * baskets back to back, and that is how it is built here: both CELLs of one HIVE are placed from
 * one pivot and one tip state, which is what makes a HIVE with both CELLs up impossible to
 * describe rather than merely wrong.</p>
 *
 * <h2>Where the numbers come from</h2>
 *
 * <p>The manual gives the frame (49.46&nbsp;in wide, 38.95&nbsp;in deep, pivot axis
 * 43.95&nbsp;in above the tiles), the CELL opening (20 &times; 14&nbsp;in, 12&nbsp;in deep) and
 * the spacings (HIVEs 25.5&nbsp;in apart, CELLs about 18.8&nbsp;in apart). Everything else is
 * measured from FIRST's field CAD and converted the way {@link BioBuzzField} converts the tag
 * plates: {@code ourX = cadX}, {@code ourY = -cadZ}, {@code ourZ = cadY}.</p>
 *
 * <p>Four independent cross-checks came out of that, and each one would have caught a different
 * mistake:</p>
 *
 * <ul>
 *   <li>All four CELLs are <b>identical</b> in their own pivot-relative frame, to 0.01&nbsp;in.
 *       They are one part placed four ways, so anything else would mean the frame conversion was
 *       wrong for some of them.</li>
 *   <li>{@link #FLOOR_FROM_PIVOT_METRES} doubled is 18.84&nbsp;in, which is the manual's
 *       "approximately 18.8&nbsp;in apart" &mdash; the two CELLs of a HIVE meet back to back at
 *       the pivot, so their separation is not a free number.</li>
 *   <li>The raised CELL's opening works out at 53.4&nbsp;in to 65.5&nbsp;in above the tiles
 *       against the manual's 53.5 and 65.6 (Figure 9-10).</li>
 *   <li>Rotating one measured tag plate by {@link #TIP_DEGREES} twice lands on the other measured
 *       plate to 0.02&nbsp;in. The CAD captured the red and blue HIVEs in opposite states, so the
 *       tip angle is measurable rather than assumed.</li>
 * </ul>
 *
 * <h2>Nothing here is in a robot's way</h2>
 *
 * <p>The manual puts the bottom of a HIVE 25.5&nbsp;in above the tiles, and a legal robot is 18,
 * so a robot cannot touch a CELL however it drives. What a robot <em>can</em> hit is the frame:
 * its four legs, and the two foot bars lying on the tiles. Those are the reason this is collision
 * geometry at all, and they are why the frame is a separate {@link Structure} from the HIVEs it
 * holds &mdash; it never moves, and a solver that had to rebuild it every time a HIVE tipped would
 * be rebuilding the only part of this that a robot ever runs into.</p>
 *
 * <p>That 25.5&nbsp;in is the tip of a rib that holds nothing; the lowest modelled panel is at
 * 31.75. The difference is deliberate &mdash; a CELL is modelled as the opening the manual
 * dimensions, not as the sheet-metal wings around it &mdash; and it is safe precisely because
 * both numbers are far above anything that can drive.</p>
 */
public final class BioBuzzHive {

    private static final double INCH = 0.0254;

    /** Manual: the two pivots are 25.5 in apart, so each HIVE is half that off the centre line. */
    private static final double HIVE_OFFSET_INCHES = 12.75;
    public static final double HIVE_OFFSET_METRES = HIVE_OFFSET_INCHES * INCH;

    /** Manual: the pivot axis is 43.95 in above the tiles, running along field X. */
    public static final double PIVOT_HEIGHT_METRES = 43.95 * INCH;

    /** Manual: the CELL opening is about 20 in wide, 14 in tall and 12 in deep. */
    public static final double CELL_WIDTH_METRES = 20.0 * INCH;
    public static final double CELL_RISE_METRES = 14.0 * INCH;
    public static final double CELL_DEPTH_METRES = 12.0 * INCH;

    /**
     * How far the closed end of a CELL sits from the pivot, from the CAD.
     *
     * <p>Doubled, this is the manual's 18.8 in between a HIVE's two CELLs: they are mounted back
     * to back, so this single number fixes both of them and the gap between them.</p>
     */
    public static final double FLOOR_FROM_PIVOT_METRES = 9.42 * INCH;

    /**
     * And how far it sits along the CELL's own up, from the CAD.
     *
     * <p>A CELL is not centred on the pivot's height: it hangs off to one side of the axis, which
     * is what gives a tipped HIVE somewhere to fall to and makes it bi-stable rather than
     * balanced.</p>
     */
    private static final double RISE_FROM_PIVOT_METRES = 5.53 * INCH;

    /**
     * How far from level a CELL sits in either state, from the CAD: 60 degrees between the two.
     *
     * <p>Measured, not chosen. Every CELL's mouth in the CAD is 30 degrees off horizontal to
     * within a thousandth, and rotating the red HIVE's tag plates by 60 degrees lands them on the
     * blue HIVE's measured plates.</p>
     */
    public static final double TIP_DEGREES = 30.0;

    /**
     * The pivot's axis: field {@code +X}, so a HIVE tips toward and away from the audience.
     *
     * <p>A rotation of {@code +2 * TIP_DEGREES} about it takes the audience-side CELL from raised
     * to lowered, which is why {@link BioBuzzField.HiveTip#AUDIENCE_UP} is this pivot's zero: the
     * CAD captured the red HIVE in that state, so every measured number in this class is the
     * geometry at angle zero and both stable states are one rotation apart.</p>
     */
    public static final Vec3 PIVOT_AXIS = new Vec3(1.0, 0.0, 0.0);

    /**
     * What it takes to tip a HIVE: the restoring torque holding it against a stop.
     *
     * <p>Manual &sect;9.6: "Each HIVE is bi-stable and will hold its position until enough POLLEN
     * or NECTAR are LAUNCHED into the upwards-facing CELL." So this number is the whole game
     * mechanic, and the manual never prints it. What the manual does print are two bounds that
     * bracket it, and this sits between them:</p>
     *
     * <ul>
     *   <li><b>Above 0.34.</b> A MATCH stages three NECTAR in each upward-facing CELL
     *       (&sect;10.3.1) and the field then stands there until a ROBOT does something. A ball
     *       settles about 0.24&nbsp;m horizontally from the axis, so three NECTAR weigh
     *       0.34&nbsp;N&nbsp;m on it, and a HIVE that tipped under its own staging would be a
     *       simulator nobody could set a match up in.</li>
     *   <li><b>Below 1.1.</b> A TIP is worth {@link BioBuzzScore#POINTS_PER_TIP} against
     *       {@link BioBuzzScore#POINTS_PER_ELEMENT} apiece for what is left in a CELL, so ten
     *       elements is the break-even: a HIVE that took more than that would make tipping
     *       strictly worse than filling, and the manual's own overview has ROBOTS tipping inside
     *       a 30-second AUTO.</li>
     * </ul>
     *
     * <p>0.6 leaves the staged NECTAR holding with three fifths to spare, and tips on five more
     * POLLEN dropped in after them &mdash; eight elements in the basket, still fewer than the ten
     * the TIP is worth. Anyone with a real field can replace the figure in a minute &mdash; put
     * POLLEN into a raised CELL one at a time and count &mdash; and {@code HiveTipTest} states
     * what the current one implies rather than enshrining it.</p>
     *
     * <p>It is a threshold on <em>arriving</em>, not only on weight, and that is the mechanism the
     * manual describes rather than an artefact: three NECTAR placed in a CELL stay there, and the
     * same three dropped from 10&nbsp;cm above its mouth tip it. Once the HIVE leaves its stop the
     * hold falls away towards the middle while the balls roll outwards as the CELL flattens, so
     * anything that gets it moving at all takes it the whole way. LAUNCHING is what tips a HIVE
     * (G417), and this is why.</p>
     */
    public static final double PIVOT_HOLD_NEWTON_METRES = 0.6;

    /**
     * Its moment of inertia about that axis, which is what sets how long a tip takes.
     *
     * <p>Not the real HIVE's. A real one carries its weight on the pivot &mdash; it has to, or the
     * 5.53&nbsp;in its CELLs hang off the axis would make a gravity well several joules deep that
     * no number of 22&nbsp;g balls could ever lift it out of. What is modelled here is the
     * <em>effective</em> pivot: gravity carried by the axis, one hold torque, and this, tuned so
     * that a tip takes the better part of a second. That matters because the manual warns that
     * "LAUNCHING at the downward-facing CELL while a HIVE is tipping may disrupt its movement",
     * which is only a thing that can happen if a tip lasts long enough to shoot into.</p>
     */
    public static final double PIVOT_INERTIA_KILOGRAM_METRES_SQUARED = 0.08;

    /**
     * And its damper, the part the manual names: manual &sect;10.5.1 dates a completed TIP from
     * "the damper on the HIVE that was previously not contacting the frame begins to contact it".
     *
     * <p>Modelled as viscous, which is what a damper is, and sized so the swing runs at a steady
     * speed rather than accelerating all the way round and slamming into the far stop. Without it
     * a tipped HIVE bounces off its stop and can rattle back over centre, which would score two
     * TIPS for one shot.</p>
     */
    public static final double PIVOT_DAMPING_NEWTON_METRE_SECONDS = 0.12;

    /**
     * The mass that inertia implies, given where a CELL sits: see
     * {@link #PIVOT_INERTIA_KILOGRAM_METRES_SQUARED} for why it is lighter than the real thing.
     *
     * <p>Derived rather than stated, so the two cannot disagree. It is read only when a ball
     * bounces off a panel, where what is exchanged is momentum about the axis and this barely
     * enters; the solver needs a mass to accept a body at all, and an invented second number here
     * would be one more thing to keep consistent with the first.</p>
     */
    public static final double PIVOT_MASS_KILOGRAMS =
            PIVOT_INERTIA_KILOGRAM_METRES_SQUARED / cellRadiusSquared();

    /**
     * How thick a CELL's panels are drawn and collided with.
     *
     * <p>The real skins are sheet, about 0.03&nbsp;in. A quarter inch is the thinnest thing this
     * simulator draws &mdash; the same figure the FLOWER's rim segments use &mdash; because a
     * 0.03&nbsp;in box is a line of aliasing in the browser and invisible in the camera, and
     * nothing about a ball's behaviour changes over two tenths of an inch. Panels grow
     * <em>outward</em> from the measured interior, so the 20 &times; 14 &times; 12&nbsp;in opening
     * stays exactly the manual's.</p>
     */
    private static final double PANEL_METRES = 0.25 * INCH;

    /** CAD: the frame's legs are 1 in square extrusion, from a foot on the tiles to the apex. */
    private static final double LEG_THICKNESS_METRES = 1.0 * INCH;

    /**
     * CAD: where a leg's feet and apex are, in inches, for the quadrant at +X and +Y.
     *
     * <p>Two legs share each apex and the two apexes are joined by the crossbar, which is the
     * manual's "two triangular metal structures... connected at their apex by a crossbar". The
     * apex is put at {@link #HIVE_OFFSET_METRES} rather than at the 12.735&nbsp;in the CAD
     * measures, because the pivot it carries is the same 12.75&nbsp;in from the centre line and
     * two numbers a hundredth of an inch apart for one place is how a drawing and a solver come to
     * disagree.</p>
     */
    private static final double FOOT_X = 23.80;
    private static final double FOOT_Y = 18.56;
    private static final double FOOT_Z = 0.53;
    private static final double APEX_Y = 0.51;
    private static final double APEX_Z = 41.09;

    /** CAD: the crossbar over the middle, 1 in square and 24 in long along field X. */
    private static final double CROSSBAR_LENGTH_METRES = 24.0 * INCH;
    private static final double CROSSBAR_HEIGHT_METRES = 41.45 * INCH;

    /**
     * CAD: the logo panels, one on each side, 30.13 by 11.12 in and leaning with the frame.
     *
     * <p>The manual notes the BIOBUZZ graphic "may not be present at all events", so the panel is
     * modelled and the graphic is not. What matters here is that it is a solid surface across the
     * middle of the structure: without it a ball flies clean through the A of the A-frame.</p>
     */
    private static final double PANEL_WIDTH_METRES = 30.13 * INCH;
    private static final double PANEL_RISE_METRES = 11.12 * INCH;
    private static final double PANEL_SHEET_METRES = 0.12 * INCH;
    private static final double PANEL_OFFSET_METRES = 1.97 * INCH;
    private static final double PANEL_HEIGHT_METRES = 39.18 * INCH;
    private static final double PANEL_LEAN_DEGREES = 66.0;

    /**
     * CAD: the two sheet-metal bars the frame stands on, lying on the tiles along field Y.
     *
     * <p>2.15&nbsp;in of steel across a robot's path. In a planar world this stops a robot dead
     * where a real one might ride up on it, and that is the honest way round: a driver who learns
     * to avoid the frame's feet in simulation has learned something true about the field.</p>
     */
    private static final double FOOT_BAR_X_METRES = 23.75 * INCH;
    private static final double FOOT_BAR_WIDTH_METRES = 2.00 * INCH;
    private static final double FOOT_BAR_LENGTH_METRES = 38.95 * INCH;
    private static final double FOOT_BAR_HEIGHT_METRES = 2.15 * INCH;

    /** CAD: the plates between a HIVE's two CELLs that carry it on the pivot. */
    private static final double CONNECTOR_WIDTH_METRES = 1.15 * INCH;
    private static final double CONNECTOR_RISE_METRES = 3.59 * INCH;

    /** CAD: the frame and the CELL skins are off-white, and the tiles they stand on are grey. */
    private static final int WHITE_RED = 230;
    private static final int WHITE_GREEN = 230;
    private static final int WHITE_BLUE = 230;

    /**
     * CAD: the red and blue this season's alliance-coloured parts are painted.
     *
     * <p>A CELL's end ribs really are alliance-coloured, so they are coloured here, which is the
     * opposite call from the FLOWER's deliberate grey. The difference is that this is measured: a
     * {@code ColorBlobLocatorProcessor} hunting for a red NECTAR will find a large red shape three
     * feet up in the middle of the field, and that is not a simulation artefact to be designed
     * away &mdash; it is what the camera will see at a competition.</p>
     */
    private static final int RED_RIB_RED = 221;
    private static final int RED_RIB_GREEN = 82;
    private static final int RED_RIB_BLUE = 40;
    private static final int BLUE_RIB_RED = 22;
    private static final int BLUE_RIB_GREEN = 81;
    private static final int BLUE_RIB_BLUE = 176;

    public static final String FRAME = "HIVE FRAME";
    public static final String RED = "HIVE RED";
    public static final String BLUE = "HIVE BLUE";

    private BioBuzzHive() {
    }

    /** The frame and both HIVEs, each tipped as asked. */
    public static List<Structure> all(BioBuzzField.HiveTip red, BioBuzzField.HiveTip blue) {
        return Collections.unmodifiableList(Arrays.asList(
                frame(),
                hive(RED, -HIVE_OFFSET_METRES, RED_RIB_RED, RED_RIB_GREEN, RED_RIB_BLUE)
                        .at(tipRadians(red)),
                hive(BLUE, HIVE_OFFSET_METRES, BLUE_RIB_RED, BLUE_RIB_GREEN, BLUE_RIB_BLUE)
                        .at(tipRadians(blue))));
    }

    /**
     * A tip state as an angle about the pivot: zero audience-up, 60&deg; audience-down.
     *
     * <p>The two stable states are the two ends of one axis's travel, so a HIVE mid-swing has an
     * angle and no state, which is the honest description of a field a robot has just shot into.
     * {@link BioBuzzField.HiveTip} survives as what a scenario file says and where a HIVE starts,
     * not as what it is during a match.</p>
     */
    public static double tipRadians(BioBuzzField.HiveTip tip) {
        return tip == BioBuzzField.HiveTip.AUDIENCE_UP ? 0.0 : Math.toRadians(2.0 * TIP_DEGREES);
    }

    /** Which structure carries a CELL: what its tags and its scoring volume are attached to. */
    public static String hiveOf(String cellName) {
        return isRedCell(cellName) ? RED : BLUE;
    }

    /** Where one alliance's pivot axis runs, in the field frame. */
    public static Vec3 pivotPoint(boolean redAlliance) {
        return new Vec3(redAlliance ? -HIVE_OFFSET_METRES : HIVE_OFFSET_METRES, 0.0,
                PIVOT_HEIGHT_METRES);
    }

    /**
     * The A-frame, which never moves.
     *
     * <p>Drawn and collided with as the same boxes. The FLOWER needs the two to differ because a
     * drawn ring has to be an open cylinder and a collided one a circle of little boxes; nothing
     * here is a shell, so nothing here needs the distinction.</p>
     */
    public static Structure frame() {
        List<Solid> solids = new ArrayList<>();

        // Four legs, two triangles. The signs walk the quadrants: each triangle stands at one end
        // of the field's X axis with its two feet either side of the audience line.
        for (int xSide = -1; xSide <= 1; xSide += 2) {
            for (int ySide = -1; ySide <= 1; ySide += 2) {
                solids.add(Solid.beam(
                        inches(xSide * FOOT_X, ySide * FOOT_Y, FOOT_Z),
                        inches(xSide * HIVE_OFFSET_INCHES, ySide * APEX_Y, APEX_Z),
                        LEG_THICKNESS_METRES, WHITE_RED, WHITE_GREEN, WHITE_BLUE));
            }
        }

        solids.add(Solid.box(
                Pose3d.facingForward(new Vec3(0.0, 0.0, CROSSBAR_HEIGHT_METRES)),
                CROSSBAR_LENGTH_METRES, LEG_THICKNESS_METRES, LEG_THICKNESS_METRES,
                WHITE_RED, WHITE_GREEN, WHITE_BLUE));

        // The two logo panels. Roll is what leans a panel with the frame: it turns the panel about
        // its own long axis, which runs along field X, so the 11.12 in rises outward and upward
        // and the sheet's own normal comes out of the face. Yawing by 180 degrees instead of
        // negating the roll is what puts the second panel on the other side leaning the other way.
        for (int ySide = -1; ySide <= 1; ySide += 2) {
            solids.add(Solid.box(
                    Pose3d.ofDegrees(
                            new Vec3(0.0, ySide * PANEL_OFFSET_METRES, PANEL_HEIGHT_METRES),
                            ySide < 0 ? 0.0 : 180.0, 0.0, PANEL_LEAN_DEGREES),
                    PANEL_WIDTH_METRES, PANEL_RISE_METRES, PANEL_SHEET_METRES,
                    WHITE_RED, WHITE_GREEN, WHITE_BLUE));
        }

        for (int xSide = -1; xSide <= 1; xSide += 2) {
            solids.add(Solid.box(
                    Pose3d.facingForward(new Vec3(xSide * FOOT_BAR_X_METRES, 0.0,
                            FOOT_BAR_HEIGHT_METRES / 2.0)),
                    FOOT_BAR_WIDTH_METRES, FOOT_BAR_LENGTH_METRES, FOOT_BAR_HEIGHT_METRES,
                    WHITE_RED, WHITE_GREEN, WHITE_BLUE));
        }

        return new Structure(FRAME, solids, solids);
    }

    /**
     * The interior of the CELL a given tag cluster is stuck to: where it is and how it is turned.
     *
     * <p>The pose's own axes are the CELL's: {@link Pose3d#forward()} points out of the mouth,
     * {@link Pose3d#up()} runs up the 14&nbsp;in rise of the opening and {@link Pose3d#left()}
     * along its 20&nbsp;in width. Public because it is what lets a test check that the AprilTag
     * cluster {@link BioBuzzField} places really does land on this face, and because
     * {@link #cellVolume} is built on it.</p>
     *
     * @param cellName one of {@link BioBuzzField#RED_AUDIENCE} and its three siblings
     */
    public static Pose3d cell(String cellName, BioBuzzField.HiveTip red,
                              BioBuzzField.HiveTip blue) {
        boolean isRed = isRedCell(cellName);
        return cellInterior(isRed, isAudienceCell(cellName))
                .rotatedAbout(pivotPoint(isRed), PIVOT_AXIS, tipRadians(isRed ? red : blue));
    }

    /**
     * The same CELL as the space a ball can be in: the manual's 20 &times; 14 &times; 12 inches.
     *
     * <p>This is what answers "is that ball in that CELL", and it is the measured opening rather
     * than anything to do with the panels around it. The panels grow outward from these
     * dimensions (see {@link #PANEL_METRES}), so the volume and the collision geometry cannot
     * drift apart: thickening a skin does not shrink the basket.</p>
     *
     * <p>Attached to the HIVE it is cut in, so a tip swings the region with the basket instead of
     * leaving it behind in mid-air.</p>
     */
    public static ScoringVolume cellVolume(String cellName, BioBuzzField.HiveTip red,
                                           BioBuzzField.HiveTip blue) {
        return ScoringVolume.box(cellName, cell(cellName, red, blue),
                        CELL_DEPTH_METRES, CELL_WIDTH_METRES, CELL_RISE_METRES)
                .on(hiveOf(cellName));
    }

    /**
     * All four CELLs as regions, red's first.
     *
     * <p>All four, including the two facing the floor. A downward-facing CELL scores nothing
     * (manual &sect;10.5.1) but it is still somewhere a ball can be &mdash; mid-tip it is where
     * the balls that are about to spill out are &mdash; and which way a CELL faces is a fact about
     * its pose that {@link BioBuzzScore} reads off it. Publishing only the two that score was how
     * this worked while a tip was a two-valued setting; with a HIVE that swings, the list would
     * have had to change membership halfway through a rotation.</p>
     */
    public static List<ScoringVolume> cellVolumes(BioBuzzField.HiveTip red,
                                                  BioBuzzField.HiveTip blue) {
        return Collections.unmodifiableList(Arrays.asList(
                cellVolume(BioBuzzField.RED_SCORING, red, blue),
                cellVolume(BioBuzzField.RED_AUDIENCE, red, blue),
                cellVolume(BioBuzzField.BLUE_AUDIENCE, red, blue),
                cellVolume(BioBuzzField.BLUE_SCORING, red, blue)));
    }

    /**
     * Whether a CELL belongs to the red alliance, which is also how a name is validated.
     *
     * <p>One place knows which of the four names are red, rather than every caller matching on the
     * spelling of a constant. A score has to attribute points to an alliance and this is the
     * mapping that does it.</p>
     *
     * @throws IllegalArgumentException if there is no CELL by that name, because the alternative
     *     is answering "not red" for a typo and quietly scoring for blue
     */
    public static boolean isRedCell(String cellName) {
        if (BioBuzzField.RED_AUDIENCE.equals(cellName)
                || BioBuzzField.RED_SCORING.equals(cellName)) {
            return true;
        }
        if (BioBuzzField.BLUE_AUDIENCE.equals(cellName)
                || BioBuzzField.BLUE_SCORING.equals(cellName)) {
            return false;
        }
        throw new IllegalArgumentException("no CELL named \"" + cellName + "\"; there are "
                + Arrays.asList(BioBuzzField.RED_SCORING, BioBuzzField.RED_AUDIENCE,
                        BioBuzzField.BLUE_AUDIENCE, BioBuzzField.BLUE_SCORING));
    }

    /** Whether a CELL is the one on the audience side of its HIVE. */
    private static boolean isAudienceCell(String cellName) {
        return BioBuzzField.RED_AUDIENCE.equals(cellName)
                || BioBuzzField.BLUE_AUDIENCE.equals(cellName);
    }

    /** One alliance's HIVE: two CELLs and the assembly joining them, on one pivot. */
    private static Structure hive(String name, double hiveX, int ribRed, int ribGreen,
                                  int ribBlue) {
        boolean redAlliance = hiveX < 0.0;
        Pose3d audience = cellInterior(redAlliance, true);
        Pose3d scoring = cellInterior(redAlliance, false);

        List<Solid> drawn = new ArrayList<>();
        List<Solid> collided = new ArrayList<>();
        shell(audience, drawn, collided, ribRed, ribGreen, ribBlue);
        shell(scoring, drawn, collided, ribRed, ribGreen, ribBlue);

        // The connecting assembly, spanning the two closed ends through the pivot. Placed on the
        // audience CELL's frame because the two share an axis: whatever tips one tips the other,
        // and reusing the pose is how that stays true if the tip angle is ever re-measured.
        Solid spine = Solid.box(
                Pose3d.of(new Vec3(hiveX, 0.0, PIVOT_HEIGHT_METRES),
                        audience.yaw(), audience.pitch(), audience.roll()),
                2.0 * FLOOR_FROM_PIVOT_METRES, CONNECTOR_WIDTH_METRES, CONNECTOR_RISE_METRES,
                WHITE_RED, WHITE_GREEN, WHITE_BLUE);
        drawn.add(spine);
        collided.add(spine);

        return new Structure(name, drawn, collided, Pivot.of(
                pivotPoint(redAlliance),
                PIVOT_AXIS,
                0.0,
                0.0,
                Math.toRadians(2.0 * TIP_DEGREES),
                PIVOT_HOLD_NEWTON_METRES,
                PIVOT_MASS_KILOGRAMS,
                PIVOT_INERTIA_KILOGRAM_METRES_SQUARED,
                PIVOT_DAMPING_NEWTON_METRE_SECONDS));
    }

    /**
     * Where one CELL's interior is in the audience-up state, and which way it faces.
     *
     * <p>Position comes from walking out along the CELL's own axes from the pivot, which is what
     * keeps the two CELLs of a HIVE rigid: both read the same two offsets, so a re-measured tip
     * angle moves them together. Every other state is this one rotated &mdash; see
     * {@link Structure#at} &mdash; so there is one construction here rather than one per stable
     * position, and a HIVE with both CELLs up stays impossible to describe.</p>
     */
    private static Pose3d cellInterior(boolean redAlliance, boolean audienceSide) {
        // The audience is at -Y, so a CELL on that side of its HIVE opens toward -Y: yaw -90. In
        // this state that one is the raised one, tilting its mouth up by the tip angle while the
        // other tilts down by the same, which is the 60 degrees between the two stable positions.
        double yaw = audienceSide ? -90.0 : 90.0;
        double pitch = audienceSide ? TIP_DEGREES : -TIP_DEGREES;

        Pose3d axes = Pose3d.ofDegrees(pivotPoint(redAlliance), yaw, pitch, 0.0);
        Vec3 centre = axes.position()
                .plus(axes.forward().scaled(FLOOR_FROM_PIVOT_METRES + CELL_DEPTH_METRES / 2.0))
                .plus(axes.up().scaled(RISE_FROM_PIVOT_METRES));
        return Pose3d.of(centre, axes.yaw(), axes.pitch(), axes.roll());
    }

    /**
     * How far a CELL's centre is from the pivot axis, squared: the radius
     * {@link #PIVOT_MASS_KILOGRAMS} divides the inertia by.
     *
     * <p>Measured off the geometry above rather than written down again, so it follows a
     * re-measured {@link #FLOOR_FROM_PIVOT_METRES} without anyone remembering to.</p>
     */
    private static double cellRadiusSquared() {
        Vec3 centre = cellInterior(true, true).position().minus(pivotPoint(true));
        return centre.y() * centre.y() + centre.z() * centre.z();
    }

    /**
     * A CELL as the panels around its opening: a floor, two ribs and two ends.
     *
     * <p>Open at the mouth, closed everywhere else, which is the whole point of a basket. Each
     * panel is placed against the measured interior and grows away from it, so the clear opening
     * is the manual's and the panels are outside it.</p>
     *
     * <h2>The face the AprilTags are on is collided with but not drawn</h2>
     *
     * <p>Manual &sect;9.6: "On the bottom face of each CELL is a unique AprilTag Cluster". That
     * face is this shell's {@code -up} end, and the cluster's sticker is measured two hundredths
     * of an inch clear of it &mdash; coplanar, for anything this renderer can tell. The camera
     * draws back to front by each polygon's own depth, with no depth buffer, so a panel there and
     * the tags on it would take turns painting over each other: the tags would vanish from some
     * viewpoints and not others, and vision would fail in a way that looked like a detector bug.
     * Leaving that one panel out of {@link Structure#drawn()} costs a hole in the underside of a
     * basket that hangs three feet up, and it is why a structure has two lists.</p>
     */
    private static void shell(Pose3d cell, List<Solid> drawn, List<Solid> collided,
                              int ribRed, int ribGreen, int ribBlue) {
        double halfDepth = CELL_DEPTH_METRES / 2.0;
        double halfWidth = CELL_WIDTH_METRES / 2.0;
        double halfRise = CELL_RISE_METRES / 2.0;
        double outside = halfDepth + PANEL_METRES / 2.0;

        Solid floor = panel(cell, cell.forward().scaled(-outside),
                PANEL_METRES, CELL_WIDTH_METRES, CELL_RISE_METRES,
                WHITE_RED, WHITE_GREEN, WHITE_BLUE);
        drawn.add(floor);
        collided.add(floor);

        for (int side = -1; side <= 1; side += 2) {
            Solid rib = panel(cell,
                    cell.left().scaled(side * (halfWidth + PANEL_METRES / 2.0)),
                    CELL_DEPTH_METRES, PANEL_METRES, CELL_RISE_METRES,
                    ribRed, ribGreen, ribBlue);
            drawn.add(rib);
            collided.add(rib);
        }

        Solid high = panel(cell, cell.up().scaled(halfRise + PANEL_METRES / 2.0),
                CELL_DEPTH_METRES, CELL_WIDTH_METRES, PANEL_METRES,
                WHITE_RED, WHITE_GREEN, WHITE_BLUE);
        drawn.add(high);
        collided.add(high);

        Solid tagged = panel(cell, cell.up().scaled(-(halfRise + PANEL_METRES / 2.0)),
                CELL_DEPTH_METRES, CELL_WIDTH_METRES, PANEL_METRES,
                WHITE_RED, WHITE_GREEN, WHITE_BLUE);
        collided.add(tagged);
    }

    /** One panel of a shell, offset from the CELL's centre but turned with it. */
    private static Solid panel(Pose3d cell, Vec3 offset, double alongForward, double alongLeft,
                               double alongUp, int red, int green, int blue) {
        return Solid.box(
                Pose3d.of(cell.position().plus(offset), cell.yaw(), cell.pitch(), cell.roll()),
                alongForward, alongLeft, alongUp, red, green, blue);
    }

    private static Vec3 inches(double x, double y, double z) {
        return new Vec3(x * INCH, y * INCH, z * INCH);
    }
}
