package org.ngicollective.testframework.season;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.ngicollective.testframework.camera.GameElement;
import org.ngicollective.testframework.camera.Pose3d;
import org.ngicollective.testframework.camera.ScoringVolume;
import org.ngicollective.testframework.camera.SimulatedScene;
import org.ngicollective.testframework.camera.Vec3;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The manual's CELL scoring rules, against the geometry that decides them.
 *
 * <p>Every test here is one clause of manual &sect;10.5.1 and Table 10-2. They are worth having
 * separately from {@code BioBuzzHiveTest}, which asks where the CELLs are: a score can be wrong
 * with the geometry perfectly right, by counting the wrong basket, crediting the wrong alliance or
 * paying out for a ball that is only leaning on a rim.</p>
 */
class BioBuzzScoreTest {

    private static final BioBuzzField.HiveTip UP = BioBuzzField.HiveTip.AUDIENCE_UP;
    private static final BioBuzzField.HiveTip DOWN = BioBuzzField.HiveTip.AUDIENCE_DOWN;

    /** The official arrangement: red tipped audience-up, blue audience-down. */
    private static List<ScoringVolume> official() {
        return BioBuzzHive.cellVolumes(UP, DOWN);
    }

    /**
     * A score with no HIVE TIPs in it, which is what every test below one is about.
     *
     * <p>Wrapped rather than repeated ten times: an empty map at every call site would bury the
     * difference between the cases that do and do not involve a tip. The tips themselves are
     * exercised through the real signature.</p>
     */
    private static BioBuzzScore scoreOf(List<ScoringVolume> volumes, List<GameElement> elements) {
        return BioBuzzScore.of(volumes, elements, Collections.<String, Integer>emptyMap());
    }

    /** A ball at the dead centre of a CELL's interior, which is as scored as a ball can be. */
    private static GameElement pollenIn(String cellName, BioBuzzField.HiveTip red,
                                        BioBuzzField.HiveTip blue) {
        Vec3 at = BioBuzzHive.cell(cellName, red, blue).position();
        return GameElement.pollenAt(at.x(), at.y(), at.z());
    }

    @Test
    void onlyTheUpwardFacingCellOfEachHiveIsScored() {
        // Which CELLs are in play is decided from the geometry, not from the list handed over: all
        // four are, because all four are somewhere a ball can be. Scoring all four would mean a
        // HIVE scoring in both of its baskets at once, which is twice the points the field can
        // hold.
        assertEquals(4, official().size(), "a HIVE has two CELLs and there are two HIVEs");

        BioBuzzScore score = scoreOf(official(), Collections.<GameElement>emptyList());

        assertEquals(Arrays.asList(BioBuzzField.RED_AUDIENCE, BioBuzzField.BLUE_SCORING),
                Arrays.asList(score.cells().get(0).cellName(), score.cells().get(1).cellName()),
                "red is tipped audience-up and blue audience-down, so those are the raised CELLs");
        assertEquals(2, score.cells().size());
    }

    @Test
    void aBallInARaisedCellIsWorthTwoPointsToThatCellsAlliance() {
        BioBuzzScore score = scoreOf(official(),
                Collections.singletonList(pollenIn(BioBuzzField.RED_AUDIENCE, UP, DOWN)));

        assertEquals(2, score.redPoints(), "Table 10-2 prices one element in a CELL at 2");
        assertEquals(0, score.bluePoints(), "blue's CELL is empty");
        assertEquals(1, score.cell(BioBuzzField.RED_AUDIENCE).holding().size());
        assertEquals(0, score.cell(BioBuzzField.BLUE_SCORING).holding().size(),
                "an empty CELL in play is still reported, so a driver can tell it from a CELL"
                        + " that is face down");
    }

    @Test
    void theMatchStagingScoresSixPointsForEachAlliance() {
        // Manual §10.3.1: three NECTAR are staged in each upward-facing CELL before the match
        // starts. So a field nobody has touched already reads 6-6, and that is a cross-check
        // against the manual rather than against this code: three balls at 2 points each.
        List<GameElement> staged = new java.util.ArrayList<>();
        for (int ball = 0; ball < 3; ball++) {
            staged.add(pollenIn(BioBuzzField.RED_AUDIENCE, UP, DOWN));
            staged.add(pollenIn(BioBuzzField.BLUE_SCORING, UP, DOWN));
        }

        BioBuzzScore score = scoreOf(official(), staged);

        assertEquals(6, score.redPoints());
        assertEquals(6, score.bluePoints());
    }

    @Test
    void aBallInALoweredCellScoresForNobody() {
        // The control for the test above, and not a hypothetical: a scenario file can stage a ball
        // anywhere, including inside a CELL whose mouth faces the floor, and this is scored before
        // the solver has had a tick to empty it. Scoring by geometry alone — "is it in a box the
        // shape of a CELL" — would pay out here.
        GameElement inLowered = pollenIn(BioBuzzField.RED_SCORING, UP, DOWN);

        BioBuzzScore score = scoreOf(official(), Collections.singletonList(inLowered));

        assertEquals(0, score.redPoints(), inLowered + " is in the CELL that is facing down");
        assertEquals(0, score.bluePoints());
        assertThrows(IllegalArgumentException.class,
                () -> score.cell(BioBuzzField.RED_SCORING),
                "a CELL that is face down is not in play, which is not the same as empty");
    }

    @Test
    void anOpponentsNectarScoresForTheCellItIsIn() {
        // "any POLLEN and/or NECTAR left in an upward-facing CELL will earn points for that
        // ALLIANCE". Points follow the basket, not the ball: filtering by colour here would look
        // right on a practice field with one alliance's elements and be wrong in every match.
        Vec3 at = BioBuzzHive.cell(BioBuzzField.RED_AUDIENCE, UP, DOWN).position();

        BioBuzzScore score = scoreOf(official(), Collections.singletonList(
                GameElement.blueNectarAt(at.x(), at.y(), at.z())));

        assertEquals(2, score.redPoints(), "a blue NECTAR in red's CELL is red's two points");
        assertEquals(0, score.bluePoints());
    }

    @Test
    void tippingAHiveTakesTheScoreWithIt() {
        // One rotation of one rigid body swaps which CELL is up, so the same ball in the same place
        // stops scoring. This is the whole reason the volumes are derived from the tip rather than
        // fixed to the field.
        GameElement ball = pollenIn(BioBuzzField.RED_AUDIENCE, UP, DOWN);

        assertEquals(2, scoreOf(official(), Collections.singletonList(ball)).redPoints());
        assertEquals(0,
                scoreOf(BioBuzzHive.cellVolumes(DOWN, DOWN),
                        Collections.singletonList(ball)).redPoints(),
                "tipped the other way, that CELL is face down and the ball is not in the raised"
                        + " one");
    }

    @Test
    void aBallOverTheMouthOfACellIsNotInIt() {
        // The boundary that decides a score. A CELL is 12 in deep and its mouth is the far face of
        // that box, so a ball hovering above the opening has not scored yet however close it is —
        // and one an inch inside has. Scoring "at least partially within", which is what the
        // FLOWER and GARDEN rules say and the CELL rule does not, would pay out for the first.
        Pose3d cell = BioBuzzHive.cell(BioBuzzField.RED_AUDIENCE, UP, DOWN);
        double halfDepth = BioBuzzHive.CELL_DEPTH_METRES / 2.0;

        Vec3 justOutside = cell.position().plus(cell.forward().scaled(halfDepth + 0.001));
        Vec3 justInside = cell.position().plus(cell.forward().scaled(halfDepth - 0.001));

        assertEquals(0, scoreOf(official(), Collections.singletonList(
                GameElement.pollenAt(justOutside.x(), justOutside.y(), justOutside.z())))
                .redPoints(), "a millimetre past the mouth is not in the CELL");
        assertEquals(2, scoreOf(official(), Collections.singletonList(
                GameElement.pollenAt(justInside.x(), justInside.y(), justInside.z())))
                .redPoints(), "a millimetre inside it is");
    }

    @Test
    void aBallBesideTheHiveScoresNothing() {
        // Three feet up in the middle of the field is where the CELLs are; the tiles beneath them
        // are not a CELL. A volume centred on the pivot instead of on the interior, or extents
        // taken as half rather than full, would both show up as points for a ball on the floor.
        BioBuzzScore score = scoreOf(official(),
                Collections.singletonList(GameElement.pollen(0.0, 0.0)));

        assertEquals(0, score.redPoints());
        assertEquals(0, score.bluePoints());
    }

    @Test
    void theOfficialSceneCarriesTheCellsAScoreIsReadFrom() {
        // What the Dashboard and any scenario-driven test actually read. A scene that dropped them
        // on the way through its own copies — withElements is called on every physics step — would
        // leave a session that scores correctly for one tick and then reports nothing.
        SimulatedScene scene = BioBuzzField.official()
                .withElements(Collections.singletonList(
                        pollenIn(BioBuzzField.RED_AUDIENCE, UP, DOWN)));

        BioBuzzScore score = scoreOf(scene.scoringVolumes(), scene.elements());

        assertEquals(2, score.redPoints());
        assertEquals(4, scene.scoringVolumes().size(), "every CELL, facing whichever way it does");
    }

    @Test
    void eachHiveTipIsTwentyPointsToItsOwnAlliance() {
        // Manual Table 10-2, and the reason the count is cumulative: every TIP scores again, so an
        // alliance that tips three times has 60 points whatever is in the CELL afterwards. A tip
        // also empties the basket that earned it, which is why these are reported separately --
        // twelve points of POLLEN becoming one TIP is a total that went up by 8.
        Map<String, Integer> tips = new LinkedHashMap<>();
        tips.put(BioBuzzHive.RED, 3);
        tips.put(BioBuzzHive.BLUE, 1);

        BioBuzzScore score = BioBuzzScore.of(official(),
                Collections.singletonList(pollenIn(BioBuzzField.RED_AUDIENCE, UP, DOWN)), tips);

        assertEquals(62, score.redPoints(), "three TIPs at 20, plus the one POLLEN still in there");
        assertEquals(20, score.bluePoints());
        assertEquals(3, score.redTips());
        assertEquals(1, score.blueTips());
    }

    @Test
    void aSceneThatIsNotASeasonFieldHasNowhereToScore() {
        // A bare cluster fixture, or a taped-out gym. Answering with the official CELLs here would
        // put points on a field that has no HIVE standing on it.
        assertEquals(Collections.emptyList(), SimulatedScene.of().scoringVolumes());
    }
}
