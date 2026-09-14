package org.ngicollective.testframework.sim;

import org.firstinspires.ftc.robotcore.external.navigation.AngleUnit;
import org.ngicollective.testframework.behavior.ImuState;
import org.ngicollective.testframework.hardware.FakeDcMotorEx;
import org.ngicollective.testframework.hardware.FakeHardwareMap;
import org.ngicollective.testframework.hardware.FakeImu;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * The plant: four spinning wheels in, a field pose out.
 *
 * <p>Every tick this reads the <em>physical</em> shaft speed of each drive motor &mdash; the one a
 * motor behavior produced, not the power the OpMode asked for &mdash; and integrates the chassis
 * motion those wheels imply. Reading downstream of the behaviors is the whole point: a
 * {@code stalled} motor makes the robot veer, and a {@code ramping} one makes it lag, exactly as
 * the real thing would. Nothing about the OpMode's intentions is consulted anywhere in here.</p>
 *
 * <p>The chassis heading is published to the IMU every tick, so a robot that turns because its
 * wheels turned is a robot whose IMU says so. Pointing the IMU at a different behavior is then a
 * deliberate fault: an IMU that disagrees with the drivetrain, which is a real failure and one
 * worth being able to reproduce.</p>
 */
public final class DriveModel {

    private static final String MECANUM = "mecanum";

    private static final String FRONT_LEFT = "frontLeft";
    private static final String FRONT_RIGHT = "frontRight";
    private static final String BACK_LEFT = "backLeft";
    private static final String BACK_RIGHT = "backRight";

    private static final List<String> DRIVE_ROLES =
            Arrays.asList(FRONT_LEFT, FRONT_RIGHT, BACK_LEFT, BACK_RIGHT);

    /**
     * Below this much turn in a tick the arc and the straight line differ by less than a nanometre,
     * and the arc formulas divide by the yaw. Anything larger takes the exact integration.
     */
    private static final double STRAIGHT_LINE_RADIANS = 1e-9;

    private final RobotConfig robot;
    private final FieldConfig field;

    private final Wheel frontLeft;
    private final Wheel frontRight;
    private final Wheel backLeft;
    private final Wheel backRight;

    /** Half the sum of track width and wheel base: the lever a mecanum wheel turns the robot on. */
    private final double yawLeverMetres;

    private final double strafeEfficiency;
    private final double halfLength;
    private final double halfWidth;

    /** Where the chassis heading is published for the IMU behavior to pick up. */
    private final ImuState imu;

    private double x;
    private double y;
    private double heading;
    private Pose2d pose = Pose2d.ORIGIN;
    private ChassisVelocity velocity = ChassisVelocity.ZERO;
    private boolean wallContact;

    public DriveModel(RobotConfig robot, FieldConfig field, FakeHardwareMap hardware) {
        if (!MECANUM.equalsIgnoreCase(robot.drivetrain().type())) {
            throw new IllegalArgumentException("robot \"" + robot.name() + "\" has a \""
                    + robot.drivetrain().type() + "\" drivetrain; this model only drives \""
                    + MECANUM + "\"");
        }
        this.robot = robot;
        this.field = field;

        DrivetrainConfig drivetrain = robot.drivetrain();
        this.frontLeft = wheel(robot, hardware, FRONT_LEFT);
        this.frontRight = wheel(robot, hardware, FRONT_RIGHT);
        this.backLeft = wheel(robot, hardware, BACK_LEFT);
        this.backRight = wheel(robot, hardware, BACK_RIGHT);
        this.yawLeverMetres =
                (drivetrain.trackWidthMetres() + drivetrain.wheelBaseMetres()) / 2.0;
        this.strafeEfficiency = drivetrain.strafeEfficiency();
        this.halfLength = robot.chassis().lengthMetres() / 2.0;
        this.halfWidth = robot.chassis().widthMetres() / 2.0;

        FakeImu fakeImu = hardware.tryGet(FakeImu.class, robot.imuName());
        if (fakeImu == null) {
            throw new IllegalArgumentException("robot \"" + robot.name() + "\" declares the IMU \""
                    + robot.imuName() + "\", but no such simulated IMU is in the hardware map; the "
                    + "drivetrain publishes the chassis heading there every tick");
        }
        this.imu = fakeImu.state();

        // Seeds the IMU with the starting heading, so the first tick of an OpMode reads a real
        // number rather than whatever an unwritten field happened to hold.
        setPose(Pose2d.ORIGIN);
    }

    /** The robot being simulated, for anything that needs its geometry without re-reading the file. */
    public RobotConfig robot() {
        return robot;
    }

    /** The field being simulated on, same reason. */
    public FieldConfig field() {
        return field;
    }

    /**
     * Integrates {@code seconds} of driving from the speed the wheels are turning right now.
     *
     * <p>Wheel speeds are taken as constant across the tick and integrated as an arc, not as a
     * straight line followed by a turn. At 50 Hz a fast pivot covers several degrees per tick, and
     * Euler integration bends that arc visibly the wrong way &mdash; a drift this model must not
     * have, because every heading-holding routine in TeamCode is validated against it.</p>
     */
    public void advance(double seconds) {
        if (seconds < 0.0) {
            throw new IllegalArgumentException("cannot advance simulated time backwards");
        }

        double frontLeftSpeed = frontLeft.metresPerSecond();
        double frontRightSpeed = frontRight.metresPerSecond();
        double backLeftSpeed = backLeft.metresPerSecond();
        double backRightSpeed = backRight.metresPerSecond();

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
        double limitX = limitX(cosEnd, sinEnd);
        double limitY = limitY(cosEnd, sinEnd);
        double clampedX = clamp(candidateX, limitX);
        double clampedY = clamp(candidateY, limitY);
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

        publishHeading();
    }

    /** Where the robot is, in metres from field centre. */
    public Pose2d pose() {
        return pose;
    }

    /**
     * Teleports the robot and stops it dead.
     *
     * <p>A teleport is not a motion. The accumulated velocity is dropped rather than carried across
     * the jump, and the IMU is carried to the new heading with the robot rather than left to deduce
     * a spin that never happened &mdash; a placement while the clock is paused would otherwise read
     * out as several thousand degrees/second on the next tick. A fault-injecting IMU behavior
     * reasserts itself on that tick, so dragging the sensor along here costs it nothing.</p>
     *
     * <p>The pose is clamped onto the field for the same reason a driven one is: a robot placed
     * through a wall has no legal way back.</p>
     */
    public void setPose(Pose2d pose) {
        double cos = Math.cos(pose.heading());
        double sin = Math.sin(pose.heading());
        double clampedX = clamp(pose.x(), limitX(cos, sin));
        double clampedY = clamp(pose.y(), limitY(cos, sin));

        x = clampedX;
        y = clampedY;
        heading = pose.heading();
        wallContact = clampedX != pose.x() || clampedY != pose.y();
        this.pose = new Pose2d(x, y, heading);
        velocity = ChassisVelocity.ZERO;

        publishHeading();
        imu.setYaw(Math.toDegrees(heading));
        imu.setYawRateDegreesPerSecond(0.0);
    }

    /** How the chassis is moving, in its own frame. */
    public ChassisVelocity velocity() {
        return velocity;
    }

    /** True while the footprint is pressed against a wall, with its normal motion being eaten. */
    public boolean inWallContact() {
        return wallContact;
    }

    /**
     * Hands the chassis heading to the IMU, which is the only path by which an OpMode learns that
     * the robot turned. {@code ImuBehaviors.followingChassis()} is what copies it to the reported
     * yaw; any other behavior is a fault being injected on purpose.
     */
    private void publishHeading() {
        imu.setChassisYawDegrees(Math.toDegrees(heading));
    }

    /** How far the centre may sit from field centre along X before the footprint hits a wall. */
    private double limitX(double cosHeading, double sinHeading) {
        return Math.max(0.0, field.halfExtentMetres()
                - (halfLength * Math.abs(cosHeading) + halfWidth * Math.abs(sinHeading)));
    }

    /** The same along Y, where the robot's length and width swap roles. */
    private double limitY(double cosHeading, double sinHeading) {
        return Math.max(0.0, field.halfExtentMetres()
                - (halfLength * Math.abs(sinHeading) + halfWidth * Math.abs(cosHeading)));
    }

    private static double clamp(double value, double limit) {
        if (value < -limit) {
            return -limit;
        }
        return value > limit ? limit : value;
    }

    /** Resolves one drive role to the motor configured for it, and precomputes its tick scaling. */
    private static Wheel wheel(RobotConfig robot, FakeHardwareMap hardware, String role) {
        String hardwareName = null;
        MotorConfig config = null;
        for (Map.Entry<String, MotorConfig> entry : robot.motors().entrySet()) {
            if (!role.equals(entry.getValue().role())) {
                continue;
            }
            if (hardwareName != null) {
                throw new IllegalArgumentException("robot \"" + robot.name() + "\" gives two motors"
                        + " the \"" + role + "\" role: \"" + hardwareName + "\" and \""
                        + entry.getKey() + "\"");
            }
            hardwareName = entry.getKey();
            config = entry.getValue();
        }
        if (hardwareName == null) {
            throw new IllegalArgumentException("robot \"" + robot.name() + "\" has no motor in the"
                    + " \"" + role + "\" role; a mecanum drivetrain needs all of " + DRIVE_ROLES);
        }
        FakeDcMotorEx motor = hardware.tryGet(FakeDcMotorEx.class, hardwareName);
        if (motor == null) {
            throw new IllegalArgumentException("the \"" + role + "\" motor \"" + hardwareName
                    + "\" is not in the hardware map; add it with addMotor(\"" + hardwareName
                    + "\") before declaring the drivetrain");
        }

        // The mounting sign is folded into the scaling constant rather than branched on every tick:
        // a mirrored motor's positive shaft rotation drives its wheel backwards, so its encoder has
        // to be read with the opposite sign before the kinematics can treat all four wheels as
        // forward-positive.
        DrivetrainConfig drivetrain = robot.drivetrain();
        double metresPerTick = drivetrain.gearRatio() * 2.0 * Math.PI
                * drivetrain.wheelRadiusMetres() / config.ticksPerRevolution();
        return new Wheel(motor, config.mirrored() ? -metresPerTick : metresPerTick);
    }

    /**
     * One drive wheel and the constant that turns its encoder into floor.
     *
     * <p>The scaling is per wheel rather than per drivetrain because the encoder resolution is a
     * property of the motor that happens to be bolted to it, and a robot that had one gearbox
     * swapped mid-season would otherwise drive fine in simulation and crab on the field. The sign
     * of the constant carries {@link MotorConfig#mirrored()}, the physical mounting.</p>
     */
    private static final class Wheel {

        private final FakeDcMotorEx motor;
        private final double metresPerTick;

        private Wheel(FakeDcMotorEx motor, double metresPerTick) {
            this.motor = motor;
            this.metresPerTick = metresPerTick;
        }

        /**
         * The speed this wheel's contact patch is laying down, in metres/second, positive forwards
         * for every wheel on the robot.
         *
         * <p>Two sign changes meet here and they are not the same one. The shaft speed is already
         * in the physical frame, so the OpMode's {@code Direction} has been applied to it &mdash;
         * that is software compensating for the mounting. The mounting itself is the sign of
         * {@code metresPerTick}. On a correctly configured robot the two cancel and the wheel
         * drives forward; when someone drops a {@code setDirection(REVERSE)} they no longer cancel
         * and the robot spins in place, which is precisely the bug this model is supposed to show
         * rather than absorb.</p>
         */
        private double metresPerSecond() {
            return motor.state().getVelocity() * metresPerTick;
        }
    }
}
