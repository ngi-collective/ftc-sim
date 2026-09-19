package org.ngicollective.testframework.sim;

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
 *
 * <p>Where the robot ends up is {@link Chassis}'s answer, not this class's. What stays here is
 * everything to do with the devices: resolving the four drive motors, reading their shaft speeds,
 * and publishing the chassis heading to the IMU. That split is what lets the same drivetrain be
 * carried either by arithmetic or by a rigid-body solver without a motor knowing the difference.
 */
public final class DriveModel {

    private static final String MECANUM = "mecanum";

    private static final String FRONT_LEFT = "frontLeft";
    private static final String FRONT_RIGHT = "frontRight";
    private static final String BACK_LEFT = "backLeft";
    private static final String BACK_RIGHT = "backRight";

    private static final List<String> DRIVE_ROLES =
            Arrays.asList(FRONT_LEFT, FRONT_RIGHT, BACK_LEFT, BACK_RIGHT);

    private final RobotConfig robot;
    private final FieldConfig field;

    private final Wheel frontLeft;
    private final Wheel frontRight;
    private final Wheel backLeft;
    private final Wheel backRight;

    /**
     * What carries the robot: see {@link Chassis} for why this is not decided here.
     *
     * <p>Replaceable, because the world the robot is in is a property of the session rather than
     * of the robot &mdash; a dashboard rebuilds it on every INIT. {@link #useChassis} is what
     * makes that a supported move instead of a robot that quietly keeps driving in the world
     * before last.</p>
     */
    private Chassis chassis;

    /** Where the chassis heading is published for the IMU behavior to pick up. */
    private final ImuState imu;

    public DriveModel(RobotConfig robot, FieldConfig field, FakeHardwareMap hardware,
                      Chassis chassis) {
        if (!MECANUM.equalsIgnoreCase(robot.drivetrain().type())) {
            throw new IllegalArgumentException("robot \"" + robot.name() + "\" has a \""
                    + robot.drivetrain().type() + "\" drivetrain; this model only drives \""
                    + MECANUM + "\"");
        }
        this.robot = robot;
        this.field = field;

        this.frontLeft = wheel(robot, hardware, FRONT_LEFT);
        this.frontRight = wheel(robot, hardware, FRONT_RIGHT);
        this.backLeft = wheel(robot, hardware, BACK_LEFT);
        this.backRight = wheel(robot, hardware, BACK_RIGHT);
        this.chassis = chassis;

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
     * Hands this tick's wheel speeds to the chassis, before anything steps it.
     *
     * <p>The shaft speed read here is the <em>physical</em> one a motor behavior produced, not the
     * power the OpMode asked for, which is what makes a stalled motor veer the robot and a ramping
     * one lag it.</p>
     *
     * <p>Split from {@link #publishHeading} because a rigid-body chassis is stepped by the world
     * it is a body in, not by this class: the wheels have to be set before that step and the
     * heading can only be read after it. Doing both in one call would publish the heading the
     * robot had a tick ago.</p>
     */
    public void commandWheels() {
        chassis.setWheelSpeeds(
                frontLeft.metresPerSecond(),
                frontRight.metresPerSecond(),
                backLeft.metresPerSecond(),
                backRight.metresPerSecond());
    }

    /**
     * Moves the robot into a different world, keeping where it was standing.
     *
     * <p>Carrying the pose across is the whole point. A session rebuilds its world when a
     * configuration file changes or an arrangement is loaded, and a robot that returned to the
     * origin every time would make the dashboard's "place the robot here" useless the moment
     * anything else was edited.</p>
     */
    public void useChassis(Chassis replacement) {
        replacement.place(chassis.pose());
        chassis = replacement;
        publishHeading();
    }

    /** Where the robot is, in metres from field centre. */
    public Pose2d pose() {
        return chassis.pose();
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
        chassis.place(pose);

        publishHeading();
        imu.setYaw(Math.toDegrees(chassis.pose().heading()));
        imu.setYawRateDegreesPerSecond(0.0);
    }

    /** How the chassis is moving, in its own frame. */
    public ChassisVelocity velocity() {
        return chassis.velocity();
    }

    /**
     * True while the robot is pressed against something eating the motion it is asking for.
     *
     * <p>The perimeter, today. Once the field's own structures are collision geometry this will
     * answer for those too, which is why it is no longer named after walls at the chassis
     * boundary.</p>
     */
    public boolean inWallContact() {
        return chassis.inContact();
    }

    /**
     * Hands the chassis heading to the IMU, which is the only path by which an OpMode learns that
     * the robot turned. {@code ImuBehaviors.followingChassis()} is what copies it to the reported
     * yaw; any other behavior is a fault being injected on purpose.
     *
     * <p>Public because it is the second half of a tick: whatever steps the chassis has to call
     * this afterwards, and at 50 Hz a fast pivot covers several degrees in a tick &mdash; exactly
     * the lag a heading-holding routine would otherwise be tuned against and fail on the real
     * robot.</p>
     */
    public void publishHeading() {
        imu.setChassisYawDegrees(Math.toDegrees(chassis.pose().heading()));
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
