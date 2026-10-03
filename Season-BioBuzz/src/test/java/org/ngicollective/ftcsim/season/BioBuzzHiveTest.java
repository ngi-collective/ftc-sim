package org.ngicollective.ftcsim.season;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.ngicollective.ftcsim.camera.FieldTag;
import org.ngicollective.ftcsim.camera.Pose3d;
import org.ngicollective.ftcsim.camera.Solid;
import org.ngicollective.ftcsim.camera.SolidSurfaces;
import org.ngicollective.ftcsim.camera.Structure;
import org.ngicollective.ftcsim.camera.Surface;
import org.ngicollective.ftcsim.camera.TagCluster;
import org.ngicollective.ftcsim.camera.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * The HIVE Structure, against the figures the manual publishes.
 *
 * <p>Every dimension here was derived from FIRST's CAD, and the manual's own numbers are the only
 * independent check on that derivation. A HIVE in the wrong place renders and collides perfectly
 * well &mdash; it is simply not the field any autonomous routine will meet, and no test of the
 * renderer or the solver could tell.</p>
 */
class BioBuzzHiveTest {

    private static final double INCH = 0.0254;

    private static final BioBuzzField.HiveTip UP = BioBuzzField.HiveTip.AUDIENCE_UP;
    private static final BioBuzzField.HiveTip DOWN = BioBuzzField.HiveTip.AUDIENCE_DOWN;

    @Test
    void theRaisedCellsOpeningIsWhereFigureNineTenPutsIt() {
        // Figure 9-10: the bottom of the HIVE opening is 53.5 in above the tiles and the top 65.6.
        // Those two heights are what a shooter is aimed at, and they come out of the tip angle,
        // the pivot height and the CELL's own offsets all being right at once -- there is no way
        // to fudge one of them and still land here.
        Pose3d cell = BioBuzzHive.cell(BioBuzzField.RED_AUDIENCE, UP, DOWN);
        double[] span = openingHeights(cell);

        assertEquals(53.5, span[0], 0.25, "bottom of the raised CELL's opening, in inches");
        assertEquals(65.6, span[1], 0.25, "top of the raised CELL's opening, in inches");
    }

    @Test
    void theTwoCellsOfAHiveAreBackToBackAroundThePivot() {
        // Manual 9.6.2: the two CELLs of a HIVE are approximately 18.8 in apart. They are one
        // rigid assembly, so this is a consequence of where each CELL's closed end sits rather
        // than a number to be set: get the pivot offset wrong and the pair drifts apart.
        Vec3 audience = closedEnd(BioBuzzHive.cell(BioBuzzField.RED_AUDIENCE, UP, DOWN));
        Vec3 scoring = closedEnd(BioBuzzHive.cell(BioBuzzField.RED_SCORING, UP, DOWN));

        assertEquals(18.8, audience.minus(scoring).length() / INCH, 0.1,
                "the CELLs' closed ends, in inches apart");
    }

    @Test
    void tippingAHiveSwapsWhichCellIsUpAndMovesBothAtOnce() {
        // A HIVE is bi-stable and rigid: exactly one CELL faces up, and tipping it raises one end
        // by however much it drops the other. Two independent CELLs would let a field exist with
        // both of a HIVE's CELLs up, which is the state this rules out.
        Pose3d audienceUp = BioBuzzHive.cell(BioBuzzField.RED_AUDIENCE, UP, DOWN);
        Pose3d scoringUp = BioBuzzHive.cell(BioBuzzField.RED_SCORING, UP, DOWN);
        Pose3d audienceDown = BioBuzzHive.cell(BioBuzzField.RED_AUDIENCE, DOWN, UP);
        Pose3d scoringDown = BioBuzzHive.cell(BioBuzzField.RED_SCORING, DOWN, UP);

        assertTrue(audienceUp.forward().z() > 0.0 && scoringUp.forward().z() < 0.0,
                "tipped audience-up, only the audience CELL should open upward");
        assertTrue(audienceDown.forward().z() < 0.0 && scoringDown.forward().z() > 0.0,
                "tipped audience-down, only the scoring CELL should open upward");

        double raised = audienceUp.position().z() - audienceDown.position().z();
        double dropped = scoringDown.position().z() - scoringUp.position().z();
        assertEquals(raised, dropped, 1e-9,
                "one CELL rising by more than the other falls means the HIVE is not rigid");
    }

    @Test
    void theTipIsSixtyDegreesBetweenTheTwoStates() {
        // The CAD caught the red and blue HIVEs in opposite states, which is what made this
        // measurable. A CELL 30 degrees off level either way is also what puts the AprilTag plates
        // 30 degrees from horizontal, as the manual says they are.
        Pose3d up = BioBuzzHive.cell(BioBuzzField.RED_AUDIENCE, UP, DOWN);
        Pose3d down = BioBuzzHive.cell(BioBuzzField.RED_AUDIENCE, DOWN, UP);

        double between = Math.toDegrees(up.pitch() - down.pitch());
        assertEquals(2.0 * BioBuzzHive.TIP_DEGREES, between, 1e-9);
        assertEquals(60.0, between, 1e-9, "the two stable positions are 60 degrees apart");
    }

    @Test
    void everyTagClusterLandsOnTheCellItBelongsTo() {
        // The cross-check that matters most, because two independent CAD measurements meet here:
        // BioBuzzField places the tag plates from their own measured coordinates, and this class
        // places the CELLs from the pivot. The manual says the cluster is on the bottom face of
        // its CELL, so every tag has to sit just outside that one face, within its width and
        // depth. Flip either model's tip logic, or mirror one alliance, and a cluster ends up
        // inside a basket or on the wrong CELL entirely -- and vision would still work, against
        // tags in the wrong place.
        for (TagCluster cluster : BioBuzzField.clusters(UP, DOWN)) {
            Pose3d cell = BioBuzzHive.cell(cluster.name(), UP, DOWN);
            double face = -BioBuzzHive.CELL_RISE_METRES / 2.0;

            for (FieldTag tag : cluster.tags()) {
                Vec3 offset = tag.pose().position().minus(cell.position());
                double alongUp = dot(offset, cell.up());
                double alongLeft = dot(offset, cell.left());
                double alongForward = dot(offset, cell.forward());

                assertTrue(alongUp < face && alongUp > face - 0.25 * INCH,
                        "tag " + tag.id() + " should sit just outside " + cluster.name()
                                + "'s bottom face, and is " + (alongUp - face) / INCH
                                + " in from it");
                assertTrue(Math.abs(alongLeft) < BioBuzzHive.CELL_WIDTH_METRES / 2.0,
                        "tag " + tag.id() + " hangs off the side of " + cluster.name());
                assertTrue(Math.abs(alongForward) < BioBuzzHive.CELL_DEPTH_METRES / 2.0,
                        "tag " + tag.id() + " is not within " + cluster.name() + "'s depth");
            }
        }
    }

    @Test
    void theFrameIsTheSizeTheManualGivesAndStandsOnTheTiles() {
        // Manual 9.6.1: 49.46 in wide, 38.95 in deep at its base, pivot axis 43.95 in up. The
        // legs lean in two axes at once, so width and depth are emergent from four pairs of
        // endpoints -- a mirrored leg would show up here as a frame that is too narrow on one
        // side.
        double[] extent = extentOf(BioBuzzHive.frame());

        assertEquals(49.46, extent[1] - extent[0], 1.0, "frame width, in inches");
        assertEquals(38.95, extent[3] - extent[2], 1.0, "frame depth, in inches");
        assertEquals(0.0, extent[4], 0.01, "the frame's feet rest on the tiles");
        assertTrue(extent[5] > 43.95 - 3.0 && extent[5] < 43.95 + 3.0,
                "the frame should reach the pivot axis at 43.95 in, and tops out at " + extent[5]);
    }

    @Test
    void nothingOnAHiveHangsLowEnoughForARobotToHit() {
        // Why the frame and the HIVEs are separate structures: only the frame is reachable. A
        // robot is at most 18 in tall, so if this ever fails, a HIVE has been placed low enough
        // that driving into one becomes part of the game and the frame's monopoly on contact is
        // gone.
        for (BioBuzzField.HiveTip tip : BioBuzzField.HiveTip.values()) {
            for (Structure hive : BioBuzzHive.all(tip, tip)) {
                if (hive.name().equals(BioBuzzHive.FRAME)) {
                    continue;
                }
                assertTrue(extentOf(hive)[4] > 18.0,
                        hive.name() + " reaches down to " + extentOf(hive)[4]
                                + " in, which a robot could touch");
            }
        }
    }

    @Test
    void theTaggedFaceIsCollidedWithButNotDrawn() {
        // Deliberate: a panel coplanar with the AprilTag sticker would take turns painting over it
        // in a renderer that sorts whole polygons by depth. The balls still have to be held in, so
        // the face exists to the solver and to nobody else.
        Structure hive = BioBuzzHive.all(UP, DOWN).get(1);
        Pose3d cell = BioBuzzHive.cell(BioBuzzField.RED_AUDIENCE, UP, DOWN);
        Vec3 tagged = cell.position()
                .plus(cell.up().scaled(-(BioBuzzHive.CELL_RISE_METRES / 2.0 + 0.003175)));

        assertTrue(nearest(hive.collided(), tagged) < 1e-9,
                "the tagged face should be a collider; nearest is "
                        + nearest(hive.collided(), tagged) + " m away");
        assertTrue(nearest(hive.drawn(), tagged) > 0.05,
                "nothing should be drawn on the face the cluster is stuck to");
    }

    @Test
    void aCellIsDrawnAsThePentagonItIsRatherThanAsABox() {
        // am-5866 Goal Rib, two per CELL: a flat base, two sides up to a shoulder, and two edges
        // meeting over the centreline. Drawn as the five bars of that outline, so this asserts the
        // outline closes -- every corner used by exactly two bars, at both ends of the CELL. A bar
        // placed from the wrong corner leaves two corners with one bar each and still looks
        // roughly like a pentagon from the one angle anyone checks.
        Pose3d cell = BioBuzzHive.cell(BioBuzzField.RED_AUDIENCE, UP, DOWN);
        double[][] corners = {
                {-10.5, -8.50}, {10.5, -8.50}, {10.5, 0.33}, {0.0, 7.53}, {-10.5, 0.33},
        };

        for (int end = -1; end <= 1; end += 2) {
            Vec3 at = cell.position().plus(cell.forward()
                    .scaled(end * (BioBuzzHive.CELL_DEPTH_METRES / 2.0 + 0.00635)));
            int[] bars = new int[corners.length];
            for (Solid solid : ribBarsOf(BioBuzzHive.all(UP, DOWN).get(1))) {
                for (Vec3 tip : endsOf(solid)) {
                    for (int corner = 0; corner < corners.length; corner++) {
                        Vec3 expected = at
                                .plus(cell.left().scaled(corners[corner][0] * INCH))
                                .plus(cell.up().scaled(corners[corner][1] * INCH));
                        if (tip.minus(expected).length() < 0.005) {
                            bars[corner]++;
                        }
                    }
                }
            }
            for (int corner = 0; corner < corners.length; corner++) {
                assertEquals(2, bars[corner], "corner " + corner + " of the rib at "
                        + (end < 0 ? "the closed end" : "the mouth")
                        + " should join two bars, and joins " + bars[corner]);
            }
        }
    }

    @Test
    void aCellsSkinsAreClearAndStayClearWhenTheHiveTips() {
        // The panels are polycarbonate on the real field, which is how anyone counts the NECTAR in
        // a CELL from the audience. The tipped case is the one that can regress on its own: every
        // solid of a tipped HIVE is a rotated copy, and a copy that dropped the alpha would leave
        // a HIVE opaque in one of its two stable states only.
        for (BioBuzzField.HiveTip tip : BioBuzzField.HiveTip.values()) {
            Structure hive = BioBuzzHive.all(tip, tip).get(1);
            int clear = 0;
            for (Solid solid : hive.drawn()) {
                if (solid.alpha() < Solid.OPAQUE) {
                    clear++;
                }
            }
            assertEquals(10, clear, "five skins per CELL, whichever way the HIVE is tipped");

            assertEquals(20, ribBarsOf(hive).size(), "ten bars per CELL: two pentagons");
            for (Solid bar : ribBarsOf(hive)) {
                assertEquals(Solid.OPAQUE, bar.alpha(),
                        "a rib is painted sheet, and is what the pentagon is read off");
            }
        }
    }

    @Test
    void askingForACellThatDoesNotExistSaysSo() {
        // The four CELL names are shared with BioBuzzField, and a typo would otherwise be a silent
        // default: scoring code asking for "RED_AUDIENCE" instead of "RED AUDIENCE" should be told.
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> BioBuzzHive.cell("RED_AUDIENCE", UP, DOWN));
        assertTrue(thrown.getMessage().contains(BioBuzzField.RED_AUDIENCE),
                "the complaint should list the names that do exist: " + thrown.getMessage());
    }

    /** The lowest and highest point of a CELL's opening, in inches above the tiles. */
    private static double[] openingHeights(Pose3d cell) {
        Vec3 mouth = cell.position().plus(cell.forward().scaled(BioBuzzHive.CELL_DEPTH_METRES / 2.0));
        double half = BioBuzzHive.CELL_RISE_METRES / 2.0;
        double low = mouth.plus(cell.up().scaled(-half)).z() / INCH;
        double high = mouth.plus(cell.up().scaled(half)).z() / INCH;
        return new double[] {Math.min(low, high), Math.max(low, high)};
    }

    /** The centre of the closed end a CELL's contents rest against. */
    private static Vec3 closedEnd(Pose3d cell) {
        return cell.position().plus(cell.forward().scaled(-BioBuzzHive.CELL_DEPTH_METRES / 2.0));
    }

    /** How close the nearest of these solids' centres comes to a point, in metres. */
    private static double nearest(List<Solid> solids, Vec3 point) {
        double closest = Double.MAX_VALUE;
        for (Solid solid : solids) {
            closest = Math.min(closest, solid.pose().position().minus(point).length());
        }
        return closest;
    }

    /** The alliance-coloured bars of a HIVE's ribs: everything else it draws is off-white. */
    private static List<Solid> ribBarsOf(Structure hive) {
        List<Solid> bars = new ArrayList<>();
        for (Solid solid : hive.drawn()) {
            if (solid.red() != solid.green()) {
                bars.add(solid);
            }
        }
        return bars;
    }

    /** A bar's two ends: {@link Solid#beam} lays its length along the solid's own up. */
    private static Vec3[] endsOf(Solid bar) {
        Vec3 half = bar.pose().up().scaled(bar.lengthZ() / 2.0);
        return new Vec3[] {bar.pose().position().plus(half), bar.pose().position().minus(half)};
    }

    /** {@code minX, maxX, minY, maxY, minZ, maxZ} of everything a structure collides with. */
    private static double[] extentOf(Structure structure) {
        List<Vec3> corners = new ArrayList<>();
        for (Solid solid : structure.collided()) {
            for (Surface face : SolidSurfaces.of(solid)) {
                corners.addAll(java.util.Arrays.asList(face.corners()));
            }
        }
        double[] extent = {
                Double.MAX_VALUE, -Double.MAX_VALUE,
                Double.MAX_VALUE, -Double.MAX_VALUE,
                Double.MAX_VALUE, -Double.MAX_VALUE,
        };
        for (Vec3 corner : corners) {
            double[] axes = {corner.x(), corner.y(), corner.z()};
            for (int axis = 0; axis < 3; axis++) {
                extent[axis * 2] = Math.min(extent[axis * 2], axes[axis] / INCH);
                extent[axis * 2 + 1] = Math.max(extent[axis * 2 + 1], axes[axis] / INCH);
            }
        }
        return extent;
    }

    private static double dot(Vec3 left, Vec3 right) {
        return left.x() * right.x() + left.y() * right.y() + left.z() * right.z();
    }
}
