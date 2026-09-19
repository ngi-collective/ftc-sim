package org.ngicollective.testframework.physics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.ngicollective.testframework.camera.GameElement;
import org.ngicollective.testframework.camera.Structure;
import org.ngicollective.testframework.camera.Vec3;
import org.ngicollective.testframework.sim.FieldConfig;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * What the balls on the field do when nothing drives into them.
 *
 * <p>These are invariants and bands, never trajectories. Recording where ode4j 0.5.4 happens to
 * put a ball after two seconds and asserting that number forever would make the solver's arbitrary
 * choices our contract: the test would fail on an upgrade that improved the physics, and it would
 * pass on a change that broke something the number does not describe. What a driver actually
 * depends on is that a ball at rest stays put, that a rolling one stops, that nothing gains energy
 * on its own, and that how fast you are watching does not change what happens &mdash; so those are
 * what is written down.</p>
 */
class FieldPhysicsTest {

    /** The dashboard's control cycle, which is what hands time to the physics in production. */
    private static final double TICK = 0.02;

    private static final double GRAVITY = 9.806;

    private FieldPhysics world(GameElement... arrangement) {
        return FieldPhysics.of(Arrays.asList(arrangement),
                Collections.<Structure>emptyList(), FieldConfig.standard(), null);
    }

    private void run(FieldPhysics world, double seconds) {
        for (int tick = 0; tick < Math.round(seconds / TICK); tick++) {
            world.advance(TICK);
        }
    }

    private static BodyState only(FieldPhysics world) {
        List<BodyState> bodies = world.bodies();
        assertEquals(1, bodies.size(), "this fixture has one ball in it");
        return bodies.get(0);
    }

    @Test
    void aBallLeftAloneStaysWhereItWasPut() {
        FieldPhysics world = world(GameElement.pollen(0.5, -0.75));

        run(world, 5.0);

        BodyState ball = only(world);
        // A tenth of a millimetre over five seconds. A solver that leaks energy into a resting
        // contact shows up here first, and it shows up as a field that slowly rearranges itself
        // while nobody is driving -- which would make every autonomous test irreproducible.
        assertEquals(0.5, ball.x(), 1e-4, "a ball nobody touched drifted along X");
        assertEquals(-0.75, ball.y(), 1e-4, "a ball nobody touched drifted along Y");
        assertEquals(GameElement.POLLEN_DIAMETER_METRES / 2.0, ball.z(), 1e-3,
                "a resting ball should sit on the floor, on its own radius");
        assertFalse(world.moving(), "a field nobody has touched should report itself still");
    }

    @Test
    void aBallDroppedOnTheFieldSettlesOntoTheFloor() {
        double radius = GameElement.NECTAR_DIAMETER_METRES / 2.0;
        FieldPhysics world = world(new GameElement("RED NECTAR", new Vec3(0.0, 0.0, 0.8),
                GameElement.NECTAR_DIAMETER_METRES, 200, 30, 40));

        run(world, 4.0);

        BodyState ball = only(world);
        assertEquals(radius, ball.z(), 2e-3, "a dropped ball should end up resting on the floor");
        // Nothing pushed it sideways, so nothing may have moved it sideways. A ball that wanders
        // off the spot it was dropped on means the contact solver is injecting tangential velocity.
        assertEquals(0.0, Math.hypot(ball.x(), ball.y()), 0.01,
                "a ball dropped straight down should land where it was dropped");
    }

    @Test
    void noBallGainsEnergyOnItsOwn() {
        FieldPhysics world = world(new GameElement("POLLEN", new Vec3(0.2, 0.2, 0.6),
                GameElement.POLLEN_DIAMETER_METRES, 240, 200, 30));

        // Per unit mass, so the nominal ball density never enters the assertion: gh + v^2/2, with
        // the speed taken from how far the ball moved between two published frames -- which is the
        // only velocity a consumer of this world can see.
        BodyState previous = only(world);
        double budget = GRAVITY * previous.z();
        double worst = 0.0;
        for (int tick = 0; tick < 200; tick++) {
            world.advance(TICK);
            BodyState now = only(world);
            double speed = Math.sqrt(
                    Math.pow(now.x() - previous.x(), 2)
                            + Math.pow(now.y() - previous.y(), 2)
                            + Math.pow(now.z() - previous.z(), 2)) / TICK;
            worst = Math.max(worst, GRAVITY * now.z() + speed * speed / 2.0);
            previous = now;
        }

        // A 2% allowance for the finite difference straddling a bounce, where the sampled speed is
        // an average over a tick in which the direction reversed. Anything beyond that is the
        // solver adding energy, which is how a simulated field starts throwing its own game
        // elements around.
        assertTrue(worst <= budget * 1.02,
                "a ball only falling and bouncing gained energy: " + worst + " J/kg against a "
                        + budget + " J/kg budget");
    }

    @Test
    void howTheTimeArrivesDoesNotChangeWhereTheBallsEndUp() {
        // The whole point of the fixed substep. A dashboard tick is 20 ms at 1x and 160 ms at 8x,
        // and the on-device clock hands over whatever the last frame took; if the solver saw those
        // numbers the robot would behave differently at different speed multipliers.
        //
        // 20 ms is five 4 ms substeps and 200 ms is fifty, so all three of these worlds must run
        // the same 250 substeps over the same simulated second.
        GameElement dropped = new GameElement("POLLEN", new Vec3(0.3, -0.3, 0.7),
                GameElement.POLLEN_DIAMETER_METRES, 240, 200, 30);

        FieldPhysics oneSecond = world(dropped);
        FieldPhysics fiftyTicks = world(dropped);
        FieldPhysics fiveChunks = world(dropped);

        oneSecond.advance(1.0);
        for (int tick = 0; tick < 50; tick++) {
            fiftyTicks.advance(0.02);
        }
        for (int chunk = 0; chunk < 5; chunk++) {
            fiveChunks.advance(0.2);
        }

        BodyState reference = only(oneSecond);
        for (FieldPhysics other : Arrays.asList(fiftyTicks, fiveChunks)) {
            BodyState ball = only(other);
            // A nanometre, not zero. Bit-identical output is not something a JVM will promise:
            // Math.sin and Math.cos are allowed a ulp of latitude, and the interpreter and the JIT
            // are entitled to disagree within it, so the same arithmetic can differ in its last
            // digits depending on when compilation happened to kick in. A nanometre is seven orders
            // of magnitude below the centimetres a dropped remainder produces -- running 200
            // substeps instead of 250 leaves this ball 15 cm higher -- so the tolerance separates
            // the bug from the noise with room to spare.
            assertEquals(reference.x(), ball.x(), 1e-9, "X depended on how the time was chopped up");
            assertEquals(reference.y(), ball.y(), 1e-9, "Y depended on how the time was chopped up");
            assertEquals(reference.z(), ball.z(), 1e-9, "Z depended on how the time was chopped up");
        }
    }

    @Test
    void aBallCannotBeThrownOffTheFieldByTheFieldItself() {
        // Every ball in the practice arrangement, dropped from a height together so they bounce off
        // each other as well as off the floor: the perimeter is the thing under test.
        FieldPhysics world = world(
                new GameElement("POLLEN", new Vec3(1.7, 1.7, 0.4),
                        GameElement.POLLEN_DIAMETER_METRES, 240, 200, 30),
                new GameElement("POLLEN", new Vec3(1.72, 1.66, 0.6),
                        GameElement.POLLEN_DIAMETER_METRES, 240, 200, 30),
                new GameElement("RED NECTAR", new Vec3(-1.7, -1.7, 0.5),
                        GameElement.NECTAR_DIAMETER_METRES, 200, 30, 40));

        run(world, 5.0);

        double half = FieldConfig.standard().halfExtentMetres();
        for (BodyState ball : world.bodies()) {
            assertTrue(Math.abs(ball.x()) < half, ball + " ended up outside the field along X");
            assertTrue(Math.abs(ball.y()) < half, ball + " ended up outside the field along Y");
            assertTrue(ball.z() > 0.0, ball + " fell through the floor");
        }
    }

    @Test
    void aFieldWithoutOde4jKeepsItsBallsWhereTheArrangementPutThem() {
        // The licence escape hatch, exercised directly rather than by deleting a jar: a team that
        // wants no LGPL in the build at all gets a field whose balls do not move, which is what
        // this simulator did before physics existed and is still enough to test vision against.
        List<GameElement> arrangement = Collections.singletonList(GameElement.pollen(0.4, 0.4));
        FieldPhysics still = new StillFieldPhysics(arrangement, FieldConfig.standard(), null);

        still.advance(10.0);

        BodyState ball = still.bodies().get(0);
        assertEquals(0.4, ball.x(), 0.0);
        assertEquals(0.4, ball.y(), 0.0);
        assertFalse(still.moving(), "nothing in this world can move, so nothing may claim to");
    }

}
