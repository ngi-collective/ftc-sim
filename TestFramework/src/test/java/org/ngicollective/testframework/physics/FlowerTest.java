package org.ngicollective.testframework.physics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.ngicollective.testframework.camera.GameElement;
import org.ngicollective.testframework.camera.Structure;
import org.ngicollective.testframework.camera.Vec3;
import org.ngicollective.testframework.season.BioBuzzFlowers;
import org.ngicollective.testframework.sim.FieldConfig;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * What a FLOWER does to the POLLEN aimed at it.
 *
 * <p>The structure's own geometry is checked in {@code BioBuzzFlowersTest}; this is about the half
 * of it that only a solver can answer. A FLOWER exists to hold balls a robot puts in and to refuse
 * the ones it misses with, and both of those are emergent: they come out of a dozen little rim
 * boxes and four uprights, not out of anything that could be read off a field.</p>
 */
class FlowerTest {

    private static final double TICK = 0.02;

    /** Where the ring is, which is the thing a shot has to get through. */
    private static final Vec3 MOUTH =
            BioBuzzFlowers.all().get(0).drawn().get(0).pose().position();

    private static final double MOUTH_RADIUS =
            BioBuzzFlowers.all().get(0).drawn().get(0).radiusMetres();

    private static FieldPhysics worldWith(GameElement... balls) {
        return FieldPhysics.of(Arrays.asList(balls), BioBuzzFlowers.all(),
                FieldConfig.standard(), null);
    }

    private static void run(FieldPhysics world, double seconds) {
        for (int tick = 0; tick < Math.round(seconds / TICK); tick++) {
            world.advance(TICK);
        }
    }

    /** How far off the FLOWER's axis a ball has ended up. */
    private static double offAxis(BodyState ball) {
        return Math.hypot(ball.x() - MOUTH.x(), ball.y() - MOUTH.y());
    }

    @Test
    void stagedPollenStayInTheFlower() {
        // The setup a match starts from. If the cage leaks -- an upright missing, the gap between
        // two of them wider than a ball, a rim segment that a ball slips between -- then every
        // session starts with POLLEN rolling across the tiles, and the scenario that staged them
        // looks broken rather than the geometry.
        List<GameElement> staged = BioBuzzFlowers.pollenIn(BioBuzzFlowers.all().get(0).name());
        FieldPhysics world = worldWith(staged.toArray(new GameElement[0]));

        run(world, 4.0);

        double radius = GameElement.POLLEN_DIAMETER_METRES / 2.0;
        double highest = 0.0;
        double lowest = Double.MAX_VALUE;
        for (BodyState ball : world.bodies()) {
            assertTrue(offAxis(ball) < MOUTH_RADIUS,
                    ball + " left the cage: " + offAxis(ball) + " m off the axis");
            assertTrue(ball.z() > radius * 0.5, ball + " fell through the floor");
            highest = Math.max(highest, ball.z());
            lowest = Math.min(lowest, ball.z());
        }

        // Four balls of one diameter each cannot all be resting on the floor: a stack that
        // collapsed would mean they had passed through one another.
        assertTrue(highest - lowest > 2.0 * GameElement.POLLEN_DIAMETER_METRES,
                "four stacked POLLEN should span most of the cage; they span "
                        + (highest - lowest) + " m");
    }

    @Test
    void aPollenDroppedDownTheMouthEndsUpInside() {
        FieldPhysics world = worldWith(
                GameElement.pollenAt(MOUTH.x(), MOUTH.y(), MOUTH.z() + 0.10));

        run(world, 3.0);

        BodyState ball = world.bodies().get(0);
        assertTrue(offAxis(ball) < MOUTH_RADIUS,
                "a ball dropped straight down the mouth should be in the cage, not " + offAxis(ball)
                        + " m off the axis");
        assertTrue(ball.z() < MOUTH.z(), ball + " never went in");
    }

    /**
     * A shot that hits the rim is thrown clear, rather than quietly not counting.
     *
     * <p>Measured against the same drop into a bare field, and that control is the test. A ball
     * released off the axis lands off the axis whatever is underneath it, so "it did not end up
     * inside the FLOWER" would pass with no rim in the world at all &mdash; the rim's effect is
     * that it kicks the ball twice as far out again as gravity alone would put it.</p>
     */
    @Test
    void aPollenThatMissesTheMouthIsThrownClearOfIt() {
        // Centre one and a half ball radii off the axis: the ball overlaps the ring and its centre
        // is outside the opening, which is a miss by the only definition that matters.
        double missedBy = 0.075;
        FieldPhysics world = worldWith(
                GameElement.pollenAt(MOUTH.x() + missedBy, MOUTH.y(), MOUTH.z() + 0.10));
        run(world, 3.0);
        BodyState struck = world.bodies().get(0);

        FieldPhysics bare = FieldPhysics.of(
                Collections.singletonList(
                        GameElement.pollenAt(MOUTH.x() + missedBy, MOUTH.y(), MOUTH.z() + 0.10)),
                Collections.<Structure>emptyList(), FieldConfig.standard(), null);
        run(bare, 3.0);
        BodyState fell = bare.bodies().get(0);

        assertTrue(offAxis(struck) > MOUTH_RADIUS,
                "a missed shot ended up in the FLOWER anyway, " + offAxis(struck)
                        + " m off the axis");
        assertTrue(offAxis(struck) > offAxis(fell) * 1.5,
                "the rim should have thrown the ball clear: it reached " + offAxis(struck)
                        + " m off the axis, against " + offAxis(fell) + " m with no FLOWER there");
        assertEquals(GameElement.POLLEN_DIAMETER_METRES / 2.0, struck.z(), 3e-3,
                "a missed shot should end up on the tiles, not perched on the rim");
    }
}
