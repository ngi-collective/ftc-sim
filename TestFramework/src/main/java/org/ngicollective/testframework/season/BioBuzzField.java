package org.ngicollective.testframework.season;

import org.ngicollective.testframework.camera.GameElement;
import org.ngicollective.testframework.camera.Pose3d;
import org.ngicollective.testframework.camera.SimulatedScene;
import org.ngicollective.testframework.camera.TagCluster;
import org.ngicollective.testframework.camera.Vec3;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * The BioBuzz competition field's AprilTag geometry.
 *
 * <p>A competition field is fixed by the game manual, so this is a real answer rather than a
 * placeholder &mdash; the same reasoning that makes {@code FieldConfig.standard()} a method and
 * not a file. Practice setups that differ belong in a scenario file; the official field belongs
 * here, where it needs no checkout to be correct.</p>
 *
 * <h2>Where these numbers come from</h2>
 *
 * <p>Tag ids, sizes and in-row offsets are the SDK's own, from
 * {@code AprilTagGameDatabase.getBioBuzzCluster}. Cluster placement is measured from FIRST's field
 * CAD, whose frame has its origin at field centre with +Y up, +Z toward the audience and red at
 * -X; it is converted here to this project's field frame ({@code ourX = cadX},
 * {@code ourY = -cadZ}, {@code ourZ = cadY}, audience at -Y). Three independent cross-checks
 * agreed: the HIVEs sit at x = &plusmn;12.75 in, matching the manual's 25.5 in centre-to-centre;
 * every plate is 14.10 in from the stated pivot axis; and red and blue mirror to within
 * 0.016 in.</p>
 *
 * <h2>What a HIVE tipping does</h2>
 *
 * <p>Each HIVE carries two CELLs, one high and one low, and each CELL has four tags on its
 * underside. The CAD captured the two HIVEs in <em>opposite</em> tip states, which is what makes
 * both poses recoverable from one measurement: in either state the two plates of a HIVE share one
 * normal, and tipping flips that normal's audience-facing component along with swapping which CELL
 * is high. Modelling the tip as a two-valued state per HIVE, rather than as four free cluster
 * poses, is what stops a test from describing a field that cannot physically exist.</p>
 *
 * <h2>The cluster origin is not the plate</h2>
 *
 * <p>The SDK's member offsets carry a {@code +7.1874 in} rise and a {@code -5.622 in} depth on top
 * of each tag's place in the row, which puts its cluster origin about nine inches behind and below
 * the plate the tags are printed on. Those offsets are kept exactly as the SDK states them, and
 * the plate position measured from the CAD is used to derive where that origin has to sit.</p>
 *
 * <p>This matters because the SDK solves a whole cluster against its own offsets and reports the
 * pose of <em>that</em> origin. Placing tags as though the measured centre were the origin renders
 * the tags nine inches out of place while still detecting perfectly, so the error would surface
 * only as a range that is quietly wrong &mdash; which is why {@link TagCluster#pose()} here means
 * the SDK's cluster frame, and the plate is an input rather than the stored value.</p>
 */
public final class BioBuzzField {

    private static final double INCH = 0.0254;

    /** Edge of the black square, from the SDK. */
    private static final double TAG_SIZE = 3.25 * INCH;

    /** In-row offsets, from the SDK: unevenly spaced, straddling the CELL's centre post. */
    private static final double[] ROW_OFFSETS =
            {-6.50 * INCH, -2.75 * INCH, 2.75 * INCH, 6.50 * INCH};

    /**
     * How far each tag sits up and back from the SDK's cluster origin, from the SDK.
     *
     * <p>Every member shares these, so together they say where the plate is relative to the
     * origin the SDK solves for.</p>
     */
    private static final double MEMBER_RISE = 7.1874 * INCH;
    private static final double MEMBER_DEPTH = -5.622 * INCH;

    /** Half the distance between the two HIVEs, from the CAD; red is at -X. */
    private static final double RED_HIVE_X = -12.742 * INCH;
    private static final double BLUE_HIVE_X = 12.758 * INCH;

    /** Height and audience-ward offset of the raised CELL, from the CAD. */
    private static final double RAISED_HEIGHT = 49.666 * INCH;
    private static final double RAISED_OFFSET = 12.887 * INCH;

    /** The same for the lowered CELL. */
    private static final double LOWERED_HEIGHT = 35.647 * INCH;
    private static final double LOWERED_OFFSET = 11.394 * INCH;

    /** Every plate is 30 degrees off horizontal, so its normal is 60 degrees below the horizon. */
    private static final double PLATE_PITCH_DEGREES = -60.0;

    public static final String RED_SCORING = "RED SCORING";
    public static final String RED_AUDIENCE = "RED AUDIENCE";
    public static final String BLUE_AUDIENCE = "BLUE AUDIENCE";
    public static final String BLUE_SCORING = "BLUE SCORING";

    /** Which way a HIVE is tipped: whether its audience-side CELL is the raised one. */
    public enum HiveTip {
        AUDIENCE_UP,
        AUDIENCE_DOWN,
    }

    private BioBuzzField() {
    }

    /**
     * The field exactly as the CAD captured it: the red HIVE tipped audience-up, the blue one
     * audience-down.
     */
    public static SimulatedScene official() {
        return scene(HiveTip.AUDIENCE_UP, HiveTip.AUDIENCE_DOWN);
    }

    /** The field with each HIVE tipped as asked. */
    public static SimulatedScene scene(HiveTip red, HiveTip blue) {
        return new SimulatedScene(clusters(red, blue), Collections.<GameElement>emptyList());
    }

    /**
     * All four tag clusters.
     *
     * <p>Game elements are deliberately absent: where the POLLEN and NECTAR start is a match
     * setup rather than field geometry, so it belongs to a scenario.</p>
     */
    public static List<TagCluster> clusters(HiveTip red, HiveTip blue) {
        List<TagCluster> clusters = new ArrayList<>(4);
        // Red: ids 30-33 on the scoring-side CELL, 34-37 on the audience-side one.
        clusters.add(cell(RED_SCORING, 30, RED_HIVE_X, red, false));
        clusters.add(cell(RED_AUDIENCE, 34, RED_HIVE_X, red, true));
        clusters.add(cell(BLUE_AUDIENCE, 38, BLUE_HIVE_X, blue, true));
        clusters.add(cell(BLUE_SCORING, 42, BLUE_HIVE_X, blue, false));
        return clusters;
    }

    /**
     * One CELL's cluster of four tags.
     *
     * @param audienceSide whether this CELL hangs on the audience side of its HIVE, which fixes
     *     the sign of its offset from the HIVE's centre line
     */
    private static TagCluster cell(String name, int firstId, double hiveX, HiveTip tip,
                                   boolean audienceSide) {
        boolean raised = (tip == HiveTip.AUDIENCE_UP) == audienceSide;
        double height = raised ? RAISED_HEIGHT : LOWERED_HEIGHT;
        double offset = raised ? RAISED_OFFSET : LOWERED_OFFSET;

        // Audience is at -Y in this project's frame.
        double y = audienceSide ? -offset : offset;

        // Both plates of a HIVE share one normal, and tipping flips which way it leans. A plate
        // leaning toward the audience faces -Y, which is a yaw of -90 degrees.
        double yaw = tip == HiveTip.AUDIENCE_UP ? -90.0 : 90.0;

        // Where the tags physically are, from the CAD.
        Pose3d plate = Pose3d.ofDegrees(new Vec3(hiveX, y, height), yaw, PLATE_PITCH_DEGREES, 0.0);

        // The SDK's cluster origin sits MEMBER_RISE up the plate and MEMBER_DEPTH into it, so the
        // origin is that far back from the plate. Walking it backwards here is what lets the
        // member offsets stay exactly as the SDK states them while the tags still land on the
        // measured plate. The plate's own axes: +Y up it, +Z into it, which is -forward.
        Vec3 origin = plate.position()
                .minus(plate.up().scaled(MEMBER_RISE))
                .plus(plate.forward().scaled(MEMBER_DEPTH));

        Pose3d pose = Pose3d.ofDegrees(origin, yaw, PLATE_PITCH_DEGREES, 0.0);
        return new TagCluster(name, pose, members(firstId));
    }

    private static List<TagCluster.Member> members(int firstId) {
        return Arrays.asList(
                member(firstId, ROW_OFFSETS[0]),
                member(firstId + 1, ROW_OFFSETS[1]),
                member(firstId + 2, ROW_OFFSETS[2]),
                member(firstId + 3, ROW_OFFSETS[3]));
    }

    private static TagCluster.Member member(int id, double offsetAlongRow) {
        return new TagCluster.Member(id, offsetAlongRow, MEMBER_RISE, MEMBER_DEPTH, TAG_SIZE);
    }
}
