package org.ngicollective.testframework.camera;

/**
 * A named box on the field that scoring elements count in: the manual's own "scoring volume".
 *
 * <p>Manual &sect;10.5.2 uses the phrase for the FLOWER's, and it is the right name for all of
 * them, because what makes one of these different from a {@link Structure} is that nothing is
 * there. It is a region, invisible, and a ball passes through it without being touched. That is
 * why it is a type of its own rather than a flag on a solid: a CELL's opening is defined by
 * exactly the space its panels do not occupy.</p>
 *
 * <h2>Oriented, not axis-aligned</h2>
 *
 * <p>A CELL's mouth sits 30&deg; off level, and a box that ignored that would either hang out of
 * the basket or exclude the corner a ball actually rests in. So this is a {@link Pose3d} and three
 * extents along that pose's own axes, and containment is three dot products &mdash; the same
 * arithmetic {@code RobotFrame} does for a volume bolted to the robot, in the field frame instead
 * of the robot's.</p>
 *
 * <h2>Containment is of the centre, not of any part of the ball</h2>
 *
 * <p>The manual's FLOWER and GARDEN rules say "at least partially within", and this deliberately
 * does not: the CELL rule (&sect;10.5.1) says only "left in an upward-facing CELL", and a ball
 * perched on the rim with a millimetre of itself over the edge is not in the basket. Scoring the
 * centre is also the rule that cannot double-count &mdash; a 2.8&nbsp;in POLLEN is nowhere near
 * big enough to have its centre in two CELLs at once, while "partially" would let one ball score
 * in a CELL it is merely leaning against. When a FLOWER volume arrives it will need the other
 * rule, and it will need it as a second method here rather than as a different kind of box.</p>
 */
public final class ScoringVolume {

    private final String name;
    private final Pose3d pose;
    private final double alongForward;
    private final double alongLeft;
    private final double alongUp;
    private final String attachedTo;

    private ScoringVolume(String name, Pose3d pose, double alongForward, double alongLeft,
                          double alongUp, String attachedTo) {
        if (!(alongForward > 0.0) || !(alongLeft > 0.0) || !(alongUp > 0.0)) {
            throw new IllegalArgumentException(name + " must enclose something; got "
                    + alongForward + " x " + alongLeft + " x " + alongUp + "m");
        }
        this.name = name;
        this.pose = pose;
        this.alongForward = alongForward;
        this.alongLeft = alongLeft;
        this.alongUp = alongUp;
        this.attachedTo = attachedTo;
    }

    /**
     * A box centred on {@code pose}, measured along that pose's own forward, left and up.
     *
     * <p>Full extents rather than half, so the numbers written at a call site are the ones the
     * manual prints: a CELL is 20 by 14 by 12 inches, and halving it is this class's business.</p>
     */
    public static ScoringVolume box(String name, Pose3d pose,
                                    double alongForward, double alongLeft, double alongUp) {
        return new ScoringVolume(name, pose, alongForward, alongLeft, alongUp, null);
    }

    /**
     * The same volume, declared to ride on a {@link Structure}'s pivot.
     *
     * <p>A CELL's opening is cut in a HIVE that tips, so the region a ball scores in is not a
     * place on the field: it swings with the basket. Saying so here, once, is what lets the scene
     * move a volume with its structure without knowing what a CELL is &mdash; and it is what
     * stops the volumes and the panels around them from being re-derived separately and drifting
     * apart, which would score balls that are visibly outside the basket.</p>
     */
    public ScoringVolume on(String structureName) {
        return new ScoringVolume(name, pose, alongForward, alongLeft, alongUp, structureName);
    }

    /** The same volume, swung to a new pose by the structure it is attached to. */
    public ScoringVolume movedTo(Pose3d newPose) {
        return new ScoringVolume(name, newPose, alongForward, alongLeft, alongUp, attachedTo);
    }

    /** The structure whose pivot carries this volume, or null for a region fixed to the field. */
    public String attachedTo() {
        return attachedTo;
    }

    /** Whether {@code point} is inside this box. */
    public boolean contains(Vec3 point) {
        Vec3 offset = point.minus(pose.position());
        return Math.abs(offset.dot(pose.forward())) <= alongForward / 2.0
                && Math.abs(offset.dot(pose.left())) <= alongLeft / 2.0
                && Math.abs(offset.dot(pose.up())) <= alongUp / 2.0;
    }

    /**
     * Which volume this is &mdash; a CELL's name, for something that can score in one place.
     *
     * <p>Named because a score has to say where the points came from. A driver told "6 points"
     * cannot tell a CELL full of NECTAR from a scoring error, and a test asserting a total would
     * pass just as happily with the balls in the wrong basket.</p>
     */
    public String name() {
        return name;
    }

    /** Where the box is and how it is turned: its axes are the ones the extents are measured on. */
    public Pose3d pose() {
        return pose;
    }

    public double alongForwardMetres() {
        return alongForward;
    }

    public double alongLeftMetres() {
        return alongLeft;
    }

    public double alongUpMetres() {
        return alongUp;
    }

    @Override
    public String toString() {
        return String.format("%s: %.3f x %.3f x %.3fm at %s",
                name, alongForward, alongLeft, alongUp, pose);
    }
}
