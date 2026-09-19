package org.ngicollective.testframework.physics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.ngicollective.testframework.camera.GameElement;
import org.ngicollective.testframework.camera.Pose3d;
import org.ngicollective.testframework.camera.Vec3;
import org.ngicollective.testframework.season.BioBuzzField;
import org.ngicollective.testframework.season.BioBuzzHive;
import org.ngicollective.testframework.season.BioBuzzScore;
import org.ngicollective.testframework.sim.FieldConfig;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * What the HIVE does to the NECTAR and POLLEN aimed at it.
 *
 * <p>{@code BioBuzzHiveTest} checks where the geometry is; this is the half only a solver can
 * answer. A CELL exists to keep what lands in it and a tipped one to spill, and both are emergent
 * &mdash; they come out of five panels, a tip angle and gravity, not out of anything that can be
 * read off a field.</p>
 */
class HiveTest {

    private static final double TICK = 0.02;

    private static final BioBuzzField.HiveTip UP = BioBuzzField.HiveTip.AUDIENCE_UP;
    private static final BioBuzzField.HiveTip DOWN = BioBuzzField.HiveTip.AUDIENCE_DOWN;

    private static FieldPhysics worldWith(List<GameElement> balls) {
        return FieldPhysics.of(balls, BioBuzzHive.all(UP, DOWN), FieldConfig.standard(), null);
    }

    private static void run(FieldPhysics world, double seconds) {
        for (int tick = 0; tick < Math.round(seconds / TICK); tick++) {
            world.advance(TICK);
        }
    }

    /**
     * Two NECTAR aimed into a CELL, dropped from just above its opening.
     *
     * <p>Two and not three, even though three is what a match stages, because three arriving from
     * this height tip the HIVE: that is {@code HiveTipTest}'s subject and here it would empty the
     * basket these tests are about. Dropped rather than launched because what is under test is the
     * basket, not the shooter.</p>
     */
    private static List<GameElement> nectarOver(Pose3d cell) {
        Vec3 mouth = cell.position()
                .plus(cell.forward().scaled(BioBuzzHive.CELL_DEPTH_METRES / 2.0));
        List<GameElement> balls = new ArrayList<>(2);
        for (int ball = 0; ball < 2; ball++) {
            // Spread along the opening's width so they are not a column of spheres balanced on
            // one another, which is a different thing to test.
            Vec3 at = mouth.plus(cell.left().scaled((ball * 2 - 1) * 0.12))
                    .plus(new Vec3(0.0, 0.0, 0.10));
            balls.add(GameElement.redNectarAt(at.x(), at.y(), at.z()));
        }
        return balls;
    }

    /** How far a ball is from a CELL's interior, along each of the CELL's own axes. */
    private static double[] insideness(Pose3d cell, BodyState ball) {
        Vec3 offset = new Vec3(ball.x(), ball.y(), ball.z()).minus(cell.position());
        return new double[] {
                dot(offset, cell.forward()) / (BioBuzzHive.CELL_DEPTH_METRES / 2.0),
                dot(offset, cell.left()) / (BioBuzzHive.CELL_WIDTH_METRES / 2.0),
                dot(offset, cell.up()) / (BioBuzzHive.CELL_RISE_METRES / 2.0),
        };
    }

    private static double dot(Vec3 left, Vec3 right) {
        return left.x() * right.x() + left.y() * right.y() + left.z() * right.z();
    }

    @Test
    void nectarDroppedIntoARaisedCellStaysInIt() {
        // The whole point of a CELL. A panel in the wrong place, a shell built inside-out, or a
        // mouth facing the wrong way all leave a CELL that cannot hold a ball, and nothing else
        // would notice: the structure still renders and the tags still read.
        Pose3d cell = BioBuzzHive.cell(BioBuzzField.RED_AUDIENCE, UP, DOWN);
        FieldPhysics world = worldWith(nectarOver(cell));

        run(world, 3.0);

        for (BodyState ball : world.bodies()) {
            double[] where = insideness(cell, ball);
            assertTrue(Math.abs(where[1]) < 1.0,
                    ball + " escaped sideways: " + where[1] + " of the CELL's half-width");
            assertTrue(where[0] < 1.0,
                    ball + " is beyond the mouth: " + where[0] + " of the CELL's half-depth");
            assertTrue(where[2] > -1.2,
                    ball + " fell through the CELL's bottom face: " + where[2]);
            assertTrue(ball.z() > BioBuzzHive.PIVOT_HEIGHT_METRES,
                    ball + " is below the pivot, so it is not in a raised CELL any more");
        }
    }

    @Test
    void theSameThrowIntoTheLoweredCellFallsToTheTiles() {
        // The other half of bi-stability, and the control for the test above: the geometry is
        // identical, only the tip differs. If a lowered CELL held balls too, the model would be
        // scoring in a basket that is upside down, and the test above would pass for the wrong
        // reason.
        Pose3d lowered = BioBuzzHive.cell(BioBuzzField.RED_SCORING, UP, DOWN);
        FieldPhysics world = worldWith(nectarOver(lowered));

        run(world, 3.0);

        double resting = GameElement.NECTAR_DIAMETER_METRES;
        for (BodyState ball : world.bodies()) {
            assertTrue(ball.z() < resting,
                    ball + " is being held up by a CELL whose mouth faces the floor");
        }
    }

    @Test
    void aRobotCannotDriveThroughAFrameLeg() {
        // Why any of this is collision geometry. The A-frame's legs and the bars its feet sit on
        // are the only parts of the HIVE Structure a robot can reach, and a ball is the only thing
        // available to prove they are solid without a chassis in the world: drop one against a leg
        // and it has to be deflected rather than pass through.
        //
        // Aimed at a leg two thirds of the way up, where it has leaned well clear of its foot --
        // so a ball that ends up at the foot's x has gone through the leg, not round it.
        double legX = 23.80 * 0.0254;
        double legY = 18.56 * 0.0254;
        Vec3 against = new Vec3(
                legX - (legX - BioBuzzHive.HIVE_OFFSET_METRES) * 0.66,
                legY - (legY - 0.51 * 0.0254) * 0.66,
                30.0 * 0.0254);

        FieldPhysics bare = FieldPhysics.of(
                Arrays.asList(GameElement.pollenAt(against.x(), against.y(), against.z())),
                new ArrayList<>(), FieldConfig.standard(), null);
        FieldPhysics framed = worldWith(
                Arrays.asList(GameElement.pollenAt(against.x(), against.y(), against.z())));

        run(bare, 1.5);
        run(framed, 1.5);

        BodyState fell = bare.bodies().get(0);
        BodyState hit = framed.bodies().get(0);
        double moved = Math.hypot(hit.x() - fell.x(), hit.y() - fell.y());

        assertEquals(0.0, Math.hypot(fell.x() - against.x(), fell.y() - against.y()), 1e-6,
                "with nothing there a dropped ball lands where it started, which is the control");
        assertTrue(moved > GameElement.POLLEN_DIAMETER_METRES / 2.0,
                "the leg should have pushed the ball at least its own radius aside; it moved "
                        + moved + " m");
    }

    @Test
    void whatTheSolverLeavesInACellIsWhatScores() {
        // The end of the chain the rest of this file tests one link of at a time: a CELL's panels
        // catch three NECTAR, they settle, and the score reads what is in the basket.
        //
        // Worth running through the solver rather than by placing balls at the CELL's centre,
        // because a ball that has come to rest is nowhere near the centre: it is against the floor
        // panel, several inches down and off to one side, and what this asserts is that the
        // scoring volume reaches the places a ball actually ends up. A volume built from half
        // extents instead of full -- an easy thing to write, given every other half-size in this
        // package -- passes BioBuzzHiveTest's geometry checks and fails here.
        Pose3d raised = BioBuzzHive.cell(BioBuzzField.RED_AUDIENCE, UP, DOWN);
        FieldPhysics world = worldWith(nectarOver(raised));

        run(world, 3.0);
        BioBuzzScore score = BioBuzzScore.of(BioBuzzHive.cellVolumes(UP, DOWN),
                world.elements(), PivotState.swingsOf(world.pivots()));

        assertEquals(4, score.redPoints(),
                "two NECTAR at two points each, and the solver put them there: " + score);
        assertEquals(0, score.bluePoints(), "nothing was aimed at blue's CELL");
    }

    @Test
    void theSameThrowAtALoweredCellScoresNothing() {
        // The control for the test above. The throw is identical and the balls end on the tiles,
        // so this is what stops that one passing for the wrong reason: a score that counted every
        // ball on the field instead of the ones in a CELL would read 6 there and 6 here too.
        Pose3d lowered = BioBuzzHive.cell(BioBuzzField.RED_SCORING, UP, DOWN);
        FieldPhysics world = worldWith(nectarOver(lowered));

        run(world, 3.0);
        BioBuzzScore score = BioBuzzScore.of(BioBuzzHive.cellVolumes(UP, DOWN),
                world.elements(), PivotState.swingsOf(world.pivots()));

        assertEquals(0, score.redPoints(), "the lowered CELL spilled them: " + score);
        assertEquals(0, score.bluePoints());
    }
}
