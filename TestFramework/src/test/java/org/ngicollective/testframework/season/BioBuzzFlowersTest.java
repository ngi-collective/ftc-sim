package org.ngicollective.testframework.season;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.ngicollective.testframework.camera.GameElement;
import org.ngicollective.testframework.camera.Solid;
import org.ngicollective.testframework.camera.Structure;
import org.ngicollective.testframework.camera.Vec3;
import org.ngicollective.testframework.sim.FieldConfig;

import java.util.ArrayList;
import java.util.List;

/**
 * The four FLOWERs, against the manual and against the field they have to fit inside.
 *
 * <p>These assert the things a wrong conversion or a mistyped inch would break and nothing would
 * otherwise notice: which wall each one is on, how high the mouth is, and whether a ball can fall
 * out of the side of one. A FLOWER in the wrong place still renders perfectly and still collides
 * perfectly &mdash; it is just not where the real one is, and every autonomous routine tuned
 * against it is wrong in a way no test of the renderer could see.</p>
 */
class BioBuzzFlowersTest {

    private static final double INCH = 0.0254;

    @Test
    void thereIsOneFlowerOnEachWall() {
        // The CAD's four positions convert to one per wall. A sign error in ourY = -cadZ puts two
        // of them on the same wall and leaves two walls bare, which is the failure this catches.
        double half = FieldConfig.standard().halfExtentMetres();
        List<String> walls = new ArrayList<>();
        for (Structure flower : BioBuzzFlowers.all()) {
            Vec3 at = mouthOf(flower);
            walls.add(Math.abs(at.x()) > Math.abs(at.y())
                    ? (at.x() < 0 ? "-X" : "+X")
                    : (at.y() < 0 ? "-Y" : "+Y"));
            assertTrue(Math.hypot(at.x(), at.y()) < Math.hypot(half, half),
                    flower.name() + " is outside the field at " + at);
        }
        assertEquals(4, walls.size());
        assertTrue(walls.containsAll(java.util.Arrays.asList("-X", "+X", "-Y", "+Y")),
                "expected one FLOWER per wall, got " + walls);
    }

    @Test
    void theFourAreAQuarterTurnApart() {
        // The manual does not give coordinates, so the CAD is the only source and rotational
        // symmetry is the only independent check on it: a quarter turn about field centre has to
        // map the set of four onto itself.
        List<Vec3> mouths = new ArrayList<>();
        for (Structure flower : BioBuzzFlowers.all()) {
            mouths.add(mouthOf(flower));
        }

        for (Vec3 at : mouths) {
            Vec3 turned = new Vec3(-at.y(), at.x(), at.z());
            assertTrue(nearestDistance(mouths, turned) < 0.001,
                    "turning " + at + " by ninety degrees gives " + turned
                            + ", which is not where any other FLOWER is");
        }
    }

    @Test
    void theMouthIsAFourInchOpeningAtTheHeightTheManualGives() {
        Solid mouth = BioBuzzFlowers.all().get(0).drawn().get(0);

        assertEquals(Solid.Shape.CYLINDER, mouth.shape());
        assertEquals(4.0 * INCH, mouth.radiusMetres() * 2.0, 1e-9,
                "the manual says the opening is about 4 in across");

        // The manual says "approximately 21.5 in" for the opening, and what a shot has to clear is
        // the top of the upper ring rather than its centre line. That height is not set anywhere:
        // it falls out of the CAD's ring centre plus the CAD's ring thickness, so it is a real
        // check on both.
        double topFace = (mouth.pose().position().z() + mouth.lengthMetres() / 2.0) / INCH;
        assertEquals(21.5, topFace, 0.25, "the top of the opening, in inches above the tiles");
    }

    @Test
    void theRetrievalOpeningIsAGapARobotCanReachAPollenThrough() {
        // Manual 9.7: a Retrieval Opening about 3.55 in tall at the bottom, and POLLEN come *out*
        // of it. An earlier version of this file ran the four pipes from the mouth to the floor,
        // which caged the staged POLLEN perfectly and left no way to take one -- the simulator
        // would have refused a game task the real field is built for, and every other test here
        // would still have passed.
        List<Solid> rings = new ArrayList<>();
        for (Solid solid : BioBuzzFlowers.all().get(0).drawn()) {
            if (solid.shape() == Solid.Shape.CYLINDER && solid.radiusMetres() > 0.02) {
                rings.add(solid);
            }
        }

        double lowerTop = Double.MAX_VALUE;
        double middleBottom = Double.MAX_VALUE;
        for (Solid ring : rings) {
            double bottom = ring.pose().position().z() - ring.lengthMetres() / 2.0;
            double top = ring.pose().position().z() + ring.lengthMetres() / 2.0;
            if (bottom < 0.01) {
                lowerTop = top;
            } else if (bottom < middleBottom) {
                middleBottom = bottom;
            }
        }

        double window = (middleBottom - lowerTop) / INCH;
        assertEquals(3.55, window, 0.25, "the Retrieval Opening's height, in inches");
        assertTrue(window * INCH > GameElement.POLLEN_DIAMETER_METRES,
                "a POLLEN is " + GameElement.POLLEN_DIAMETER_METRES / INCH
                        + " in and the window it comes out of is " + window);
    }

    @Test
    void aRingLiesFlatRatherThanOnItsSide() {
        // A cylinder's axis is its local +Z, which is Pose3d's up(). Pitching a ring by ninety
        // degrees to "stand it up" instead aims forward() at the ceiling and leaves up()
        // horizontal, which lays every ring on its side and leaves a FLOWER looking like a
        // letterbox. Nothing downstream can tell; it just looks wrong and holds no balls.
        Solid mouth = BioBuzzFlowers.all().get(0).drawn().get(0);

        assertEquals(1.0, mouth.pose().up().z(), 1e-9,
                "a ring's axis has to be field +Z, got " + mouth.pose().up());
    }

    @Test
    void aBallCannotFallOutBetweenTwoUprights() {
        // Four uprights on a 2 in radius leave a 2.33 in gap, and the smallest ball is 2.8 in.
        // Thinner pipes or a wider ring and the staged POLLEN roll onto the tiles at the start of
        // every session, which reads as a physics bug rather than a geometry one.
        assertTrue(BioBuzzFlowers.SIDE_GAP_METRES < GameElement.POLLEN_DIAMETER_METRES,
                "the gap between uprights is " + BioBuzzFlowers.SIDE_GAP_METRES
                        + " m and a POLLEN is " + GameElement.POLLEN_DIAMETER_METRES + " m");
    }

    @Test
    void whatIsDrawnAndWhatIsCollidedWithAreDifferentGeometry() {
        // Deliberately, and this is the one place it is worth stating as an assertion: the mouth
        // is one open cylinder drawn and a ring of boxes collided with, because ode4j's cylinders
        // are solid and a solid one would plug the opening a robot shoots POLLEN through.
        Structure flower = BioBuzzFlowers.all().get(0);

        assertEquals(7, flower.drawn().size(), "three rings and four uprights");
        assertTrue(flower.collided().size() > flower.drawn().size(),
                "the rims are segmented for the solver, so there must be more colliders than "
                        + "drawings; got " + flower.collided().size());

        int solidCylinders = 0;
        for (Solid solid : flower.collided()) {
            if (solid.shape() == Solid.Shape.CYLINDER) {
                solidCylinders++;
            }
        }
        assertEquals(4, solidCylinders,
                "only the uprights may be cylinders to the solver; a ring must not be");
    }

    @Test
    void stagedPollenSitInTheFlowerTheyWereAskedFor() {
        List<GameElement> staged = BioBuzzFlowers.pollenIn(BioBuzzFlowers.RED_WALL);
        Vec3 mouth = mouthOf(flowerNamed(BioBuzzFlowers.RED_WALL));

        assertEquals(4, staged.size(), "a match stages four POLLEN in each FLOWER");
        double below = 0.0;
        for (GameElement pollen : staged) {
            assertEquals(mouth.x(), pollen.centre().x(), 1e-9);
            assertEquals(mouth.y(), pollen.centre().y(), 1e-9);
            assertTrue(pollen.centre().z() > below,
                    "the stack has to climb; " + pollen + " is not above " + below);
            assertTrue(pollen.centre().z() < mouth.z(),
                    "a staged ball has to be inside the cage, not above its mouth: " + pollen);
            below = pollen.centre().z();
        }
    }

    private static Structure flowerNamed(String name) {
        for (Structure flower : BioBuzzFlowers.all()) {
            if (flower.name().equals(name)) {
                return flower;
            }
        }
        throw new AssertionError("no FLOWER named " + name);
    }

    /** Every FLOWER draws its mouth first, which is the ring a shot has to go through. */
    private static Vec3 mouthOf(Structure flower) {
        return flower.drawn().get(0).pose().position();
    }

    private static double nearestDistance(List<Vec3> candidates, Vec3 to) {
        double nearest = Double.MAX_VALUE;
        for (Vec3 candidate : candidates) {
            nearest = Math.min(nearest,
                    Math.hypot(candidate.x() - to.x(), candidate.y() - to.y()));
        }
        return nearest;
    }
}
