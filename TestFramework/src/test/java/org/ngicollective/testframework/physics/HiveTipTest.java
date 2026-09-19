package org.ngicollective.testframework.physics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.ngicollective.testframework.camera.GameElement;
import org.ngicollective.testframework.camera.Pose3d;
import org.ngicollective.testframework.camera.SimulatedScene;
import org.ngicollective.testframework.camera.Vec3;
import org.ngicollective.testframework.season.BioBuzzField;
import org.ngicollective.testframework.season.BioBuzzHive;
import org.ngicollective.testframework.season.BioBuzzScore;
import org.ngicollective.testframework.sim.FieldConfig;

import java.util.ArrayList;
import java.util.List;

/**
 * The HIVE TIP: the thing the whole season aims at.
 *
 * <p>Manual &sect;9.6: "Each HIVE is bi-stable and will hold its position until enough POLLEN or
 * NECTAR are LAUNCHED into the upwards-facing CELL." Nothing in this simulator decides that a tip
 * has happened &mdash; the HIVE is a body on a hinge with a hold torque and a damper, and every
 * test here is asking the solver what it did with what was thrown at it.</p>
 *
 * <h2>These state what the hold torque implies, rather than enshrining it</h2>
 *
 * <p>{@link BioBuzzHive#PIVOT_HOLD_NEWTON_METRES} is an estimate bracketed by two things the
 * manual does print, and somebody with a real field will measure it properly one day. So the
 * assertions below are written to hold for any figure in that bracket: the staged NECTAR stay put,
 * a handful more tips it, one ball never does, and a tip takes long enough to shoot into. The
 * exact counts appear in the messages rather than in the expectations wherever that is possible.
 * </p>
 */
class HiveTipTest {

    private static final double TICK = 0.02;

    private static final BioBuzzField.HiveTip UP = BioBuzzField.HiveTip.AUDIENCE_UP;
    private static final BioBuzzField.HiveTip DOWN = BioBuzzField.HiveTip.AUDIENCE_DOWN;

    /** The red HIVE's raised CELL in the official arrangement, which is the one everything aims at. */
    private static Pose3d raisedCell() {
        return BioBuzzHive.cell(BioBuzzField.RED_AUDIENCE, UP, DOWN);
    }

    /**
     * Balls put into a CELL, either staged in it or dropped above its mouth.
     *
     * <p>The two are different experiments and the difference is the point: a HIVE holds three
     * NECTAR staged in it and tips on three arriving from above. Staged means what a scenario
     * file's {@code "cell"} does &mdash; the interior's own centre, spread along its width by a
     * NECTAR's diameter, left to settle by the solver &mdash; so this is the arrangement a match
     * really starts from rather than an approximation of it.</p>
     */
    private static List<GameElement> stagedIn(Pose3d cell, int count, boolean pollen) {
        List<GameElement> balls = new ArrayList<>(count);
        for (int ball = 0; ball < count; ball++) {
            int step = (ball + 1) / 2 * (ball % 2 == 0 ? -1 : 1);
            Vec3 at = cell.position()
                    .plus(cell.left().scaled(step * GameElement.NECTAR_DIAMETER_METRES));
            balls.add(pollen
                    ? GameElement.pollenAt(at.x(), at.y(), at.z())
                    : GameElement.redNectarAt(at.x(), at.y(), at.z()));
        }
        return balls;
    }

    /** The same count dropped in through the mouth, which is the half that tips a HIVE. */
    private static List<GameElement> droppedInto(Pose3d cell, int count, boolean pollen) {
        Vec3 mouth = cell.position()
                .plus(cell.forward().scaled(BioBuzzHive.CELL_DEPTH_METRES / 2.0));
        List<GameElement> balls = new ArrayList<>(count);
        for (int ball = 0; ball < count; ball++) {
            Vec3 at = mouth.plus(cell.left().scaled(((ball % 3) - 1) * 0.12))
                    .plus(new Vec3(0.0, 0.0, 0.10 + 0.09 * (ball / 3)));
            balls.add(pollen
                    ? GameElement.pollenAt(at.x(), at.y(), at.z())
                    : GameElement.redNectarAt(at.x(), at.y(), at.z()));
        }
        return balls;
    }

    private static FieldPhysics worldWith(List<GameElement> balls) {
        return FieldPhysics.of(balls, BioBuzzHive.all(UP, DOWN), FieldConfig.standard(), null);
    }

    private static void run(FieldPhysics world, double seconds) {
        for (int tick = 0; tick < Math.round(seconds / TICK); tick++) {
            world.advance(TICK);
        }
    }

    private static PivotState redHive(FieldPhysics world) {
        for (PivotState pivot : world.pivots()) {
            if (pivot.structureName().equals(BioBuzzHive.RED)) {
                return pivot;
            }
        }
        throw new AssertionError("no red HIVE in " + world.pivots());
    }

    private static double degrees(FieldPhysics world) {
        return Math.toDegrees(redHive(world).angleRadians());
    }

    @Test
    void aHiveStandsWhereItsScenarioPutItAndReportsThatAngle() {
        // The zero of the pivot is the state the CAD was captured in, so a HIVE staged the other
        // way round starts at the far end of its own travel rather than at zero. Getting this
        // wrong would put every scenario's blue HIVE upright in the browser and leave its CELLs'
        // scoring volumes in mid-air.
        FieldPhysics official = worldWith(new ArrayList<GameElement>());

        run(official, 0.5);

        assertEquals(0.0, degrees(official), 0.5, "red is staged audience-up, which is the zero");
        assertEquals(2.0 * BioBuzzHive.TIP_DEGREES,
                Math.toDegrees(official.pivots().get(1).angleRadians()), 0.5,
                "and blue audience-down, which is the other stop: " + official.pivots());
        assertEquals(0, redHive(official).completedSwings(), "nobody has tipped anything");
    }

    @Test
    void theNectarAMatchStagesInACellDoesNotTipIt() {
        // Manual §10.3.1 stages three NECTAR in each upward-facing CELL, so a HIVE that went under
        // its own match setup would be a simulator nobody could start a match in. This is the
        // lower bound the hold torque is chosen against, and the one that fails first if anybody
        // lowers it.
        FieldPhysics world = worldWith(stagedIn(raisedCell(), 3, false));

        run(world, 3.0);

        assertEquals(0.0, degrees(world), 1.0, "the staged NECTAR are resting, not launching");
        assertEquals(0, redHive(world).completedSwings());
        assertEquals(6, BioBuzzScore.of(BioBuzzHive.cellVolumes(UP, DOWN), world.elements(),
                PivotState.swingsOf(world.pivots())).redPoints(),
                "and all three are still in there, which is the six points a match starts at");
    }

    @Test
    void enoughPollenInTheRaisedCellTipsTheHiveAndEmptiesIt() {
        // The season's whole mechanic, and every part of it is emergent: nothing counts balls or
        // decides a tip has happened. The HIVE goes over because the weight in one basket beats
        // what holds it, and it empties because the basket it was in is now pointing at the floor.
        FieldPhysics world = worldWith(droppedInto(raisedCell(), 6, true));

        run(world, 3.0);

        assertEquals(2.0 * BioBuzzHive.TIP_DEGREES, degrees(world), 1.0,
                "six POLLEN should have put it against the far stop");
        assertEquals(1, redHive(world).completedSwings(), "which is one HIVE TIP");

        // Scored against the CELLs where they now are, which is the whole reason the volumes ride
        // the scene: read off the staged geometry instead and the score would still be looking for
        // balls in a basket that has turned over.
        SimulatedScene scene = BioBuzzField.scene(UP, DOWN)
                .tipped(PivotState.anglesOf(world.pivots()));
        BioBuzzScore score = BioBuzzScore.of(scene.scoringVolumes(), world.elements(),
                PivotState.swingsOf(world.pivots()));
        assertEquals(BioBuzzScore.POINTS_PER_TIP, score.redPoints(),
                "the TIP scores 20 and the basket it scored from is empty: " + score);
        assertEquals(0, score.cell(BioBuzzField.RED_SCORING).holding().size(),
                "the CELL now facing up was never shot at");
    }

    @Test
    void oneBallLaunchedHardAtACellDoesNotTipIt() {
        // The control, and the rule: "enough POLLEN or NECTAR", not one good shot. A hinge with no
        // hold torque at all would pass every other test here and fail this one, and so would one
        // whose bi-stability came only from the balls already in the basket.
        Pose3d cell = raisedCell();
        Vec3 mouth = cell.position()
                .plus(cell.forward().scaled(BioBuzzHive.CELL_DEPTH_METRES / 2.0));
        // Straight in through the mouth at 6 m/s, which is about what a legal launcher can throw
        // from the nearest a ROBOT can stand.
        List<GameElement> one = new ArrayList<>();
        one.add(GameElement.redNectarAt(mouth.x(), mouth.y() - 0.3, mouth.z() - 0.1));
        FieldPhysics world = worldWith(one);

        run(world, 2.0);

        assertTrue(Math.abs(degrees(world)) < 1.0,
                "one NECTAR is not enough, at " + degrees(world) + " degrees");
        assertEquals(0, redHive(world).completedSwings());
    }

    @Test
    void aTipTakesLongEnoughToShootIntoAndIsCountedOnceItArrives() {
        // Manual §10.5.1 dates a TIP from the damper reaching the frame, and warns that
        // "LAUNCHING at the downward-facing CELL while a HIVE is tipping may disrupt its
        // movement" -- which is only a thing that can happen if a tip is a motion rather than an
        // instant. A HIVE that snapped over in one solver step would make that warning
        // unreproducible and would let a shot land in a CELL that no longer exists.
        FieldPhysics world = worldWith(droppedInto(raisedCell(), 6, true));

        double leftTheStop = Double.NaN;
        double arrived = Double.NaN;
        for (int tick = 0; tick < 150; tick++) {
            world.advance(TICK);
            if (Double.isNaN(leftTheStop) && degrees(world) > 1.0) {
                leftTheStop = tick * TICK;
            }
            if (Double.isNaN(arrived) && redHive(world).completedSwings() > 0) {
                arrived = tick * TICK;
            }
        }

        double swing = arrived - leftTheStop;
        assertTrue(swing > 0.3 && swing < 2.0,
                "a tip should be watchable and over quickly; took " + swing + "s");
        // And the count is of arrivals, not of movement: the angle passes through every value in
        // between and none of them is a TIP.
        assertEquals(1, redHive(world).completedSwings());
        run(world, 2.0);
        assertEquals(1, redHive(world).completedSwings(),
                "and it stays at one while the HIVE sits against its damper");
    }

    @Test
    void aTipCarriesTheCellsScoringVolumeAndItsTagsWithIt() {
        // The reason a tip goes through the scene rather than being applied to any one of them: an
        // OpMode ranges off the AprilTag cluster under a CELL and scores into the volume behind
        // its mouth, and those two plus the panels a ball bounces off have to be one rigid body.
        FieldPhysics world = worldWith(droppedInto(raisedCell(), 6, true));
        SimulatedScene before = BioBuzzField.scene(UP, DOWN);
        // A tag, not the cluster's own origin: the SDK's cluster frame sits nine inches behind the
        // plate the tags are printed on, so it moves a good deal less than they do.
        Vec3 tagBefore = before.clusters().get(1).tags().get(0).pose().position();

        run(world, 3.0);
        SimulatedScene after = before.tipped(PivotState.anglesOf(world.pivots()));

        // The red audience CELL was up and is now down, so its volume's mouth points at the floor
        // and its tags have swung to the other side of the pivot.
        assertEquals(BioBuzzField.RED_AUDIENCE, after.scoringVolumes().get(1).name());
        assertTrue(after.scoringVolumes().get(1).pose().forward().z() < 0.0,
                "the CELL that was shot into is now facing down: "
                        + after.scoringVolumes().get(1).pose());
        assertEquals(BioBuzzField.RED_AUDIENCE, after.clusters().get(1).name());
        Vec3 tagAfter = after.clusters().get(1).tags().get(0).pose().position();
        assertTrue(tagAfter.z() < tagBefore.z() - 0.3,
                "the raised CELL is the lowered one now, so its tags have come down a foot, from "
                        + tagBefore + " to " + tagAfter);
        // Rigidly: every point on a HIVE keeps its distance from the axis.
        Vec3 pivot = BioBuzzHive.pivotPoint(true);
        assertEquals(radiusFrom(pivot, tagBefore), radiusFrom(pivot, tagAfter), 1e-9,
                "a tip is a rotation, and a rotation cannot move a tag off its own circle");
    }

    private static double radiusFrom(Vec3 axis, Vec3 point) {
        return Math.hypot(point.y() - axis.y(), point.z() - axis.z());
    }
}
