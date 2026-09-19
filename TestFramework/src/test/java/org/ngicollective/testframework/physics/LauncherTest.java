package org.ngicollective.testframework.physics;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.ngicollective.testframework.behavior.ImuBehaviors;
import org.ngicollective.testframework.behavior.MotorBehaviors;
import org.ngicollective.testframework.camera.GameElement;
import org.ngicollective.testframework.camera.Pose3d;
import org.ngicollective.testframework.camera.Structure;
import org.ngicollective.testframework.camera.Vec3;
import org.ngicollective.testframework.hardware.FakeHardwareMap;
import org.ngicollective.testframework.season.BioBuzzField;
import org.ngicollective.testframework.season.BioBuzzHive;
import org.ngicollective.testframework.season.BioBuzzScore;
import org.ngicollective.testframework.sim.FieldConfig;
import org.ngicollective.testframework.sim.Pose2d;
import org.ngicollective.testframework.sim.RobotConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * What a flywheel does to a ball.
 *
 * <p>The launcher is a surface like the intake is a surface: a box bolted to the chassis with a
 * wheel in it, and anything inside it while the wheel is spinning leaves at the speed that wheel
 * can give it. So these tests are about a ball being <em>thrown</em> &mdash; how fast, how far, and
 * into what &mdash; and about the one mistake this exists to make possible, which is firing before
 * the wheel is up to speed.</p>
 *
 * <p>The robots here are described by files these tests write. TeamCode's configuration is not on
 * this module's path, and pinning a ballistic assertion to the competition robot would mean that
 * re-aiming its launcher next week breaks a test about momentum.</p>
 */
class LauncherTest {

    private static final double TICK = 0.02;

    /** A 4 in compliant wheel on a bare 5203: the numbers the fixtures below are built from. */
    private static final double WHEEL_RADIUS_METRES = 0.0508;
    private static final double FREE_RPM = 6000.0;
    private static final double TICKS_PER_REVOLUTION = 28.0;
    private static final double TRANSFER_EFFICIENCY = 0.5;
    private static final double SPIN_UP_SECONDS = 1.2;

    /**
     * Surface speed of the wheel at full power, in metres/second: nearly 32, which is why a useful
     * shot is taken at a third of the stick rather than at the top of it.
     */
    private static final double FULL_SURFACE_METRES_PER_SECOND =
            FREE_RPM / 60.0 * 2.0 * Math.PI * WHEEL_RADIUS_METRES;

    /** And what a ball leaves at, which is the model this file is mostly about. */
    private static final double FULL_EXIT_METRES_PER_SECOND =
            FULL_SURFACE_METRES_PER_SECOND * TRANSFER_EFFICIENCY;

    /**
     * How steeply the fixture robot is aimed.
     *
     * <p>Steep because the field forces it. The raised CELL's mouth is 1.5&nbsp;m above the tiles
     * and only 0.3&nbsp;m from the field's centre line, while a legal robot cannot get its nose
     * further back than about 1.4&nbsp;m from it &mdash; so the straight line to the target is
     * already close to 60&deg;, and anything flatter than that cannot reach the mouth at all. A
     * ball has to arrive past the top of its arc as well: the basket's back panel returns a third
     * of whatever hits it, so a flat hard shot bounces straight back out of the opening it came
     * in through. Both of those push the same way, and 75&deg; is what is left.</p>
     */
    private static final double EXIT_PITCH_DEGREES = 75.0;

    /** A chassis with a flywheel across its nose, lobbing. */
    private static final String SLINGER_JSON = robotJson("Slinger", 14.0, EXIT_PITCH_DEGREES);

    /**
     * The same launcher on a 3 kg chassis, aimed flat.
     *
     * <p>For the one law that is invisible on a competition robot. Recoil is an impulse divided by
     * the robot's mass while the wheels resist it at {@code mu * g} regardless of mass, so a 14 kg
     * robot swallows a POLLEN's momentum inside a single solver step and a light one does not. The
     * law is the same either way, and this is the robot it can be measured on.</p>
     */
    private static final String PEASHOOTER_JSON = robotJson("Peashooter", 3.0, 0.0);

    @TempDir
    Path configDirectory;

    private static String robotJson(String name, double massKilograms, double exitPitchDegrees) {
        return "{\n"
                + "  \"version\": 1,\n"
                + "  \"name\": \"" + name + "\",\n"
                + "  \"chassis\": { \"widthMetres\": 0.38, \"lengthMetres\": 0.40,"
                + " \"heightMetres\": 0.05, \"deckHeightMetres\": 0.105,"
                + " \"massKilograms\": " + massKilograms + " },\n"
                + "  \"drivetrain\": { \"type\": \"mecanum\", \"wheelRadiusMetres\": 0.048,"
                + " \"gearRatio\": 1.0, \"trackWidthMetres\": 0.32, \"wheelBaseMetres\": 0.29,"
                + " \"strafeEfficiency\": 0.8, \"gripCoefficient\": 0.9 },\n"
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
                + " \"ticksPerRevolution\": 537.7 },\n"
                + "    \"flywheel\": { \"role\": \"launcher\", \"mirrored\": false,"
                + " \"rpm\": " + FREE_RPM + ", \"ticksPerRevolution\": " + TICKS_PER_REVOLUTION
                + " }\n"
                + "  },\n"
                + "  \"launchers\": {\n"
                + "    \"flywheel\": { \"wheelRadiusMetres\": " + WHEEL_RADIUS_METRES + ","
                + " \"transferEfficiency\": " + TRANSFER_EFFICIENCY + ","
                + " \"spinUpSeconds\": " + SPIN_UP_SECONDS + ","
                + " \"exitYawDegrees\": 0.0, \"exitPitchDegrees\": " + exitPitchDegrees + ",\n"
                + "      \"mouth\": { \"forwardMetres\": 0.24, \"leftMetres\": 0.0,"
                + " \"heightMetres\": 0.06, \"lengthMetres\": 0.06, \"widthMetres\": 0.20,"
                + " \"tallMetres\": 0.12 } }\n"
                + "  }\n"
                + "}\n";
    }

    private RobotConfig robot(String json) throws IOException {
        Path file = configDirectory.resolve("launcher.json");
        Files.write(file, json.getBytes(UTF_8));
        return RobotConfig.load(file);
    }

    private FakeHardwareMap hardware(RobotConfig robot) {
        FakeHardwareMap hardware = FakeHardwareMap.builder()
                .addMotor("FL").addMotor("FR").addMotor("BL").addMotor("BR")
                // Ramping, which is what VerityRobot installs from spinUpSeconds. An ideal motor
                // here would reach full speed between two ticks and make every assertion below
                // about spin-up vacuous.
                .addMotor("flywheel", MotorBehaviors.ramping(SPIN_UP_SECONDS))
                .addImu("imu", ImuBehaviors.followingChassis())
                .withDrivetrain(robot, FieldConfig.standard())
                .withMechanisms(robot)
                .build();
        for (String wheel : new String[] {"FL", "FR", "BL", "BR"}) {
            hardware.motor(wheel).state().setMaxSpeed(312.0, 537.7);
        }
        hardware.motor("flywheel").state().setMaxSpeed(FREE_RPM, TICKS_PER_REVOLUTION);
        return hardware;
    }

    private static void run(FakeHardwareMap hardware, double seconds) {
        for (int tick = 0; tick < Math.round(seconds / TICK); tick++) {
            hardware.advance(TICK);
        }
    }

    /**
     * Puts the robot's mouth around a ball with the flywheel already at speed.
     *
     * <p>Two steps because on this robot they cannot be one. The mouth is where the wheel is, so a
     * ball in it goes the moment the wheel bites; a driver who wants a full-speed shot therefore
     * spins up first and collects second, and a test that wants one has to do the same. The robot
     * spins up a metre away and is then placed over the ball, which is the teleport a dashboard
     * session does and the shortest honest stand-in for driving up to it.</p>
     */
    private FieldPhysics spunUpOver(FakeHardwareMap hardware, double power, double ballX,
                                    double ballY, List<GameElement> balls,
                                    List<Structure> structures, RobotConfig robot) {
        hardware.drive().setPose(new Pose2d(ballX, ballY - 1.24, Math.PI / 2.0));
        FieldPhysics world = FieldPhysics.of(balls, structures, FieldConfig.standard(), robot);
        hardware.setPhysics(world);

        hardware.motor("flywheel").setPower(power);
        run(hardware, 2.0);
        hardware.drive().setPose(new Pose2d(ballX, ballY - 0.24, Math.PI / 2.0));
        return world;
    }

    /** Metres/second across the floor, measured over one tick of flight. */
    private static double horizontalSpeed(FakeHardwareMap hardware, FieldPhysics world) {
        BodyState before = world.bodies().get(0);
        hardware.advance(TICK);
        BodyState after = world.bodies().get(0);
        double awayX = after.x() - before.x();
        double awayY = after.y() - before.y();
        return Math.sqrt(awayX * awayX + awayY * awayY) / TICK;
    }

    @Test
    void anIdleFlywheelLeavesABallWhereItIs() throws IOException {
        RobotConfig robot = robot(SLINGER_JSON);
        FakeHardwareMap hardware = hardware(robot);
        hardware.drive().setPose(new Pose2d(0.0, -0.24, Math.PI / 2.0));
        FieldPhysics world = FieldPhysics.of(
                Arrays.asList(GameElement.pollen(0.0, 0.0)),
                Collections.<Structure>emptyList(), FieldConfig.standard(), robot);
        hardware.setPhysics(world);

        run(hardware, 1.0);

        // Not merely "did not fly": a launcher that set velocities unconditionally would pin this
        // ball at a standstill in the mouth, which looks identical to leaving it alone until you
        // notice the one at the top of an arc that stops in mid-air.
        assertEquals(0.0, world.bodies().get(0).x(), 1e-3, "an idle launcher moved a ball sideways");
        assertEquals(0.0, world.bodies().get(0).y(), 1e-3, "an idle launcher moved a ball");
        assertEquals(GameElement.POLLEN_DIAMETER_METRES / 2.0, world.bodies().get(0).z(), 1e-3,
                "an idle launcher lifted a ball off the floor");
    }

    @Test
    void aFlywheelAtSpeedThrowsABallAtTheSpeedItsSurfaceIsRunning() throws IOException {
        RobotConfig robot = robot(SLINGER_JSON);
        FakeHardwareMap hardware = hardware(robot);
        FieldPhysics world = spunUpOver(hardware, 1.0, 0.0, 0.0,
                Arrays.asList(GameElement.pollen(0.0, 0.0)),
                Collections.<Structure>emptyList(), robot);

        // Horizontal rather than total speed, because gravity is already working on the vertical
        // component by the time anything can be measured and the cosine of the pitch is not.
        double expected = FULL_EXIT_METRES_PER_SECOND
                * Math.cos(Math.toRadians(EXIT_PITCH_DEGREES));
        assertEquals(expected, horizontalSpeed(hardware, world), expected * 0.05,
                "a ball should leave at the wheel's surface speed times what the mechanism"
                        + " transfers, aimed where the launcher points");
    }

    /**
     * The mistake the whole seam exists to allow.
     *
     * <p>Not a special case anywhere in the model: the ball is given whatever the wheel has on each
     * step it is still in the mouth, so a ball fed to a wheel at 2% of its speed creeps out of the
     * mouth at walking pace and is gone before the wheel is anywhere near ready. That is what
     * happens on a real robot, and the only thing that makes it happen here is reading the
     * simulated shaft instead of the commanded power.</p>
     */
    @Test
    void aFlywheelStillSpinningUpThrowsShort() throws IOException {
        RobotConfig robot = robot(SLINGER_JSON);
        FakeHardwareMap hardware = hardware(robot);
        hardware.drive().setPose(new Pose2d(0.0, -0.24, Math.PI / 2.0));
        FieldPhysics world = FieldPhysics.of(
                Arrays.asList(GameElement.pollen(0.0, 0.0)),
                Collections.<Structure>emptyList(), FieldConfig.standard(), robot);
        hardware.setPhysics(world);

        // Full power and no patience: commanded at the same instant the ball is already in there.
        hardware.motor("flywheel").setPower(1.0);
        run(hardware, 0.3);

        double thrown = horizontalSpeed(hardware, world);
        double full = FULL_EXIT_METRES_PER_SECOND * Math.cos(Math.toRadians(EXIT_PITCH_DEGREES));
        assertTrue(thrown > 0.05,
                "the ball should still have been nudged out of the mouth, but it moved at "
                        + thrown + " m/s");
        assertTrue(thrown < full / 4.0,
                "a shot fired during spin-up should fall far short of " + full + " m/s, but it"
                        + " left at " + thrown + " m/s");
    }

    /**
     * Newton, on a robot light enough to show it.
     *
     * <p>The roller went a year without this and nothing noticed, because a mechanism that pushes a
     * ball without being pushed back looks exactly like one that works. A flywheel throws far more
     * momentum than a roller ever drags, so this is the version of that bug that would matter.</p>
     */
    @Test
    void aLightRobotIsPushedBackByTheBallItThrows() throws IOException {
        RobotConfig robot = robot(PEASHOOTER_JSON);
        FakeHardwareMap hardware = hardware(robot);
        // A NECTAR, which is twice a POLLEN's mass, thrown flat: the most momentum this robot can
        // put into the floor's own plane, which is the only plane its chassis can move in.
        FieldPhysics world = spunUpOver(hardware, 1.0, 0.0, 0.0,
                Arrays.asList(GameElement.redNectar(0.0, 0.0)),
                Collections.<Structure>emptyList(), robot);

        double stoodAt = hardware.drive().pose().y();
        run(hardware, 0.1);

        double recoiled = stoodAt - hardware.drive().pose().y();
        assertTrue(recoiled > 5e-4,
                "throwing a ball forwards should have pushed a 3 kg robot backwards; it moved "
                        + recoiled * 1000.0 + " mm");
        assertTrue(recoiled < 0.05,
                "and its own wheels should have stopped it almost at once, not let it roll "
                        + recoiled + " m");
    }

    /**
     * The whole point: a shot that scores.
     *
     * <p>End to end through every part of this &mdash; the encoder's ticks, the wheel's radius, the
     * transfer, the aim, an unpowered arc under gravity, a basket three and a half feet up in the
     * middle of the field, and the score that reads what settled in it. Nothing in it knows that a
     * shot is happening.</p>
     *
     * <p>The power is calculated, not tuned. The ball lies 0.86&nbsp;m short of the red audience
     * CELL's mouth and 1.47&nbsp;m below it, and
     * {@code rise = x tan(p) - g x^2 / (2 v^2 cos^2(p))} at {@code p = 75} wants
     * {@code v = 5.59}&nbsp;m/s, which is 35% of what this flywheel can give. Sweeping the power
     * in 0.005 steps says every setting from 0.325 to 0.360 scores, so the arithmetic lands in
     * the middle of the band rather than on its edge &mdash; and the band's width is the honest
     * answer to "how precisely must an OpMode hold its RPM", which is to about 5%.</p>
     *
     * <p>That calculation is the one an OpMode will have to do from a tag detection, which is why
     * this test does it rather than looking a number up.</p>
     */
    @Test
    void aBallLaunchedFromTheAudienceSideLandsInTheRaisedCell() throws IOException {
        RobotConfig robot = robot(SLINGER_JSON);
        FakeHardwareMap hardware = hardware(robot);

        BioBuzzField.HiveTip red = BioBuzzField.HiveTip.AUDIENCE_UP;
        BioBuzzField.HiveTip blue = BioBuzzField.HiveTip.AUDIENCE_DOWN;

        // Square on to the red HIVE, which hangs on the -X side of the field's centre line, and
        // shooting along +Y at the CELL whose mouth faces the audience.
        double shotFrom = -BioBuzzHive.HIVE_OFFSET_METRES;
        FieldPhysics world = spunUpOver(hardware, 0.35, shotFrom, -1.26,
                Arrays.asList(GameElement.pollen(shotFrom, -1.26)),
                BioBuzzHive.all(red, blue), robot);

        run(hardware, 3.0);

        BioBuzzScore score = BioBuzzScore.of(BioBuzzHive.cellVolumes(red, blue),
                world.elements(), PivotState.swingsOf(world.pivots()));
        assertEquals(2, score.redPoints(),
                "the POLLEN should have landed in the raised red CELL; it ended up at "
                        + world.elements().get(0).centre() + " (" + score + ")");
    }

    /**
     * And that LAUNCHING is what tips a HIVE, which is the point of the whole mechanism.
     *
     * <p>Manual G417: "LAUNCHING into the upward-facing CELL is the only allowed way to earn a
     * HIVE TIP." This is that sentence end to end &mdash; a flywheel at a computed power, a ball
     * through a mouth, a basket, a hinge, and twenty points &mdash; with nothing anywhere deciding
     * that a tip has happened.</p>
     *
     * <p>A burst of three, on top of the three NECTAR a MATCH stages in the CELL (&sect;10.3.1).
     * The mouth is the wheel, so three balls sitting in it leave together; a queue fed one at a
     * time is what an intake is for and what {@code LauncherTeleOp} does with a driver behind it.
     * The staged NECTAR matter: they are 0.34 of the 0.6&nbsp;N&nbsp;m holding the HIVE, so what
     * arrives has only the rest to beat, and that is the position a real match starts from.</p>
     */
    @Test
    void aBurstIntoAStagedCellTipsTheHiveForTwenty() throws IOException {
        RobotConfig robot = robot(SLINGER_JSON);
        FakeHardwareMap hardware = hardware(robot);

        BioBuzzField.HiveTip red = BioBuzzField.HiveTip.AUDIENCE_UP;
        BioBuzzField.HiveTip blue = BioBuzzField.HiveTip.AUDIENCE_DOWN;
        double shotFrom = -BioBuzzHive.HIVE_OFFSET_METRES;

        List<GameElement> balls = new ArrayList<>();
        // The match's own staging, spread across the CELL's width the way a scenario file does.
        Pose3d cell = BioBuzzHive.cell(BioBuzzField.RED_AUDIENCE, red, blue);
        for (int staged = 0; staged < 3; staged++) {
            int step = (staged + 1) / 2 * (staged % 2 == 0 ? -1 : 1);
            Vec3 at = cell.position()
                    .plus(cell.left().scaled(step * GameElement.NECTAR_DIAMETER_METRES));
            balls.add(GameElement.redNectarAt(at.x(), at.y(), at.z()));
        }
        // And three POLLEN in the mouth, across its 0.20 m width.
        for (int loaded = -1; loaded <= 1; loaded++) {
            balls.add(GameElement.pollen(shotFrom + loaded * 0.075, -1.26));
        }

        FieldPhysics world = spunUpOver(hardware, 0.35, shotFrom, -1.26, balls,
                BioBuzzHive.all(red, blue), robot);
        run(hardware, 3.0);

        PivotState hive = world.pivots().get(0);
        assertEquals(BioBuzzHive.RED, hive.structureName());
        assertEquals(1, hive.completedSwings(),
                "the burst should have tipped the red HIVE; it is at "
                        + Math.toDegrees(hive.angleRadians()) + " degrees");

        // Scored against the CELLs where they are now, which after a tip is not where they were.
        BioBuzzScore score = BioBuzzScore.of(
                BioBuzzField.scene(red, blue).tipped(PivotState.anglesOf(world.pivots()))
                        .scoringVolumes(),
                world.elements(), PivotState.swingsOf(world.pivots()));
        assertEquals(BioBuzzScore.POINTS_PER_TIP, score.redPoints(),
                "twenty for the TIP, and the basket that earned it has emptied itself: " + score);
    }
}
