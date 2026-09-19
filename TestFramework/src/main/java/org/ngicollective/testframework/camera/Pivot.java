package org.ngicollective.testframework.camera;

/**
 * The axis a {@link Structure} turns on, and what it takes to turn it.
 *
 * <p>There is exactly one of these on a BioBuzz field per HIVE. Manual &sect;9.6: "Each HIVE is on
 * a pivot and can tip so that one of the CELLS is facing upwards at any given time. Each HIVE is
 * bi-stable and will hold its position until enough POLLEN or NECTAR are LAUNCHED into the
 * upwards-facing CELL."</p>
 *
 * <h2>Geometry and dynamics in one place</h2>
 *
 * <p>Both, deliberately. The alternative is a table of masses and torques somewhere in the solver,
 * keyed by structure name, which can disagree with the geometry and whose disagreement is
 * invisible: the HIVE would simply be harder or easier to tip than the thing it is drawn as. The
 * solver reads all of this, the drawer reads the first half, and neither can be handed a pivot
 * that only half exists.</p>
 *
 * <h2>The angle is absolute, and the structure is drawn at one</h2>
 *
 * <p>{@link #angleRadians()} says where the structure's own solids already are, so a structure
 * built in its raised state and one built in its lowered state are the same pivot at two angles
 * rather than two descriptions. {@link #lowRadians()} and {@link #highRadians()} are the two
 * stable states, which are the hard stops: nothing intermediate is stable, and the manual's
 * "damper ... begins to contact the frame" is what reaching one of them means.</p>
 *
 * <h2>Why a hold torque and not a centre of mass</h2>
 *
 * <p>An over-centre mechanism is normally bi-stable because its mass hangs off the axis, and the
 * HIVE's CELLs measurably do &mdash; 5.53&nbsp;in off it, from the CAD. Modelling that literally
 * makes the HIVE impossible to tip: the well is {@code m g d (1 - cos 30°)}, which for anything
 * that weighs what two sheet-metal baskets weigh is several joules, and a 22&nbsp;g POLLEN arrives
 * with a third of one. A real HIVE is therefore counterweighted about its pivot and held by the
 * dampers the manual names, neither of which is published anywhere.</p>
 *
 * <p>So what is modelled is the <em>effective</em> pivot: gravity carried by the axis, and one
 * explicit restoring torque, {@code hold * sin(lean) / sin(half the travel)}, which is the shape a
 * real over-centre mechanism has &mdash; strongest at a stop, zero at the midpoint, reversed past
 * it. {@link #holdNewtonMetres()} is the value at a stop, and it is measurable on a real field in
 * about a minute: put POLLEN into a raised CELL one at a time and count what it takes to go. Every
 * observable consequence &mdash; how many elements tip it, how long the swing lasts, whether one
 * hard shot can do it &mdash; is derived from these numbers rather than stated beside them.</p>
 */
public final class Pivot {

    private final Vec3 point;
    private final Vec3 axis;
    private final double angleRadians;
    private final double lowRadians;
    private final double highRadians;
    private final double holdNewtonMetres;
    private final double massKilograms;
    private final double inertiaKilogramMetresSquared;
    private final double dampingNewtonMetreSecondsPerRadian;

    private Pivot(Vec3 point, Vec3 axis, double angleRadians, double lowRadians,
                  double highRadians, double holdNewtonMetres, double massKilograms,
                  double inertiaKilogramMetresSquared,
                  double dampingNewtonMetreSecondsPerRadian) {
        if (lowRadians >= highRadians) {
            throw new IllegalArgumentException("a pivot's two stable states must differ, with the"
                    + " low one first; got " + lowRadians + " and " + highRadians + " (radians)");
        }
        if (angleRadians < lowRadians || angleRadians > highRadians) {
            throw new IllegalArgumentException("a pivot cannot be built outside its own travel;"
                    + " got " + angleRadians + " outside [" + lowRadians + ", " + highRadians
                    + "] (radians)");
        }
        if (massKilograms <= 0.0 || inertiaKilogramMetresSquared <= 0.0) {
            throw new IllegalArgumentException("a pivot needs a positive mass and inertia to be"
                    + " swung; got " + massKilograms + " kg and "
                    + inertiaKilogramMetresSquared + " kg m^2");
        }
        this.point = point;
        this.axis = axis;
        this.angleRadians = angleRadians;
        this.lowRadians = lowRadians;
        this.highRadians = highRadians;
        this.holdNewtonMetres = holdNewtonMetres;
        this.massKilograms = massKilograms;
        this.inertiaKilogramMetresSquared = inertiaKilogramMetresSquared;
        this.dampingNewtonMetreSecondsPerRadian = dampingNewtonMetreSecondsPerRadian;
    }

    /**
     * A pivot, spelled out.
     *
     * <p>Nine numbers and one call site, which is the trade made rather than a fluent builder for
     * a thing the field has two of.</p>
     *
     * @param point any point on the axis, in the field frame
     * @param axis the axis's direction in the field frame; need not be a unit vector
     * @param angleRadians the angle the structure's solids are already drawn at
     * @param lowRadians the lesser of the two stable states
     * @param highRadians the greater
     * @param holdNewtonMetres the restoring torque at a stop: what has to be beaten to tip it
     * @param massKilograms the swinging mass, whose weight the axis carries
     * @param inertiaKilogramMetresSquared its moment of inertia about this axis
     * @param dampingNewtonMetreSecondsPerRadian the damper, which is what stops a tip slamming
     */
    public static Pivot of(Vec3 point, Vec3 axis, double angleRadians, double lowRadians,
                           double highRadians, double holdNewtonMetres, double massKilograms,
                           double inertiaKilogramMetresSquared,
                           double dampingNewtonMetreSecondsPerRadian) {
        return new Pivot(point, axis, angleRadians, lowRadians, highRadians, holdNewtonMetres,
                massKilograms, inertiaKilogramMetresSquared, dampingNewtonMetreSecondsPerRadian);
    }

    public Vec3 point() {
        return point;
    }

    public Vec3 axis() {
        return axis;
    }

    /** Where the structure this belongs to is drawn: see {@link Structure#at}. */
    public double angleRadians() {
        return angleRadians;
    }

    public double lowRadians() {
        return lowRadians;
    }

    public double highRadians() {
        return highRadians;
    }

    /** The angle halfway between the two stable states: the one place nothing holds. */
    public double middleRadians() {
        return (lowRadians + highRadians) / 2.0;
    }

    public double holdNewtonMetres() {
        return holdNewtonMetres;
    }

    public double massKilograms() {
        return massKilograms;
    }

    public double inertiaKilogramMetresSquared() {
        return inertiaKilogramMetresSquared;
    }

    public double dampingNewtonMetreSecondsPerRadian() {
        return dampingNewtonMetreSecondsPerRadian;
    }

    /**
     * The restoring torque at {@code angle}: negative, towards {@link #lowRadians()}, below the
     * middle and positive above it.
     *
     * <p>{@link #holdNewtonMetres()} at either stop, zero at the middle, and a sine in between.
     * That is a pendulum's own law, {@code m g d sin(lean)}, with the product of mass and
     * overhang replaced by a number somebody can measure with a handful of POLLEN. Past the middle
     * it has changed sign, so a HIVE nudged over the top is pulled the rest of the way rather than
     * easing to a halt: that is what bi-stable means, and it is why a tip is all-or-nothing
     * rather than a HIVE left leaning.</p>
     */
    public double holdAt(double angleRadians) {
        double half = (highRadians - lowRadians) / 2.0;
        return holdNewtonMetres * Math.sin(angleRadians - middleRadians()) / Math.sin(half);
    }

    /** The same pivot, with the structure on it drawn at a different angle. */
    public Pivot at(double angleRadians) {
        return new Pivot(point, axis, angleRadians, lowRadians, highRadians, holdNewtonMetres,
                massKilograms, inertiaKilogramMetresSquared, dampingNewtonMetreSecondsPerRadian);
    }

    @Override
    public String toString() {
        return String.format("pivot about %s through %s at %.1f\u00b0 of [%.1f\u00b0, %.1f\u00b0]",
                axis, point, Math.toDegrees(angleRadians), Math.toDegrees(lowRadians),
                Math.toDegrees(highRadians));
    }
}
