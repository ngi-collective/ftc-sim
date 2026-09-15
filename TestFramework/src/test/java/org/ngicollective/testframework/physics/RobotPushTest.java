package org.ngicollective.testframework.physics;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.ngicollective.testframework.behavior.ImuBehaviors;
import org.ngicollective.testframework.camera.GameElement;
import org.ngicollective.testframework.hardware.FakeHardwareMap;
import org.ngicollective.testframework.sim.FieldConfig;
import org.ngicollective.testframework.sim.Pose2d;
import org.ngicollective.testframework.sim.RobotConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * What happens when the robot drives into the balls.
 *
 * <p>The robot here is a plain rectangle with four unmirrored motors, described by a file this test
 * writes itself rather than by {@code TeamCode/robot-config/verity.json}. Two reasons, and the
 * second is the important one: TeamCode's configuration is not on this module's path at all, and
 * pinning physics assertions to the competition robot's dimensions would mean that widening the
 * chassis by a centimetre next season breaks a test about collision detection.</p>
 *
 * <p>Full power on all four wheels, positive, with no mirroring: the mecanum sign conventions are
 * {@code DriveModel}'s contract and {@code MecanumDrivePoseTest} already holds it to them. What is
 * under test here is only what the chassis does to a ball once it gets there.</p>
 */
class RobotPushTest {

    private static final double TICK = 0.02;

    /** goBILDA 5203 19.2:1 through the config below: 312 RPM, 537.7 ticks, 48 mm wheel. */
    private static final double TOP_SPEED_METRES_PER_SECOND = 312.0 / 60.0 * 2.0 * Math.PI * 0.048;

    private static final String ROBOT_JSON = "{\n"
            + "  \"version\": 1,\n"
            + "  \"name\": \"Pusher\",\n"
            + "  \"chassis\": { \"widthMetres\": 0.38, \"lengthMetres\": 0.40,"
            + " \"heightMetres\": 0.05, \"deckHeightMetres\": 0.105 },\n"
            + "  \"drivetrain\": { \"type\": \"mecanum\", \"wheelRadiusMetres\": 0.048,"
            + " \"gearRatio\": 1.0, \"trackWidthMetres\": 0.32, \"wheelBaseMetres\": 0.29,"
            + " \"strafeEfficiency\": 0.8 },\n"
            + "  \"imu\": { \"name\": \"imu\" },\n"
            + "  \"camera\": { \"name\": \"Webcam 1\", \"forwardMetres\": 0.16,"
            + " \"leftMetres\": 0.0, \"yawDegrees\": 0.0, \"pitchDegrees\": 35.0,"
            + " \"rollDegrees\": 0.0, \"framesPerSecond\": 30.0 },\n"
            + "  \"motors\": {\n"
            + "    \"FL\": { \"role\": \"frontLeft\",  \"mirrored\": false, \"rpm\": 312.0,"
            + " \"ticksPerRevolution\": 537.7 },\n"
            + "    \"FR\": { \"role\": \"frontRight\", \"mirrored\": false, \"rpm\": 312.0,"
            + " \"ticksPerRevolution\": 537.7 },\n"
            + "    \"BL\": { \"role\": \"backLeft\",   \"mirrored\": false, \"rpm\": 312.0,"
            + " \"ticksPerRevolution\": 537.7 },\n"
            + "    \"BR\": { \"role\": \"backRight\",  \"mirrored\": false, \"rpm\": 312.0,"
            + " \"ticksPerRevolution\": 537.7 }\n"
            + "  }\n"
            + "}\n";

    @TempDir
    Path configDirectory;

    private RobotConfig robot;

    @BeforeEach
    void readTheRobot() throws IOException {
        Path file = configDirectory.resolve("pusher.json");
        Files.write(file, ROBOT_JSON.getBytes(UTF_8));
        robot = RobotConfig.load(file);
    }

    private FakeHardwareMap hardware() {
        FakeHardwareMap hardware = FakeHardwareMap.builder()
                .addMotor("FL").addMotor("FR").addMotor("BL").addMotor("BR")
                .addImu("imu", ImuBehaviors.followingChassis())
                .withDrivetrain(robot, FieldConfig.standard())
                .build();
        for (String wheel : new String[] {"FL", "FR", "BL", "BR"}) {
            hardware.motor(wheel).state().setMaxSpeed(312.0, 537.7);
        }
        return hardware;
    }

    /** Drives the session forward at {@code power} for {@code seconds}, physics and all. */
    private void drive(FakeHardwareMap hardware, FieldPhysics world, double power,
                       double seconds) {
        drive(hardware, world, power, seconds, TICK);
    }

    /**
     * The same, with the length of a control cycle as a parameter.
     *
     * <p>Which is what the session's speed multiplier changes: at 8&times; the dashboard hands the
     * hardware 160&nbsp;ms of simulated time per cycle, not 20.</p>
     */
    private void drive(FakeHardwareMap hardware, FieldPhysics world, double power,
                       double seconds, double cycleSeconds) {
        for (String wheel : new String[] {"FL", "FR", "BL", "BR"}) {
            hardware.motor(wheel).setPower(power);
        }
        for (int tick = 0; tick < Math.round(seconds / cycleSeconds); tick++) {
            // The same order the dashboard's tick uses: the drive model integrates the wheels
            // first, then the physics world is carried to the pose that produced.
            hardware.advance(cycleSeconds);
            world.advance(cycleSeconds);
        }
    }

    @Test
    void theRobotShovesABallOutOfItsWay() {
        FakeHardwareMap hardware = hardware();
        hardware.drive().setPose(Pose2d.ORIGIN);
        // Half a metre ahead, dead on the nose: the chassis is 0.40 m long, so its bumper starts
        // 0.20 m out and reaches the ball a quarter of a second in.
        FieldPhysics world = FieldPhysics.of(
                Arrays.asList(GameElement.pollen(0.5, 0.0)),
                FieldConfig.standard(), hardware.drive());

        drive(hardware, world, 1.0, 0.6);

        BodyState ball = world.bodies().get(0);
        double nose = hardware.drive().pose().x() + robot.chassis().lengthMetres() / 2.0;
        assertTrue(ball.x() > 0.55,
                "the robot drove into the ball and it did not move: x = " + ball.x());
        assertTrue(ball.x() >= nose - 0.02,
                "the ball ended up inside the robot rather than in front of it: ball at "
                        + ball.x() + ", bumper at " + nose);
        assertTrue(Math.abs(ball.y()) < 0.1,
                "a ball hit square on should go forwards, not sideways: y = " + ball.y());
    }

    @Test
    void aShovedBallRollsToAStopOnceTheRobotStops() {
        FakeHardwareMap hardware = hardware();
        hardware.drive().setPose(Pose2d.ORIGIN);
        FieldPhysics world = FieldPhysics.of(
                Arrays.asList(GameElement.pollen(0.5, 0.0)),
                FieldConfig.standard(), hardware.drive());

        drive(hardware, world, 1.0, 0.5);
        // Wheels off. Whatever the ball does now is the floor's doing, not the robot's.
        drive(hardware, world, 0.0, 0.0);

        List<Double> speeds = new ArrayList<>();
        BodyState previous = world.bodies().get(0);
        for (int sample = 0; sample < 40; sample++) {
            for (int tick = 0; tick < 5; tick++) {
                hardware.advance(TICK);
                world.advance(TICK);
            }
            BodyState now = world.bodies().get(0);
            speeds.add(Math.hypot(now.x() - previous.x(), now.y() - previous.y()) / (5 * TICK));
            previous = now;
        }

        assertTrue(speeds.get(0) > 0.3,
                "the shove should have left the ball rolling, got " + speeds.get(0) + " m/s");
        for (int sample = 1; sample < speeds.size(); sample++) {
            // Rolling resistance only ever takes energy out. An increase means the solver is
            // feeding the ball, or that it is still in contact with a robot that has stopped.
            assertTrue(speeds.get(sample) <= speeds.get(sample - 1) + 5e-3,
                    "a free-rolling ball sped up between samples " + (sample - 1) + " and "
                            + sample + ": " + speeds.get(sample - 1) + " -> " + speeds.get(sample));
        }
        assertTrue(speeds.get(speeds.size() - 1) < 0.05,
                "four seconds on foam tiles should have stopped the ball, still doing "
                        + speeds.get(speeds.size() - 1) + " m/s");
    }

    @Test
    void aBallInTheRobotsPathIsNeverDrivenStraightThrough() {
        FakeHardwareMap hardware = hardware();
        hardware.drive().setPose(new Pose2d(-1.5, 0.0, 0.0));

        // A line of balls across the robot's path, so the robot meets them one after another at
        // full speed -- driven at the 160 ms control cycle a session running at 8x hands over,
        // because that is where this can actually go wrong. At 1.57 m/s the robot covers 251 mm in
        // one of those cycles, three and a half POLLEN diameters: a world stepped with the time it
        // is given arrives on the far side of a ball no contact was ever evaluated for, and the
        // ball is left sitting untouched behind it.
        List<GameElement> line = Arrays.asList(
                GameElement.pollen(-0.6, 0.0),
                GameElement.pollen(0.0, 0.02),
                GameElement.pollen(0.6, -0.02),
                GameElement.pollen(1.2, 0.0));
        FieldPhysics world = FieldPhysics.of(line, FieldConfig.standard(), hardware.drive());

        drive(hardware, world, 1.0, 2.56, 0.16);

        assertTrue(hardware.drive().pose().x() > 1.2,
                "the robot should have driven the length of the field, got "
                        + hardware.drive().pose().x());
        double tail = hardware.drive().pose().x() - robot.chassis().lengthMetres() / 2.0;
        List<BodyState> bodies = world.bodies();
        for (int id = 0; id < bodies.size(); id++) {
            BodyState ball = bodies.get(id);
            double startedAt = line.get(id).centre().x();
            assertTrue(ball.x() > startedAt + 0.02,
                    "the robot passed ball " + id + " without moving it: it started at "
                            + startedAt + " and is at " + ball.x());
            // The robot cannot have got past a ball it was pushing, so no ball may be behind it.
            // This is the assertion a solver stepped with the time it was handed fails: the chassis
            // jumps a quarter of a metre, lands beyond the ball, and leaves it in its wake.
            assertTrue(ball.x() > tail,
                    "ball " + id + " ended up behind the robot at " + ball.x()
                            + ", with the robot's tail at " + tail);
        }
    }

    @Test
    void aBallCrushedAgainstThePerimeterComesBackWhenTheRobotBacksOff() {
        FakeHardwareMap hardware = hardware();
        double half = FieldConfig.standard().halfExtentMetres();
        double radius = GameElement.POLLEN_DIAMETER_METRES / 2.0;
        hardware.drive().setPose(new Pose2d(half - 0.6, 0.0, 0.0));

        FieldPhysics world = FieldPhysics.of(
                Arrays.asList(GameElement.pollen(half - 0.1, 0.0)),
                FieldConfig.standard(), hardware.drive());

        // The drive model clamps the footprint at the wall, so the bumper reaches the perimeter
        // exactly and the ball has nowhere to be. Something has to give: the chassis is kinematic
        // and pushes with infinite authority, so the solver resolves the pinch by letting the ball
        // penetrate something by a few millimetres. That much is unavoidable in any soft-contact
        // solver and is not what this test is about.
        drive(hardware, world, 1.0, 3.0);

        // What matters is whether the penetration was transient or permanent. Backing off and
        // letting it settle separates the two: a ball that was merely squashed into the wall comes
        // back onto the field, while a ball that was squeezed through it stays outside forever.
        // With a 50 mm perimeter this ended up 1.7 m into the gym and never returned.
        drive(hardware, world, -1.0, 1.0);
        drive(hardware, world, 0.0, 2.0);

        BodyState ball = world.bodies().get(0);
        assertTrue(ball.x() < half - radius + 1e-3,
                "a ball crushed against the wall did not come back onto the field: x = "
                        + ball.x() + ", and a ball resting against the wall sits at "
                        + (half - radius));
        assertTrue(Math.abs(ball.y()) < half - radius,
                "a ball crushed against the wall left the field sideways: y = " + ball.y());
        assertTrue(ball.z() > radius * 0.5,
                "a ball crushed against the wall ended up under the floor: z = " + ball.z());
    }
}
