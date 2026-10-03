package org.firstinspires.ftc.teamcode;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.firstinspires.ftc.teamcode.simulated.ExampleRobot;
import org.junit.jupiter.api.Test;
import org.ngicollective.ftcsim.hardware.FakeHardwareMap;
import org.ngicollective.ftcsim.harness.LinearOpModeHarness;
import org.ngicollective.ftcsim.harness.OpModeHarness;
import org.ngicollective.ftcsim.sim.Pose2d;

/**
 * Where the robot actually ends up for a given stick input, driven through the real
 * {@link BasicMecanumTeleOp} against the simulated drivetrain.
 *
 * <p>These assert displacement rather than wheel powers on purpose. A test that pins
 * {@code fl == +1 && fr == -1} passes happily when two motors are swapped or a mounting sign is
 * wrong; only the pose says whether the robot went where the driver asked. The first version of
 * the drive model passed every wheel-power test in this module and still turned a full-forward
 * stick into 106 degrees of rotation, because it read the physical shaft frame without accounting
 * for the mirrored left-side mounting.</p>
 */
class MecanumDrivePoseTest {

    private static final double TICK = 0.02;

    /**
     * Free wheel speed: 312 RPM on a 48 mm wheel.
     *
     * <p>Taken straight from revolutions per second rather than by way of the encoder's
     * 537.7 ticks, because that round trip used to be written out as a rounded 2796 ticks/s and
     * the 14-parts-per-million error in it is larger than the margin
     * {@link #theRobotSettlesAtTheSpeedItsWheelsAreTurning} works to.</p>
     */
    private static final double TOP_SPEED = 312.0 / 60.0 * 2 * Math.PI * 0.048;

    /**
     * How much of the free-speed distance a standing start covers, at least and at most.
     *
     * <p>Neither end is arbitrary. The chassis is a rigid body accelerated by what its four
     * contact patches can grip, so it cannot leave the line at free speed: the upper bound is what
     * grip forbids, and a robot that beat it would mean the traction limit had stopped being
     * applied. The lower bound is what a robot that got going at all must manage; below it
     * something is fighting the wheels. The gap between them is the ramp, which for the example robot is
     * about three tenths of a second, and pinning a number inside it would be pinning the solver's
     * tuning rather than the robot's behaviour.</p>
     */
    private static final double SLOWEST_STANDING_START = 0.6;
    private static final double FASTEST_STANDING_START = 0.85;

    private FakeHardwareMap hardware;
    private LinearOpModeHarness harness;

    private void stick(double leftX, double leftY, double rightX) {
        hardware = new ExampleRobot().create();
        harness = OpModeHarness.forLinear(new BasicMecanumTeleOp(), hardware);
        harness.launch();
        harness.pressStart();
        harness.gamepad1().left_stick_x = (float) leftX;
        harness.gamepad1().left_stick_y = (float) leftY;
        harness.gamepad1().right_stick_x = (float) rightX;

        awaitAllWheelsCommanded();

        // Measure from a standstill at the origin. The wait above deliberately does not advance
        // the simulation: a tick landing mid-command integrates a partial mecanum solution (some
        // wheels set, some still at their previous power), which turns up as phantom drift in the
        // pose and made these tests fail roughly one run in eight.
        hardware.drive().setPose(Pose2d.ORIGIN);
    }

    /**
     * Blocks until the OpMode thread has applied a power to every wheel.
     *
     * <p>Commanded power, not measured velocity: {@code setPower} takes effect immediately, while
     * velocity only ramps once the simulation advances &mdash; and advancing is the very thing
     * that must not happen while a command is half-applied.</p>
     */
    private void awaitAllWheelsCommanded() {
        for (int attempt = 0; attempt < 500; attempt++) {
            harness.eventLoopIteration();
            if (allWheelsCommanded()) {
                return;
            }
            try {
                Thread.sleep(1);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AssertionError("interrupted waiting for the OpMode to drive", interrupted);
            }
        }
        throw new AssertionError("the OpMode never commanded all four wheels");
    }

    private boolean allWheelsCommanded() {
        for (String wheel : new String[] {"FL", "FR", "BL", "BR"}) {
            if (hardware.motor(wheel).getPower() == 0.0) {
                return false;
            }
        }
        return true;
    }

    /** Drives for {@code seconds} and stops the OpMode, which is what most of these want. */
    private Pose2d after(double seconds) {
        return after(seconds, true);
    }

    /**
     * The same, optionally leaving the OpMode running.
     *
     * <p>Needed now that a standing start takes time: measuring what the robot does once it is up
     * to speed means driving one leg, reading the pose, and carrying on with the same OpMode.</p>
     */
    private Pose2d after(double seconds, boolean thenStop) {
        for (int tick = 0; tick < Math.round(seconds / TICK); tick++) {
            harness.advance(TICK);
            harness.eventLoopIteration();
        }
        Pose2d pose = hardware.drive().pose();
        if (!thenStop) {
            return pose;
        }
        harness.pressStop();
        System.out.printf("x=%+.3f y=%+.3f heading=%+.1f deg  imu=%+.1f deg%n",
                pose.x(), pose.y(), pose.headingDegrees(),
                hardware.imu("imu").state().reportedYaw());
        return pose;
    }

    @Test
    void forwardStickDrivesAlongPlusX() {
        stick(0.0, -1.0, 0.0);              // stick Y is negative pushed away from the driver
        Pose2d pose = after(0.5);
        double freeSpeedDistance = TOP_SPEED * 0.5;
        assertTrue(pose.x() > SLOWEST_STANDING_START * freeSpeedDistance,
                "expected the robot to get going along +X, got " + pose.x());
        assertTrue(pose.x() < FASTEST_STANDING_START * freeSpeedDistance,
                "expected grip to limit the launch, got " + pose.x());
        assertTrue(Math.abs(pose.y()) < 0.005, "expected no lateral drift, got " + pose.y());
        assertTrue(Math.abs(pose.headingDegrees()) < 0.5, "expected no rotation");
    }

    /**
     * The wheels bound the robot: it reaches the speed they are turning at and stops there.
     *
     * <p>Worth its own test because the drivetrain is now a slip model, and the whole point of one
     * is that a wheel pulls the robot towards its own surface speed. A scale error anywhere in
     * that &mdash; the 45 degree roller projection, the strafe efficiency dividing the wrong term
     * &mdash; shows up as a robot that settles faster or slower than its wheels, which no
     * displacement-over-half-a-second assertion would notice.</p>
     */
    @Test
    void theRobotSettlesAtTheSpeedItsWheelsAreTurning() {
        stick(0.0, -1.0, 0.0);
        // From the red end, because free speed needs room: from the middle the robot reaches the
        // far wall in 1.2 s and the answer would be whatever the wall left it.
        hardware.drive().setPose(new Pose2d(-1.5, 0.0, 0.0));

        Pose2d first = after(1.0, false);
        Pose2d second = after(0.5);

        // It settles on free speed to the last bit: a wheel's force is proportional to its slip,
        // so the slip goes to zero and stays there, and the only gap left is floating point. The
        // tolerance is for that, not for a margin the model needs.
        double settled = (second.x() - first.x()) / 0.5;
        assertTrue(settled <= TOP_SPEED * (1.0 + 1e-9),
                "the robot outran its own wheels: " + settled + " m/s beats " + TOP_SPEED);
        assertTrue(settled > 0.99 * TOP_SPEED,
                "expected to have reached free speed after a second, got " + settled + " m/s");
    }

    /**
     * Sticks at rest leave the robot where it is.
     *
     * <p>A rigid body keeps whatever force it was given, so this is the test that a force is not
     * left applied after the wheels stop asking for one: a robot that crept with nobody driving
     * would drift out of position through a whole autonomous period.</p>
     */
    @Test
    void aRobotWithNoPowerStaysPut() {
        stick(0.0, -1.0, 0.0);
        after(1.0, false);

        harness.gamepad1().left_stick_y = 0.0f;
        Pose2d stopped = after(1.0, false);
        Pose2d later = after(2.0);

        assertTrue(Math.abs(later.x() - stopped.x()) < 1e-3,
                "the robot crept " + (later.x() - stopped.x()) + " m with no power");
    }

    @Test
    void strafeRightTravelsTowardMinusY() {
        stick(1.0, 0.0, 0.0);
        Pose2d pose = after(0.5);
        // 0.8 strafe efficiency: the rollers scrub, so sideways is slower than driving.
        double freeStrafeDistance = 0.8 * TOP_SPEED * 0.5;
        assertTrue(pose.y() < -SLOWEST_STANDING_START * freeStrafeDistance,
                "expected the robot to get going toward -Y, got " + pose.y());
        assertTrue(pose.y() > -FASTEST_STANDING_START * freeStrafeDistance,
                "expected grip to limit the launch, got " + pose.y());
        assertTrue(Math.abs(pose.x()) < 0.005, "expected no forward drift, got " + pose.x());
    }

    @Test
    void twistRotatesClockwiseInPlace() {
        stick(0.0, 0.0, 1.0);
        Pose2d pose = after(0.5);
        assertTrue(pose.headingDegrees() < -30.0, "expected clockwise rotation");
        assertTrue(Math.hypot(pose.x(), pose.y()) < 0.005, "expected rotation in place");
    }

    @Test
    void wallStopsTheRobotButNotTheEncoders() {
        stick(0.0, -1.0, 0.0);
        Pose2d pose = after(4.0);
        double ticks = hardware.motor("FL").state().getPosition();
        System.out.printf("4 s at full forward -> x=%.3f contact=%s FL encoder=%.0f ticks%n",
                pose.x(), hardware.drive().inWallContact(), ticks);
        assertTrue(hardware.drive().inWallContact(), "expected wall contact");
        assertTrue(pose.x() < 1.6, "expected the footprint to stay inside the field");
        assertTrue(Math.abs(ticks) > 5000, "expected encoders to keep counting while pinned");
    }
}
