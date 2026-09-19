package org.ngicollective.testframework.sim;

/**
 * Wheel speeds in, a field pose out.
 *
 * <p>The seam between the drivetrain's devices and whatever decides where the robot ends up.
 * {@link DriveModel} keeps the part that is about hardware &mdash; resolving the four drive motors,
 * reading their physical shaft speeds, publishing the chassis heading to the IMU &mdash; and hands
 * the resulting wheel surface speeds here. Everything about how those speeds become motion lives
 * behind this interface.</p>
 *
 * <h2>Why this is an interface</h2>
 *
 * <p>Two implementations, for the same licence reason {@link
 * org.ngicollective.testframework.physics.FieldPhysics} is an interface. {@code KinematicChassis}
 * integrates the mecanum kinematics directly and stops the footprint at the perimeter with
 * arithmetic; it needs nothing but this module. The rigid-body chassis is a body in the ode4j
 * world, and ode4j is LGPL-2.1, so it cannot be named from this package at all. A team that drops
 * the jar keeps a robot that drives.</p>
 *
 * <p>Declared in {@code sim} rather than beside the solver so the dependency stays one-way:
 * {@code physics} already reads {@code sim}, and pointing {@link DriveModel} at a type in
 * {@code physics} would make the two packages mutually dependent for no gain.</p>
 *
 * <h2>The wheel order is a contract</h2>
 *
 * <p>Every speed is the speed that wheel's contact patch is laying down, in metres per second,
 * <em>positive forwards for every wheel on the robot</em>. The mounting mirror and the OpMode's
 * {@code Direction} have both already been applied by the time a number arrives here, which is
 * what lets an implementation treat all four wheels alike. Feeding these in the wrong order
 * produces a robot that drives sideways when asked to go forwards, and nothing downstream can
 * detect it, so the order is fixed: front left, front right, back left, back right.</p>
 */
public interface Chassis {

    /**
     * Tells the chassis how fast each wheel is turning right now.
     *
     * <p>Separate from {@link #advance} because a rigid-body chassis recomputes the force each
     * wheel is applying many times per control cycle &mdash; the force depends on how fast the
     * robot is already moving, which changes inside the step &mdash; while the wheels themselves
     * only change speed once per tick. One call per tick, many uses of it.</p>
     */
    void setWheelSpeeds(double frontLeft, double frontRight, double backLeft, double backRight);

    /**
     * Does this chassis's part of a step of {@code seconds}.
     *
     * <p>Deliberately not called "advance", because only one of the two implementations finishes
     * the job here. The kinematic chassis integrates its wheels and is done. The rigid-body one
     * applies the forces its wheels are currently making and lets the solver &mdash; which is
     * stepping the balls in the same world, off the same clock &mdash; do the moving. Two
     * accumulators over one world would drift apart, and a robot whose physics depended on which
     * of them was read is not one anybody can tune against.</p>
     *
     * <p>The rigid-body chassis ignores {@code seconds}: a contact force does not depend on how
     * long it is about to be applied for.</p>
     */
    void step(double seconds);

    /** Where the robot is, in metres from field centre. */
    Pose2d pose();

    /** How the chassis is moving, in its own frame. */
    ChassisVelocity velocity();

    /**
     * True while the robot is pressed against something that is eating the motion it is asking
     * for &mdash; the perimeter today, and the field's own structures once they exist.
     *
     * <p>Reported rather than inferred from a velocity of zero, because a robot pinned on a wall
     * with its wheels spinning is the interesting case and a stationary robot with no power is
     * not, and the two look identical from the outside.</p>
     */
    boolean inContact();

    /**
     * Teleports the robot and stops it dead.
     *
     * <p>A teleport is not a motion: the accumulated velocity is dropped rather than carried
     * across the jump. Placing the robot through a wall is refused rather than honoured, since a
     * robot outside the field has no legal way back.</p>
     */
    void place(Pose2d pose);

    /**
     * A chassis that goes exactly where its wheels say, stopping at the perimeter by arithmetic.
     *
     * <p>What the simulator uses when there is no rigid-body world to put a robot in &mdash;
     * because ode4j was dropped from the build. A factory rather than a public class so the
     * implementation stays package-private and this interface remains the only way to ask for
     * one, which is what keeps the choice of chassis in one place.</p>
     */
    static Chassis kinematic(RobotConfig robot, FieldConfig field) {
        return new KinematicChassis(robot, field);
    }
}
