package org.ngicollective.testframework.season;

import org.ngicollective.testframework.camera.GameElement;
import org.ngicollective.testframework.camera.Pose3d;
import org.ngicollective.testframework.camera.Solid;
import org.ngicollective.testframework.camera.Structure;
import org.ngicollective.testframework.camera.Vec3;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * The four BioBuzz FLOWERs: the POLLEN dispensers bolted to the perimeter wall.
 *
 * <p>A FLOWER is a cage, not a chute. Manual &sect;9.7: POLLEN and NECTAR go in through a 4&nbsp;in
 * opening about 21.5&nbsp;in above the tiles, POLLEN comes out of a Retrieval Opening at the
 * bottom, and it is built as three rings &mdash; "the upper and middle rings are connected with
 * four HIPS pipes, and the middle and lower rings are connected on the perimeter wall side with
 * square extrusion". So the column a ball travels down is walled by the four pipes for its whole
 * height, and then deliberately <em>not</em> walled for the last 3.5&nbsp;in, which is the window
 * a robot reaches into.</p>
 *
 * <h2>What holds the POLLEN in, given that hole</h2>
 *
 * <p>Each part of the column has its own answer, and it is worth naming because the obvious
 * simplification &mdash; running the pipes to the floor &mdash; blocks the one opening the game
 * asks a robot to use:</p>
 *
 * <ul>
 *   <li>The lowest ball sits <em>in</em> the lower ring's 2.79&nbsp;in hole, so the ring holds it
 *       laterally all the way round even though the cage above it is open on three sides. That is
 *       what "an hole for POLLEN to sit in" means.</li>
 *   <li>The next ball up is level with the middle ring, whose bore holds it.</li>
 *   <li>Everything above that is inside the four pipes. See {@link #SIDE_GAP_METRES}.</li>
 * </ul>
 *
 * <h2>Where the numbers come from</h2>
 *
 * <p>The opening, the 2.79&nbsp;in hole and the Retrieval Opening's 3.55&nbsp;in are the manual's
 * (&sect;9.7, p.72). Every height and the pipe diameter are measured from FIRST's field CAD, whose
 * parts are {@code am-5857/5858/5859 Flower Layer C/B/X} for the three rings and
 * {@code am-5862 Flower HIPS Pipe} for the pipes. Heights are converted to this project's field
 * frame the same way {@link BioBuzzField} converts the tag plates.</p>
 *
 * <p>Three cross-checks came out of that, and none of them is a number this file sets:</p>
 *
 * <ul>
 *   <li>The upper ring's top face lands at 21.41&nbsp;in, against the manual's "approximately
 *       21.5&nbsp;in" for the opening.</li>
 *   <li>The gap between the lower ring's top and the middle ring's bottom comes out at
 *       3.49&nbsp;in, against the manual's 3.55&nbsp;in Retrieval Opening.</li>
 *   <li>The pipes span exactly the upper and middle rings, and the square extrusions exactly the
 *       middle and lower ones, which is what the manual says each connects.</li>
 * </ul>
 *
 * <p>The four positions are also the CAD's. There is one FLOWER on each of the four walls, and the
 * set maps onto itself under a quarter turn about field centre &mdash; the cross-check that the
 * conversion is right, because a sign error puts two of them on the same wall.</p>
 *
 * <h2>What is deliberately missing</h2>
 *
 * <ul>
 *   <li><b>The backstop.</b> The CAD has it: a near-square plate 3.86 by 3.75&nbsp;in and
 *       0.42&nbsp;in thick, lying horizontally 22.45&nbsp;in up and 0.76&nbsp;in toward the wall,
 *       which is the manual's guide for shots that arrive high. Its outline overhangs the bore's
 *       own axis, so modelling it from that footprint would <em>roof</em> the opening a shot has to
 *       get through &mdash; and a FLOWER that cannot be loaded from above is a far worse error than
 *       a FLOWER with no guide. Leaving it out makes one slightly harder to hit than the real
 *       thing, which is the direction an unknown should err in.</li>
 *   <li><b>The two square extrusions</b> below the middle ring. They stand on the perimeter-wall
 *       side, and the perimeter wall itself is already collision geometry over that whole height,
 *       so they would stop nothing that is not stopped already.</li>
 *   <li><b>The peanut outline.</b> The real rings are an oval 5.88 by 4.92&nbsp;in, and the four
 *       pipes stand 2.2 to 2.7&nbsp;in out from the axis rather than on one circle. The circle of
 *       2&nbsp;in used here is the manual's opening and the tighter reading: it cannot let a ball
 *       leak out where the real cage would hold it.</li>
 * </ul>
 */
public final class BioBuzzFlowers {

    private static final double INCH = 0.0254;

    /** Manual: the opening on top is about 4 in across. */
    private static final double MOUTH_RADIUS = 2.0 * INCH;

    /**
     * CAD: the upper ring's centre, and how thick it is.
     *
     * <p>Its top face is therefore 21.41&nbsp;in up, which is the manual's "approximately
     * 21.5&nbsp;in" for the opening and the height a shooter has to clear.</p>
     */
    private static final double MOUTH_HEIGHT = 20.81 * INCH;
    private static final double MOUTH_THICKNESS = 1.19 * INCH;

    /**
     * CAD: the middle ring, which the four pipes stand on.
     *
     * <p>It shares the upper ring's bore, because a ball has to fall through it. Its underside at
     * 3.89&nbsp;in is the top of the Retrieval Opening.</p>
     */
    private static final double MIDDLE_HEIGHT = 4.59 * INCH;
    private static final double MIDDLE_THICKNESS = 1.40 * INCH;

    /** Manual: the lower ring sits on the tiles, is 0.4 in tall and has a 2.79 in hole. */
    private static final double BASE_RADIUS = 2.79 / 2.0 * INCH;
    private static final double BASE_HEIGHT = 0.4 * INCH;

    /** CAD: four 0.53 in pipes, from the middle ring up to the upper one. */
    private static final int UPRIGHTS = 4;
    private static final double UPRIGHT_RADIUS = 0.53 / 2.0 * INCH;
    private static final double UPRIGHT_BOTTOM = 4.25 * INCH;
    private static final double UPRIGHT_TOP = 21.25 * INCH;

    /** Boxes around one ring's circumference, and how far each reaches outward. */
    private static final int RIM_SEGMENTS = 12;
    private static final double RIM_DEPTH = 0.25 * INCH;

    /**
     * The clear gap between two uprights, which is what keeps the stacked POLLEN inside the cage.
     *
     * <p>Four uprights on a 2&nbsp;in radius put adjacent centres
     * {@code 2 * 2 * sin(45°) = 2.83 in} apart, so the gap between their surfaces is
     * {@code 2.83 - 0.53 = 2.30 in}. A POLLEN is 2.8&nbsp;in across and a NECTAR 3.6, so neither
     * fits through. Get this wrong &mdash; thinner pipes, a wider ring, three uprights instead of
     * four &mdash; and the staged POLLEN quietly roll out onto the tiles at the start of every
     * session, which reads as a physics bug rather than as a geometry one.</p>
     */
    public static final double SIDE_GAP_METRES =
            2.0 * MOUTH_RADIUS * Math.sin(Math.PI / UPRIGHTS) - 2.0 * UPRIGHT_RADIUS;

    /**
     * The colours the CAD gives these parts, which are not one grey.
     *
     * <p>An earlier version of this file used a neutral on the grounds that a saturated FLOWER
     * would hand {@code ColorBlobLocatorProcessor} a blob the size of the ball it is hunting for.
     * That was the wrong call twice over: the field CAD does carry per-part colours, so the guess
     * was unnecessary, and a green pipe at intake height is not a simulation artefact to design
     * away &mdash; it is what the camera will see at a competition. An OpMode that cannot tell a
     * green pipe from a yellow POLLEN should fail here first.</p>
     */
    private static final int MOUTH_RED = 255;
    private static final int MOUTH_GREEN = 186;
    private static final int MOUTH_BLUE = 82;
    private static final int RING_RED = 48;
    private static final int RING_GREEN = 48;
    private static final int RING_BLUE = 48;
    private static final int PIPE_RED = 95;
    private static final int PIPE_GREEN = 167;
    private static final int PIPE_BLUE = 61;

    /** Named for the wall each one is bolted to: red is at -X, blue at +X, the audience at -Y. */
    public static final String RED_WALL = "FLOWER RED";
    public static final String BLUE_WALL = "FLOWER BLUE";
    public static final String AUDIENCE_WALL = "FLOWER AUDIENCE";
    public static final String SCORING_WALL = "FLOWER SCORING";

    private BioBuzzFlowers() {
    }

    /**
     * All four, one per wall.
     *
     * <p>Named after the wall rather than numbered, because "the far one" stops meaning anything
     * the moment somebody switches alliance, and a scenario that stages POLLEN wants to say which
     * FLOWER it means in a way that survives being read next season.</p>
     */
    public static List<Structure> all() {
        // CAD (x, z) inches -> our (x, -z). Facing is the way the retrieval opening looks, which
        // is toward field centre. The four are a quarter turn apart: (x, y) -> (-y, x) maps each
        // onto the next.
        return Collections.unmodifiableList(Arrays.asList(
                flower(AUDIENCE_WALL, 23.39, -68.04, 90.0),
                flower(BLUE_WALL, 68.04, 23.39, 180.0),
                flower(SCORING_WALL, -23.39, 68.04, -90.0),
                flower(RED_WALL, -68.04, -23.39, 0.0)));
    }

    /**
     * One FLOWER, standing on the tiles at {@code (xInches, yInches)} and opening toward field
     * centre.
     *
     * <p>{@code facingDegrees} turns the cage so the gap between two uprights faces the field: the
     * pipes sit at 45, 135, 225 and 315 degrees to it. The manual puts the square extrusion on the
     * perimeter-wall side and the Retrieval Opening on the field side, so an upright straight
     * across the front would be modelling the one part that has to be open.</p>
     */
    private static Structure flower(String name, double xInches, double yInches,
                                    double facingDegrees) {
        double x = xInches * INCH;
        double y = yInches * INCH;

        List<Solid> drawn = new ArrayList<>();
        List<Solid> collided = new ArrayList<>();

        // The upper ring first, because it is the one a shot is aimed at and callers read it back
        // as the FLOWER's mouth. One ring drawn, a dozen little boxes collided with: a solid
        // cylinder here would plug the opening.
        Pose3d mouth = flat(x, y, MOUTH_HEIGHT, facingDegrees);
        drawn.add(Solid.cylinder(mouth, MOUTH_RADIUS, MOUTH_THICKNESS,
                MOUTH_RED, MOUTH_GREEN, MOUTH_BLUE));
        collided.addAll(rimOf(mouth, MOUTH_RADIUS, MOUTH_THICKNESS));

        // The middle ring, which the pipes stand on and which catches the second ball down.
        Pose3d middle = flat(x, y, MIDDLE_HEIGHT, facingDegrees);
        drawn.add(Solid.cylinder(middle, MOUTH_RADIUS, MIDDLE_THICKNESS,
                RING_RED, RING_GREEN, RING_BLUE));
        collided.addAll(rimOf(middle, MOUTH_RADIUS, MIDDLE_THICKNESS));

        // The lower ring, whose narrower hole a POLLEN settles into rather than passes through.
        Pose3d base = flat(x, y, BASE_HEIGHT / 2.0, facingDegrees);
        drawn.add(Solid.cylinder(base, BASE_RADIUS, BASE_HEIGHT,
                RING_RED, RING_GREEN, RING_BLUE));
        collided.addAll(rimOf(base, BASE_RADIUS, BASE_HEIGHT));

        // Four pipes, middle ring to upper ring. Cylinders on both sides: these only ever meet
        // balls and the robot, and ode4j has colliders for cylinder-versus-sphere and
        // cylinder-versus-box. It has none for two cylinders, which never arises -- two uprights
        // are both static, and the world does not build contacts between two things it cannot
        // move.
        double uprightLength = UPRIGHT_TOP - UPRIGHT_BOTTOM;
        for (int upright = 0; upright < UPRIGHTS; upright++) {
            double around = Math.toRadians(facingDegrees + 45.0 + upright * 360.0 / UPRIGHTS);
            Solid pipe = Solid.cylinder(
                    flat(x + MOUTH_RADIUS * Math.cos(around),
                            y + MOUTH_RADIUS * Math.sin(around),
                            UPRIGHT_BOTTOM + uprightLength / 2.0,
                            facingDegrees),
                    UPRIGHT_RADIUS, uprightLength, PIPE_RED, PIPE_GREEN, PIPE_BLUE);
            drawn.add(pipe);
            collided.add(pipe);
        }

        return new Structure(name, drawn, collided);
    }

    /**
     * A pose lying flat on the field, so that a cylinder built on it stands upright.
     *
     * <p>A {@link Solid}'s cylinder runs along its local +Z, which is {@link Pose3d#up()}, and at
     * zero pitch and roll that is field +Z whatever the yaw. Pitching by 90 degrees to "point the
     * thing upward" is the mistake worth naming: it aims {@link Pose3d#forward()} at the ceiling
     * and leaves {@code up()} horizontal, which lays every ring on its side.</p>
     */
    private static Pose3d flat(double x, double y, double height, double facingDegrees) {
        return Pose3d.ofDegrees(new Vec3(x, y, height), facingDegrees, 0.0, 0.0);
    }

    /**
     * A ring as the boxes a solver can push against.
     *
     * <p>Twelve segments of a four-inch ring are 1.05&nbsp;in chords, so a ball meeting the rim
     * meets something the same order of size as itself rather than a facet it could straddle. Each
     * box sits <em>outside</em> the bore and reaches a quarter inch further out, so the hole a
     * solver sees is the hole that is drawn: centring them on the bore instead narrows it by the
     * wall thickness, which is a fifth of a NECTAR's clearance through the opening.</p>
     *
     * <p>A quarter inch of wall is also enough that nothing can pass through one. What has to cross
     * a static box in one 4&nbsp;ms step is not the ball's centre but the whole ball, so the window
     * is the wall plus a diameter &mdash; over 3&nbsp;in here, which is 19&nbsp;m/s.</p>
     */
    private static List<Solid> rimOf(Pose3d ring, double bore, double thickness) {
        List<Solid> segments = new ArrayList<>(RIM_SEGMENTS);
        double radius = bore + RIM_DEPTH / 2.0;
        double chord = 2.0 * radius * Math.sin(Math.PI / RIM_SEGMENTS);
        for (int segment = 0; segment < RIM_SEGMENTS; segment++) {
            double around = 2.0 * Math.PI * segment / RIM_SEGMENTS;
            Vec3 at = ring.position()
                    .plus(ring.forward().scaled(radius * Math.cos(around)))
                    .plus(ring.left().scaled(radius * Math.sin(around)));
            // Turned to lie along its own chord: local +X points radially outward and +Y runs
            // around the circle, so RIM_DEPTH is a wall thickness rather than a spoke sticking
            // into the bore.
            double outward = Math.toDegrees(ring.yaw() + around);
            segments.add(Solid.box(flat(at.x(), at.y(), at.z(), outward),
                    RIM_DEPTH, chord, thickness, RING_RED, RING_GREEN, RING_BLUE));
        }
        return segments;
    }

    /**
     * Four POLLEN stacked up one FLOWER's bore, which is how a match starts.
     *
     * <p>Placed at resting heights rather than dropped in: the manual stages 4 POLLEN in each
     * FLOWER, and a scenario that wants a loaded field should be able to ask for that by name
     * instead of restating four sets of coordinates and getting the stacking wrong.</p>
     */
    public static List<GameElement> pollenIn(String flowerName) {
        for (Structure flower : all()) {
            if (flower.name().equals(flowerName)) {
                return stack(flower);
            }
        }
        throw new IllegalArgumentException("no FLOWER named \"" + flowerName + "\"; there are "
                + Arrays.asList(RED_WALL, BLUE_WALL, AUDIENCE_WALL, SCORING_WALL));
    }

    /**
     * Four balls up the axis of a FLOWER, each resting on the one below.
     *
     * <p>The axis is read back off the structure's own mouth rather than recomputed, so a staged
     * ball cannot end up beside a FLOWER somebody has moved. The lowest starts on top of the lower
     * ring and settles the last half inch into its hole once the solver runs, which is a cheaper
     * thing to be right about than the seated height of a 2.795&nbsp;in ball in a 2.79&nbsp;in
     * hole.</p>
     */
    private static List<GameElement> stack(Structure flower) {
        Vec3 mouth = flower.drawn().get(0).pose().position();
        double diameter = GameElement.POLLEN_DIAMETER_METRES;

        List<GameElement> stacked = new ArrayList<>(4);
        for (int ball = 0; ball < 4; ball++) {
            stacked.add(GameElement.pollenAt(mouth.x(), mouth.y(),
                    BASE_HEIGHT + diameter * (0.5 + ball)));
        }
        return stacked;
    }
}
