package org.ngicollective.testframework.sim;

import org.firstinspires.ftc.robotcore.external.navigation.AngleUnit;

/**
 * A chassis that goes exactly where its wheels say it should.
 *
 * <p>Mecanum forward kinematics integrated straight into a pose, with the footprint stopped at the
 * perimeter by arithmetic rather than by collision. Nothing can push this robot: it is moved by
 * its wheels and by nothing else, so a ball, a field structure or another robot leaves no mark on
 * it.</p>
 *
 * <p>That is a real limitation and it is also the behaviour this simulator had before a solver
 * existed. It stays as the implementation used when ode4j is not on the classpath, because a
 * robot that drives and cannot be shoved is far more useful than no robot at all &mdash; every
 * vision test, every heading-holding routine and every dead-reckoning check works against it. See
 * {@link Chassis} for why that fallback has to be reachable without naming the solver.</p>
 */
final class KinematicChassis implements Chassis {

    /**
     * Below this much turn in a tick the arc and the straight line differ by less than a nanometre,
     * and the arc formulas divide by the yaw. Anything larger takes the exact integration.
     */
    private static final double STRAIGHT_LINE_RADIANS = 1e-9;

    private final FieldConfig field;
    private final ChassisConfig chassis;

    /** Half the sum of track width and wheel base: the lever a mecanum wheel turns the robot on. */
    private final double yawLeverMetres;

    private final double strafeEfficiency;

    private double frontLeftSpeed;
    private double frontRightSpeed;
    private double backLeftSpeed;
    private double backRightSpeed;

    private double x;
    private double y;
    private double heading;
    private Pose2d pose = Pose2d.ORIGIN;
    private ChassisVelocity velocity = ChassisVelocity.ZERO;
    private boolean wallContact;

    KinematicChassis(RobotConfig robot, FieldConfig field) {
        this.field = field;
        this.chassis = robot.chassis();

        DrivetrainConfig drivetrain = robot.drivetrain();
        this.yawLeverMetres = (drivetrain.trackWidthMetres() + drivetrain.wheelBaseMetres()) / 2.0;
        this.strafeEfficiency = drivetrain.strafeEfficiency();
    }

    @Override
    public void setWheelSpeeds(double frontLeft, double frontRight,
                               double backLeft, double backRight) {
        this.frontLeftSpeed = frontLeft;
        this.frontRightSpeed = frontRight;
        this.backLeftSpeed = backLeft;
        this.backRightSpeed = backRight;
    }

    /**
     * Integrates {@code seconds} of driving from the speed the wheels are turning right now.
     *
     * <p>Wheel speeds are taken as constant across the tick and integrated as an arc, not as a
     * straight line followed by a turn. At 50 Hz a fast pivot covers several degrees per tick, and
     * Euler integration bends that arc visibly the wrong way &mdash; a drift this model must not
     * have, because every heading-holding routine in TeamCode is validated against it.</p>
     */
    @Override
    public void step(double seconds) {
        if (seconds < 0.0) {
            throw new IllegalArgumentException("cannot advance simulated time backwards");
        }

        // Mecanum forward kinematics, rollers at 45 degrees, nose along +X and lateral to the left.
        // Strafing is the only component the wheels cannot deliver in full: the rollers scrub, so
        // the measured sideways speed is a fraction of what the geometry alone predicts.
        double forward =
                (frontLeftSpeed + frontRightSpeed + backLeftSpeed + backRightSpeed) / 4.0;
        double lateral =
                (-frontLeftSpeed + frontRightSpeed + backLeftSpeed - backRightSpeed) / 4.0
                        * strafeEfficiency;
        double yawRate =
                (-frontLeftSpeed + frontRightSpeed - backLeftSpeed + backRightSpeed)
                        / (4.0 * yawLeverMetres);

        double yaw = yawRate * seconds;
        double forwardStep = forward * seconds;
        double lateralStep = lateral * seconds;

        // Pose exponential: the chassis travels an arc of constant curvature over the tick. The
        // sin(yaw)/yaw and (1-cos(yaw))/yaw factors are what turn the straight-line step into the
        // chord of that arc; both tend to the straight-line case as the yaw goes to zero.
        double localForward;
        double localLateral;
        if (Math.abs(yaw) < STRAIGHT_LINE_RADIANS) {
            localForward = forwardStep;
            localLateral = lateralStep;
        } else {
            double sinYaw = Math.sin(yaw);
            double cosYaw = Math.cos(yaw);
            localForward = (forwardStep * sinYaw + lateralStep * (cosYaw - 1.0)) / yaw;
            localLateral = (forwardStep * (1.0 - cosYaw) + lateralStep * sinYaw) / yaw;
        }

        // Rotated by the heading the tick started at: that is the frame the wheels pushed in.
        double cosStart = Math.cos(heading);
        double sinStart = Math.sin(heading);
        double candidateX = x + localForward * cosStart - localLateral * sinStart;
        double candidateY = y + localForward * sinStart + localLateral * cosStart;
        double candidateHeading = AngleUnit.normalizeRadians(heading + yaw);

        // The footprint is a rectangle that rotates with the robot, so how close its centre may get
        // to a wall depends on which way it is pointing: a robot at 45 degrees has to stop sooner.
        double cosEnd = Math.cos(candidateHeading);
        double sinEnd = Math.sin(candidateHeading);
        double limitX = Footprint.limitX(candidateHeading, chassis, field);
        double limitY = Footprint.limitY(candidateHeading, chassis, field);
        double clampedX = Footprint.clamp(candidateX, limitX);
        double clampedY = Footprint.clamp(candidateY, limitY);
        boolean againstXWall = clampedX != candidateX;
        boolean againstYWall = clampedY != candidateY;

        if (againstXWall || againstYWall) {
            // Slide along the wall rather than stop dead against it: only the component pushing
            // into the wall is lost, which is what a robot pinned on a wall and driven diagonally
            // actually does.
            //
            // The motors are deliberately left alone here. The wheels keep spinning and the
            // encoders keep counting while the robot goes nowhere, so dead reckoning drifts away
            // from the truth exactly as it does on a real field -- that divergence between the
            // encoders and where the robot really is, is the failure mode this simulator exists to
            // expose, and silently stopping the wheels would hide it.
            double fieldVelocityX = forward * cosEnd - lateral * sinEnd;
            double fieldVelocityY = forward * sinEnd + lateral * cosEnd;
            if (againstXWall) {
                fieldVelocityX = 0.0;
            }
            if (againstYWall) {
                fieldVelocityY = 0.0;
            }
            forward = fieldVelocityX * cosEnd + fieldVelocityY * sinEnd;
            lateral = -fieldVelocityX * sinEnd + fieldVelocityY * cosEnd;
        }

        x = clampedX;
        y = clampedY;
        heading = candidateHeading;
        wallContact = againstXWall || againstYWall;
        pose = new Pose2d(x, y, heading);
        velocity = new ChassisVelocity(forward, lateral, yawRate);
    }

    @Override
    public Pose2d pose() {
        return pose;
    }

    @Override
    public ChassisVelocity velocity() {
        return velocity;
    }

    @Override
    public boolean inContact() {
        return wallContact;
    }

    @Override
    public void place(Pose2d pose) {
        double clampedX = Footprint.clamp(pose.x(),
                Footprint.limitX(pose.heading(), chassis, field));
        double clampedY = Footprint.clamp(pose.y(),
                Footprint.limitY(pose.heading(), chassis, field));

        x = clampedX;
        y = clampedY;
        heading = pose.heading();
        wallContact = clampedX != pose.x() || clampedY != pose.y();
        this.pose = new Pose2d(x, y, heading);
        velocity = ChassisVelocity.ZERO;
    }
}
