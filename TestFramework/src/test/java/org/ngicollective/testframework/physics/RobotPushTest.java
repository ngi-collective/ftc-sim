package org.ngicollective.testframework.physics;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
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

    /**
     * A robot placed on top of a ball.
     *
     * <p>Which is an ordinary thing to do: placing the robot is a teleport, and a teleport does
     * not ask what is in the way &mdash; a driver dragging the chassis across the field view in
     * the dashboard does this several times a minute.</p>
     *
     * <p>It used to be unsurvivable. ODE ejects a sphere inside a box through whichever face is
     * nearest, and for a ball on the floor inside a box that reaches the floor, that is the bottom:
     * the chassis drove the ball down, the floor drove it back up, and neither won. The ball
     * oscillated at about three metres a second while going nowhere, which this world reported as
     * motion on every tick for the rest of the session &mdash; a body frame fifty times a second,
     * the camera's scene rebuilt just as often, and a browser redrawing a field that was not
     * changing. It was found as a Chromium burning six cores, which is a long way from the cause.</p>
     */
    @Test
    void aRobotPlacedOnTopOfABallPushesItOutRatherThanFightingIt() {
        FakeHardwareMap hardware = hardware();
        double radius = GameElement.POLLEN_DIAMETER_METRES / 2.0;
        FieldPhysics world = FieldPhysics.of(
                Arrays.asList(GameElement.pollen(0.0, 0.0)),
                FieldConfig.standard(), hardware.drive());

        // Dead centre of the footprint, the worst case: every side face is equally far away.
        hardware.drive().setPose(Pose2d.ORIGIN);
        hardware.advance(TICK);
        world.advance(TICK);

        // The correction has to be reported, not just made. It is the largest single jump a ball
        // ever makes, and a world that moved it quietly would leave every browser drawing it under
        // the robot for as long as nothing else happened to move it.
        assertTrue(world.moving(),
                "moving a ball out from under the robot has to count as the ball moving");

        drive(hardware, world, 0.0, 2.0);

        BodyState ball = world.bodies().get(0);
        double halfLength = robot.chassis().lengthMetres() / 2.0;
        double halfWidth = robot.chassis().widthMetres() / 2.0;
        boolean clear = Math.abs(ball.x()) > halfLength || Math.abs(ball.y()) > halfWidth;

        assertTrue(clear, "the ball is still inside the robot's footprint at (" + ball.x() + ", "
                + ball.y() + ")");
        assertEquals(radius, ball.z(), 3e-3,
                "the ball should have been pushed out sideways and left on the floor, not driven "
                        + "into it");

        // And the world has to go quiet afterwards, because that is what the session depends on:
        // a field that never settles publishes a body frame on every tick forever.
        int restless = 0;
        for (int tick = 0; tick < 100; tick++) {
            hardware.advance(TICK);
            world.advance(TICK);
            if (world.moving()) {
                restless++;
            }
        }
        assertTrue(restless <= 5, "the world never settled: it reported motion on " + restless
                + " of 100 ticks after the ball had been pushed clear");
    }

    /**
     * A ball pinned between the perimeter and a robot that pushes with infinite authority.
     *
     * <p>The drive model clamps the footprint at the wall, so the bumper reaches the perimeter
     * exactly and the ball has nowhere to be. Something has to give, and what this holds the
     * simulation to is that it is never the far side of the wall: with a 50&nbsp;mm perimeter the
     * solver squeezed the ball straight through and it ended up 1.7&nbsp;m into the gym, which is
     * the bug the 0.5&nbsp;m walls exist to prevent.</p>
     *
     * <p>It deliberately does <em>not</em> assert that the ball comes all the way back onto the
     * field. It usually does &mdash; a crushed ball is typically ejected the moment the robot backs
     * off &mdash; but a pinch between two immovable surfaces is a chaotic problem, and an earlier
     * version of this test that asserted full recovery failed about one run in six on nothing but
     * the last digits of a sine. The residue is a few centimetres of a ball left inside the
     * perimeter, and it exists because the chassis is kinematic: it can press with unbounded force
     * and no real robot can. Making the chassis dynamic is what fixes it, and asserting it here
     * would only mean re-running the suite until the physics agreed.</p>
     */
    @Test
    void aBallCrushedAgainstThePerimeterIsNeverPushedThroughIt() {
        FakeHardwareMap hardware = hardware();
        double half = FieldConfig.standard().halfExtentMetres();
        double radius = GameElement.POLLEN_DIAMETER_METRES / 2.0;
        hardware.drive().setPose(new Pose2d(half - 0.6, 0.0, 0.0));

        FieldPhysics world = FieldPhysics.of(
                Arrays.asList(GameElement.pollen(half - 0.1, 0.0)),
                FieldConfig.standard(), hardware.drive());

        drive(hardware, world, 1.0, 3.0);
        drive(hardware, world, -1.0, 1.0);
        drive(hardware, world, 0.0, 2.0);

        BodyState ball = world.bodies().get(0);
        // Inside the wall's own thickness at worst, and on the field side of its outer face.
        assertTrue(ball.x() < half + WALL_DEPTH_METRES,
                "a ball crushed against the wall was pushed through it: x = " + ball.x());
        assertTrue(Math.abs(ball.y()) < half + WALL_DEPTH_METRES,
                "a ball crushed against the wall was pushed through it sideways: y = " + ball.y());
        // And never below the floor, which is a slab for exactly this reason: an infinitely thin
        // floor let the same pinch eject a ball downwards, and nothing could push it back.
        assertTrue(ball.z() > 0.0,
                "a ball crushed against the wall ended up under the floor: z = " + ball.z());
    }

    /**
     * How deep the physics world's perimeter boxes are, mirrored from {@code OdeFieldPhysics}.
     *
     * <p>Duplicated rather than exposed: it is an implementation detail of how the perimeter is
     * built, and a world that published its own collision dimensions would invite a test to assert
     * them rather than assert behaviour.</p>
     */
    private static final double WALL_DEPTH_METRES = 0.5;
}
