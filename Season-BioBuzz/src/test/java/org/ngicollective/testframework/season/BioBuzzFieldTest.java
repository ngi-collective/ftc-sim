package org.ngicollective.testframework.season;

import org.junit.jupiter.api.Test;
import org.ngicollective.testframework.camera.FieldTag;
import org.ngicollective.testframework.camera.Pose3d;
import org.ngicollective.testframework.camera.SimulatedScene;
import org.ngicollective.testframework.camera.TagCluster;
import org.ngicollective.testframework.camera.Vec3;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * That the season's field geometry matches the manual and the CAD.
 *
 * <p>Tolerances are no tighter than an inch anywhere absolute placement is asserted, because
 * FIRST's own field tolerance is an inch and a test that demanded better would be asserting
 * fiction.</p>
 */
class BioBuzzFieldTest {

    private static final double INCH = 0.0254;

    @Test
    void theFieldCarriesTheSixteenTagsTheSdkDeclares() {
        List<Integer> ids = new ArrayList<>();
        for (TagCluster cluster : BioBuzzField.clusters(
                BioBuzzField.HiveTip.AUDIENCE_UP, BioBuzzField.HiveTip.AUDIENCE_DOWN)) {
            for (FieldTag tag : cluster.tags()) {
                ids.add(tag.id());
                assertEquals(3.25 * INCH, tag.sizeMetres(), 1e-9);
            }
        }

        // 30-33 red scoring, 34-37 red audience, 38-41 blue audience, 42-45 blue scoring.
        List<Integer> expected = new ArrayList<>();
        for (int id = 30; id <= 45; id++) {
            expected.add(id);
        }
        java.util.Collections.sort(ids);
        assertEquals(expected, ids);
    }

    @Test
    void everyClusterFacesTheFloorSoACameraMustAimUp() {
        // The season's defining constraint for vision: the tags are on the undersides of the
        // CELLs, tilted thirty degrees, three to four feet up.
        for (TagCluster cluster : BioBuzzField.clusters(
                BioBuzzField.HiveTip.AUDIENCE_UP, BioBuzzField.HiveTip.AUDIENCE_DOWN)) {
            for (FieldTag tag : cluster.tags()) {
                assertEquals(-0.866, tag.visibleNormal().z(), 0.002,
                        "tag " + tag.id() + " should face 60 degrees below the horizon");
                double heightInches = tag.pose().position().z() / INCH;
                assertTrue(heightInches > 35.0 && heightInches < 50.0,
                        "tag " + tag.id() + " sits at " + heightInches + " in");
            }
        }
    }

    /**
     * The one fact the whole vision loop rests on: a cluster detection <em>is</em> an aim point.
     *
     * <p>Three sources that never mention each other have to agree for this to hold &mdash; the
     * SDK's member offsets, FIRST's CAD for the plate, and the manual's CELL dimensions &mdash; so
     * it is not a restatement of the construction. Break the plate roll and the origin lands two
     * feet away, behind the closed end of the basket, on exactly two of the four CELLs.</p>
     */
    @Test
    void everyClusterOriginSitsAtItsCellsOpening() {
        for (BioBuzzField.HiveTip tip : BioBuzzField.HiveTip.values()) {
            for (TagCluster cluster : BioBuzzField.clusters(tip, tip)) {
                Pose3d cell = BioBuzzHive.cell(cluster.name(), tip, tip);
                Vec3 mouth = cell.position()
                        .plus(cell.forward().scaled(BioBuzzHive.CELL_DEPTH_METRES / 2.0));

                double missInches = cluster.pose().position().minus(mouth).length() / INCH;
                assertTrue(missInches < 1.5,
                        cluster.name() + " tipped " + tip + ": the SDK's cluster origin lands "
                                + missInches + " in from the centre of the opening it is under,"
                                + " so an OpMode cannot aim at what it detects");
            }
        }
    }

    /**
     * And that the plate under a CELL is turned the way the CELL is, not merely placed there.
     *
     * <p>The position above is the assertion with teeth; this one says which axis was wrong when
     * it fails. A cluster's {@code forward} is the way the tags face, which is out through the
     * bottom of the basket, and its {@code up} runs from the opening toward the closed end.</p>
     */
    @Test
    void aPlatesAxesAreItsCellsAxes() {
        for (TagCluster cluster : BioBuzzField.clusters(
                BioBuzzField.HiveTip.AUDIENCE_UP, BioBuzzField.HiveTip.AUDIENCE_DOWN)) {
            Pose3d cell = BioBuzzHive.cell(cluster.name(), BioBuzzField.HiveTip.AUDIENCE_UP,
                    BioBuzzField.HiveTip.AUDIENCE_DOWN);

            assertEquals(0.0, cluster.pose().forward().plus(cell.up()).length(), 1e-6,
                    cluster.name() + ": the tags face out of the CELL's floor, so the plate's"
                            + " normal is the CELL's own up, negated");
            assertEquals(0.0, cluster.pose().up().plus(cell.forward()).length(), 1e-6,
                    cluster.name() + ": the plate's up runs back from the opening, so it is the"
                            + " CELL's forward, negated -- this is the 180 degrees of roll that"
                            + " the two CELLs of one HIVE differ by");
        }
    }

    @Test
    void theHivesSitWhereTheManualSaysAndMirrorEachOther() {
        // The manual gives 25.5 in between HIVE centres; the CAD agrees to a hundredth of an inch.
        // Measured on the tags, since a cluster's own pose is the SDK's frame, not the plate.
        double red = meanTagX(BioBuzzField.RED_AUDIENCE);
        double blue = meanTagX(BioBuzzField.BLUE_AUDIENCE);

        assertEquals(-12.75, red, 0.02);
        assertEquals(12.75, blue, 0.02);
        assertEquals(25.5, blue - red, 0.05);
    }

    @Test
    void tippingAHiveSwapsWhichCellIsRaised() {
        // The signature BioBuzz event. One HIVE tips; its two CELLs exchange heights, and the pair
        // stays rigid because a HIVE is one object.
        double audienceUp = tagHeight(BioBuzzField.RED_AUDIENCE, BioBuzzField.HiveTip.AUDIENCE_UP);
        double scoringUp = tagHeight(BioBuzzField.RED_SCORING, BioBuzzField.HiveTip.AUDIENCE_UP);
        double audienceDown =
                tagHeight(BioBuzzField.RED_AUDIENCE, BioBuzzField.HiveTip.AUDIENCE_DOWN);
        double scoringDown =
                tagHeight(BioBuzzField.RED_SCORING, BioBuzzField.HiveTip.AUDIENCE_DOWN);

        assertTrue(audienceUp > scoringUp,
                "tipped audience-up, the audience CELL should be the high one");
        assertTrue(audienceDown < scoringDown,
                "tipped audience-down, it should be the low one");

        // The heights are the two the CAD measured, whichever CELL is in them.
        assertEquals(49.666, audienceUp, 0.02);
        assertEquals(35.647, audienceDown, 0.02);
    }

    @Test
    void aTipDoesNotMirrorTheTagRowItCarries() {
        // The pivot axis is field X and so is the tag row, so a tip cannot change which way the
        // ids run: 34 stays on the same side of the CELL it is stuck to. This is the one thing a
        // hand-built "tipped" plate pose gets wrong. A plate starts 60 degrees below the horizon
        // and 60 degrees of tip takes it off the end of the yaw-pitch-roll chart, so the state on
        // the other side needs 180 degrees of roll; leave it at zero, as the pose reads more
        // naturally, and the four tags come out reversed along the plate with every pattern turned
        // upside down. Nothing about that looks wrong in a field view, and a detector reports zero
        // detections, because a rotated 36h11 codeword is usually not a codeword.
        List<FieldTag> upright = clusterNamed(BioBuzzField.RED_AUDIENCE,
                BioBuzzField.HiveTip.AUDIENCE_UP).tags();
        List<FieldTag> tipped = clusterNamed(BioBuzzField.RED_AUDIENCE,
                BioBuzzField.HiveTip.AUDIENCE_DOWN).tags();

        assertEquals(34, upright.get(0).id());
        assertEquals(34, tipped.get(0).id());
        double alongRowUpright = upright.get(3).pose().position().x()
                - upright.get(0).pose().position().x();
        double alongRowTipped = tipped.get(3).pose().position().x()
                - tipped.get(0).pose().position().x();
        assertEquals(alongRowUpright, alongRowTipped, 1e-9,
                "the row runs along the pivot's own axis, which a rotation about it cannot flip");

        // And each tag's own up is still the plate's up rather than its negative, which is what a
        // 180 degree error in roll would leave: the two differ by a sign nothing else reveals.
        assertEquals(upright.get(0).tagX().x(), tipped.get(0).tagX().x(), 1e-9);
    }

    @Test
    void tagsRunAcrossTheFieldAtTheManualsUnevenSpacing() {
        // Manual figure 9-15: the inner pair straddles the CELL's centre post, so the gaps are
        // 3.75 in, 5.5 in, 3.75 in. Even spacing would mean someone had tidied up the SDK's data.
        List<FieldTag> tags = clusterNamed(BioBuzzField.RED_AUDIENCE).tags();
        List<Double> gaps = new ArrayList<>();
        for (int i = 0; i < tags.size() - 1; i++) {
            gaps.add(tags.get(i).pose().position()
                    .minus(tags.get(i + 1).pose().position()).length() / INCH);
        }

        assertEquals(Arrays.asList(3.75, 5.50, 3.75).size(), gaps.size());
        assertEquals(3.75, gaps.get(0), 0.01);
        assertEquals(5.50, gaps.get(1), 0.01);
        assertEquals(3.75, gaps.get(2), 0.01);

        // One row, all at the same height: a cluster is a flat plate.
        for (FieldTag tag : tags) {
            assertEquals(tags.get(0).pose().position().z(), tag.pose().position().z(), 1e-9);
        }
    }

    @Test
    void theOfficialFieldIsTheOneTheCadCaptured() {
        SimulatedScene official = BioBuzzField.official();

        assertEquals(4, official.clusters().size());
        assertTrue(official.elements().isEmpty(),
                "where the balls start is match setup, not field geometry");
    }

    private static TagCluster clusterNamed(String name) {
        return clusterNamed(name, BioBuzzField.HiveTip.AUDIENCE_UP);
    }

    private static TagCluster clusterNamed(String name, BioBuzzField.HiveTip redTip) {
        for (TagCluster cluster : BioBuzzField.clusters(redTip,
                BioBuzzField.HiveTip.AUDIENCE_DOWN)) {
            if (cluster.name().equals(name)) {
                return cluster;
            }
        }
        throw new AssertionError("no cluster named " + name);
    }

    /** Where a cluster's tag row sits across the field, in inches. */
    private static double meanTagX(String name) {
        double sum = 0.0;
        List<FieldTag> tags = clusterNamed(name).tags();
        for (FieldTag tag : tags) {
            sum += tag.pose().position().x();
        }
        return sum / tags.size() / INCH;
    }

    /** How high a cluster's tags hang, in inches; every member shares one height. */
    private static double tagHeight(String name, BioBuzzField.HiveTip redTip) {
        return clusterNamed(name, redTip).tags().get(0).pose().position().z() / INCH;
    }
}
