package org.ngicollective.testframework.physics;

import org.ngicollective.testframework.sim.Chassis;
import org.ngicollective.testframework.sim.ChassisConfig;
import org.ngicollective.testframework.sim.ChassisVelocity;
import org.ngicollective.testframework.sim.DrivetrainConfig;
import org.ngicollective.testframework.sim.FieldConfig;
import org.ngicollective.testframework.sim.Footprint;
import org.ngicollective.testframework.sim.Pose2d;
import org.ngicollective.testframework.sim.RobotConfig;
import org.ode4j.math.DMatrix3C;
import org.ode4j.math.DQuaternion;
import org.ode4j.math.DVector3C;
import org.ode4j.ode.DBody;
import org.ode4j.ode.DBox;
import org.ode4j.ode.DMass;
import org.ode4j.ode.DSpace;
import org.ode4j.ode.DWorld;
import org.ode4j.ode.OdeHelper;

/**
 * The robot as a rigid body: a box with mass, pushed around by what its wheels can grip.
 *
 * <p>Nothing carries this robot anywhere. It is accelerated by four contact forces and stopped by
 * whatever it runs into, so a ball slows it, a wall stops it and a corner spins it. That is the
 * whole difference from {@code KinematicChassis}, and it is what makes field structures worth
 * building: geometry a robot can drive straight through is a lie the driver can see.</p>
 *
 * <h2>Flat on the floor, and upright</h2>
 *
 * <p>A {@code plane2D} joint pins the body to {@code z = 0} and allows rotation about {@code +Z}
 * alone, which is three of the six degrees of freedom removed. An FTC robot on foam tiles does not
 * meaningfully pitch, roll or leave the ground, and simulating those would open questions &mdash;
 * when does it tip over, what does its centre of mass sit at &mdash; that nothing here is
 * calibrated to answer. The joint constrains {@code vz = wx = wy = 0} and pulls {@code z} back to
 * zero if it drifts, which is exactly the body origin the drive model already publishes: on the
 * floor, at the centre of the footprint.</p>
 *
 * <p>One consequence is worth knowing: the chassis is immovable in {@code z}, so its box never
 * rides up over anything. A ball can only be shoved aside, never run over.</p>
 *
 * <h2>Why the wheels are a slip model and not joints</h2>
 *
 * <p>A mecanum wheel's rollers sit at 45 degrees, so its contact patch can push along one
 * direction and rolls freely along the other. Modelling that as hinged wheel bodies with roller
 * geometry would be dozens of joints solved every substep to reproduce an algebraic relation that
 * is already known exactly &mdash; and the SDK gives us shaft speeds, not torques, so the wheels
 * would have to be velocity-driven joints anyway.</p>
 *
 * <p>So each wheel contributes a force at its contact patch, proportional to how fast that patch
 * is slipping, capped by what friction can hold. Below the cap the robot tracks its wheels almost
 * exactly; at the cap it spins its wheels and accelerates no harder, which is the behaviour that
 * matters &mdash; it is why a robot cannot accelerate as fast as its free speed suggests.</p>
 *
 * <p>The motors stay velocity sources: a wheel that cannot move the robot keeps turning and keeps
 * counting encoder ticks. That is deliberate, and it is the failure this simulator exists to show
 * &mdash; dead reckoning drifting away from the truth while the numbers look healthy. A
 * torque&ndash;speed curve that let a stalled wheel slow down would hide it.</p>
 */
final class OdeChassis implements Chassis {

    /**
     * How hard a wheel pulls towards its commanded speed, in newtons per metre/second of slip.
     *
     * <p>A solver constant rather than a robot measurement, which is why it is here and not in
     * {@code robot-config}: it sets how quickly slip is taken up, and the physical answer &mdash;
     * how much force a wheel can apply at all &mdash; is the friction cap below it. Stiff enough
     * that the robot tracks its wheels closely (the contact settles with a time constant of about
     * 23&nbsp;ms at Verity's mass), soft enough to be stable: the explicit force is stable while
     * {@code 4k·h/m} stays well under 2, which at a 4&nbsp;ms step and 14&nbsp;kg means anything
     * under about 1750. This is 300.</p>
     */
    private static final double SLIP_STIFFNESS_NEWTONS_PER_METRE_PER_SECOND = 300.0;

    /** Standard gravity as a magnitude, for the weight each wheel carries. */
    private static final double GRAVITY_METRES_PER_SECOND_SQUARED = 9.806;

    private static final double ROOT_HALF = Math.sqrt(0.5);

    /** Wheel order throughout: front left, front right, back left, back right. */
    private static final int WHEELS = 4;

    /** Which end of the wheel base each wheel sits at: the nose is {@code +X}. */
    private static final double[] FORWARD_SIGN = {1.0, 1.0, -1.0, -1.0};

    /** Which side of the track each wheel sits on: left is {@code +Y}. */
    private static final double[] LEFT_SIGN = {1.0, -1.0, 1.0, -1.0};

    /**
     * The sign of each wheel's drive direction across the robot.
     *
     * <p>A mecanum wheel pushes along {@code (1, LATERAL_SIGN)/}&radic;2 in the robot frame, the
     * 45 degree normal to its rollers. Dotting that direction with its contact patch's velocity
     * reproduces {@code KinematicChassis}'s forward kinematics term for term &mdash; the same
     * {@code forward ± lateral ± yaw·lever} &mdash; which is the check that these signs are the
     * ones the rest of the framework already agrees on.</p>
     */
    private static final double[] LATERAL_SIGN = {-1.0, 1.0, 1.0, -1.0};

    private final DBody body;
    private final ChassisConfig chassisConfig;
    private final FieldConfig field;

    /** Half the wheel base and half the track: where the contact patches are. */
    private final double halfWheelBase;
    private final double halfTrack;

    private final double strafeEfficiency;

    /** What one wheel can push with before it slips: its share of the weight, times grip. */
    private final double tractionLimitNewtons;

    private final double[] wheelSpeeds = new double[WHEELS];

    /** Set while the solver reports the chassis touching something it cannot move. */
    private boolean contact;

    /**
     * Called after a teleport, so the world can put right anything the robot was dropped on top
     * of. Set by the world that owns this chassis.
     */
    private Runnable placed = new Runnable() {
        @Override
        public void run() {
        }
    };

    /** Reused across steps so a driving robot allocates nothing per substep. */
    private final DQuaternion heading = new DQuaternion();

    OdeChassis(DWorld world, DSpace space, RobotConfig robot, FieldConfig field) {
        this.chassisConfig = robot.chassis();
        this.field = field;

        DrivetrainConfig drivetrain = robot.drivetrain();
        this.halfWheelBase = drivetrain.wheelBaseMetres() / 2.0;
        this.halfTrack = drivetrain.trackWidthMetres() / 2.0;
        this.strafeEfficiency = drivetrain.strafeEfficiency();
        this.tractionLimitNewtons = drivetrain.gripCoefficient()
                * chassisConfig.massKilograms() * GRAVITY_METRES_PER_SECOND_SQUARED / WHEELS;

        this.body = OdeHelper.createBody(world);

        // Inertia from the box itself rather than from a configured number, so it can never
        // contradict the dimensions beside it. With pitch and roll constrained away, only the yaw
        // term is ever used: m(length^2 + width^2)/12.
        DMass mass = OdeHelper.createMass();
        mass.setBoxTotal(chassisConfig.massKilograms(), chassisConfig.lengthMetres(),
                chassisConfig.widthMetres(), chassisConfig.deckHeightMetres());
        body.setMass(mass);

        // The footprint the drive model stops against the walls with, reaching from the floor to
        // the deck: a mecanum robot's wheels and frame rails do reach the floor, so balls are
        // shoved rather than swallowed.
        DBox box = OdeHelper.createBox(space, chassisConfig.lengthMetres(),
                chassisConfig.widthMetres(), chassisConfig.deckHeightMetres());
        box.setBody(body);
        // The geom sits half the box above the body's origin, so the body's origin is the pose the
        // drive model publishes: on the floor, at the centre of the footprint.
        box.setOffsetPosition(0.0, 0.0, chassisConfig.deckHeightMetres() / 2.0);

        OdeHelper.createPlane2DJoint(world).attach(body, null);
    }

    /** The geom's body, so the world can tell the robot apart from everything else in it. */
    DBody body() {
        return body;
    }

    /** What to run after a teleport, so balls the robot landed on can be moved out of it. */
    void onPlaced(Runnable listener) {
        this.placed = listener;
    }

    /** Told by the world when the chassis met something static; cleared once per tick. */
    void noteContact() {
        contact = true;
    }

    void clearContact() {
        contact = false;
    }

    @Override
    public void setWheelSpeeds(double frontLeft, double frontRight,
                               double backLeft, double backRight) {
        wheelSpeeds[0] = frontLeft;
        wheelSpeeds[1] = frontRight;
        wheelSpeeds[2] = backLeft;
        wheelSpeeds[3] = backRight;
    }

    /**
     * Applies what each wheel can grip with, given how fast its contact patch is already moving.
     *
     * <p>Recomputed every substep and not once per tick, because the force depends on the robot's
     * own velocity: a wheel commanded at full speed pulls hard while the robot is stationary and
     * hardly at all once it has caught up. Computing it once per control cycle would accelerate a
     * standing robot as though it had been standing for the whole cycle.</p>
     */
    @Override
    public void step(double seconds) {
        DMatrix3C rotation = body.getRotation();
        DVector3C linear = body.getLinearVel();
        double yawRate = body.getAngularVel().get2();

        // The rotation's first column is the robot's nose in field coordinates, so its transpose
        // takes a field velocity into the robot frame without needing the heading angle at all.
        double cos = rotation.get00();
        double sin = rotation.get10();
        double alongRobot = linear.get0() * cos + linear.get1() * sin;
        double acrossRobot = -linear.get0() * sin + linear.get1() * cos;

        for (int wheel = 0; wheel < WHEELS; wheel++) {
            double atForward = FORWARD_SIGN[wheel] * halfWheelBase;
            double atLeft = LEFT_SIGN[wheel] * halfTrack;

            // This patch's own velocity: the chassis's, plus what the yaw carries it around by.
            double patchForward = alongRobot - yawRate * atLeft;
            double patchLeft = acrossRobot + yawRate * atForward;

            // How fast the patch is travelling along the direction this wheel drives in. The
            // lateral term carries the strafe efficiency, because roller scrub is exactly a loss
            // in the sideways half of that relation: a wheel turning at a given speed lays down
            // less sideways travel than its geometry promises, so the speed it settles at is
            // lower. Applying the loss to the force instead would change how hard it pushed
            // without changing where it ended up.
            double rolling = patchForward + LATERAL_SIGN[wheel] * patchLeft / strafeEfficiency;

            double slipping = wheelSpeeds[wheel] - rolling;
            double force = SLIP_STIFFNESS_NEWTONS_PER_METRE_PER_SECOND * slipping;
            if (force > tractionLimitNewtons) {
                force = tractionLimitNewtons;
            } else if (force < -tractionLimitNewtons) {
                force = -tractionLimitNewtons;
            }

            body.addRelForceAtRelPos(
                    force * ROOT_HALF, force * ROOT_HALF * LATERAL_SIGN[wheel], 0.0,
                    atForward, atLeft, 0.0);
        }
    }

    /**
     * An equal and opposite push, for a mechanism that is pushing on the world.
     *
     * <p>An intake dragging a ball in pulls the robot towards the ball, which is a real and
     * noticeable effect on a light robot and was simply missing while the chassis could not be
     * moved by anything.</p>
     */
    void addReaction(double fieldX, double fieldY, double atForwardMetres, double atLeftMetres) {
        body.addForceAtRelPos(fieldX, fieldY, 0.0, atForwardMetres, atLeftMetres, 0.0);
    }

    @Override
    public Pose2d pose() {
        DVector3C at = body.getPosition();
        DMatrix3C rotation = body.getRotation();
        return new Pose2d(at.get0(), at.get1(), Math.atan2(rotation.get10(), rotation.get00()));
    }

    @Override
    public ChassisVelocity velocity() {
        DMatrix3C rotation = body.getRotation();
        DVector3C linear = body.getLinearVel();
        double cos = rotation.get00();
        double sin = rotation.get10();
        return new ChassisVelocity(
                linear.get0() * cos + linear.get1() * sin,
                -linear.get0() * sin + linear.get1() * cos,
                body.getAngularVel().get2());
    }

    @Override
    public boolean inContact() {
        return contact;
    }

    @Override
    public void place(Pose2d pose) {
        double clampedX = Footprint.clamp(pose.x(),
                Footprint.limitX(pose.heading(), chassisConfig, field));
        double clampedY = Footprint.clamp(pose.y(),
                Footprint.limitY(pose.heading(), chassisConfig, field));

        body.setPosition(clampedX, clampedY, 0.0);
        // ODE's quaternion order is (w, x, y, z); a heading is a rotation about field +Z alone.
        heading.set(Math.cos(pose.heading() / 2.0), 0.0, 0.0, Math.sin(pose.heading() / 2.0));
        body.setQuaternion(heading);

        // Stopped dead, and with nothing left over from the step it was torn out of: a force
        // accumulated before a teleport would be applied at the destination.
        body.setLinearVel(0.0, 0.0, 0.0);
        body.setAngularVel(0.0, 0.0, 0.0);
        body.setForce(0.0, 0.0, 0.0);
        body.setTorque(0.0, 0.0, 0.0);
        body.enable();

        contact = clampedX != pose.x() || clampedY != pose.y();
        placed.run();
    }
}
