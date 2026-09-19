package org.ngicollective.testframework.physics;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.ngicollective.testframework.behavior.ImuBehaviors;
import org.ngicollective.testframework.camera.GameElement;
import org.ngicollective.testframework.camera.Structure;
import org.ngicollective.testframework.hardware.FakeHardwareMap;
import org.ngicollective.testframework.sim.FieldConfig;
import org.ngicollective.testframework.sim.Pose2d;
import org.ngicollective.testframework.sim.RobotConfig;
import org.ngicollective.testframework.sim.SensorConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * What an intake does to a ball, and what the robot's sensors make of it.
 *
 * <p>The intake is a surface, not a rule: a box bolted to the chassis whose skin runs backwards
 * while the servo does. So the assertions here are about a ball being <em>carried</em> &mdash; it
 * arrives, it stays while the roller runs, it leaves when the roller reverses &mdash; rather than
 * about a flag being set. Nothing in the simulation knows the word "captured", and that is the
 * point: an OpMode that can lose a ball on a bump is one whose driver finds out in practice
 * instead of in a match.</p>
 */
class IntakeTest {

    private static final double TICK = 0.02;

    /** A chassis with an intake across its nose, and every sensor pointed into that mouth. */
    private static final String ROBOT_JSON = "{\n"
            + "  \"version\": 1,\n"
            + "  \"name\": \"Sweeper\",\n"
            + "  \"chassis\": { \"widthMetres\": 0.38, \"lengthMetres\": 0.40,"
            + " \"heightMetres\": 0.05, \"deckHeightMetres\": 0.105,"
            + " \"massKilograms\": 14.0 },\n"
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
            + " \"ticksPerRevolution\": 537.7 }\n"
            + "  },\n"
            + "  \"servos\": {\n"
            + "    \"intake\": { \"type\": \"continuous\", \"surfaceMetresPerSecond\": 1.4,\n"
            + "      \"sweep\": { \"forwardMetres\": 0.24, \"leftMetres\": 0.0,"
            + " \"heightMetres\": 0.085, \"lengthMetres\": 0.06, \"widthMetres\": 0.28,"
            + " \"tallMetres\": 0.07 } }\n"
            + "  },\n"
            + "  \"sensors\": {\n"
            + "    \"intakeTouch\": { \"type\": \"touch\",\n"
            + "      \"volume\": { \"forwardMetres\": 0.235, \"leftMetres\": 0.0,"
            + " \"heightMetres\": 0.045, \"lengthMetres\": 0.10, \"widthMetres\": 0.28,"
            + " \"tallMetres\": 0.09 } },\n"
            + "    \"intakeColor\": { \"type\": \"color\",\n"
            + "      \"volume\": { \"forwardMetres\": 0.235, \"leftMetres\": 0.0,"
            + " \"heightMetres\": 0.045, \"lengthMetres\": 0.10, \"widthMetres\": 0.28,"
            + " \"tallMetres\": 0.09 } },\n"
            + "    \"frontRange\": { \"type\": \"distance\", \"forwardMetres\": 0.20,"
            + " \"leftMetres\": 0.0, \"heightMetres\": 0.05, \"yawDegrees\": 0.0,"
            + " \"pitchDegrees\": 0.0, \"maxRangeMetres\": 2.0 },\n"
            + "    \"battery\": { \"type\": \"voltage\" }\n"
            + "  }\n"
            + "}\n";

    @TempDir
    Path configDirectory;

    private RobotConfig robot;

    @BeforeEach
    void readTheRobot() throws IOException {
        Path file = configDirectory.resolve("sweeper.json");
        Files.write(file, ROBOT_JSON.getBytes(UTF_8));
        robot = RobotConfig.load(file);
    }

    private FakeHardwareMap hardware() {
        FakeHardwareMap hardware = FakeHardwareMap.builder()
                .addMotor("FL").addMotor("FR").addMotor("BL").addMotor("BR")
                .addImu("imu", ImuBehaviors.followingChassis())
                .addCRServo("intake")
                .addTouchSensor("intakeTouch")
                .addColorSensor("intakeColor")
                .addDistanceSensor("frontRange")
                .addVoltageSensor("battery")
                .withDrivetrain(robot, FieldConfig.standard())
                .withMechanisms(robot)
                .build();
        for (String wheel : new String[] {"FL", "FR", "BL", "BR"}) {
            hardware.motor(wheel).state().setMaxSpeed(312.0, 537.7);
        }
        return hardware;
    }

    /** A world of one POLLEN ball, with the robot's intake pointed at it. */
    private FieldPhysics worldWith(FakeHardwareMap hardware, GameElement... balls) {
        FieldPhysics physics = FieldPhysics.of(Arrays.asList(balls),
                Collections.<Structure>emptyList(), FieldConfig.standard(), robot);
        hardware.setPhysics(physics);
        return physics;
    }

    private void run(FakeHardwareMap hardware, double seconds) {
        for (int tick = 0; tick < Math.round(seconds / TICK); tick++) {
            hardware.advance(TICK);
        }
    }

    private void drive(FakeHardwareMap hardware, double power) {
        for (String wheel : new String[] {"FL", "FR", "BL", "BR"}) {
            hardware.motor(wheel).setPower(power);
        }
    }

    @Test
    void aRunningIntakeDragsABallInWithoutTheRobotMoving() {
        FakeHardwareMap hardware = hardware();
        hardware.drive().setPose(Pose2d.ORIGIN);
        // Just touching the mouth, which spans 0.19 m to 0.27 m ahead of centre.
        FieldPhysics world = worldWith(hardware, GameElement.pollen(0.26, 0.0));
        double startedAt = world.bodies().get(0).x();

        hardware.get(com.qualcomm.robotcore.hardware.CRServo.class, "intake").setPower(1.0);
        run(hardware, 1.0);

        // The wheels never turned, so anything that moved was moved by the roller's surface.
        //
        // A millimetre rather than nothing at all: the roller now pushes back on the robot it is
        // bolted to, so a light chassis with its brakes off does shift a little while dragging a
        // ball in. Most of that force pair cancels once the ball is seated against the bumper --
        // the roller pulls the ball back, the ball leans on the chassis -- which is why what is
        // left is tens of microns. What this rules out is the robot driving.
        assertEquals(0.0, hardware.drive().pose().x(), 1e-3,
                "this test is about the intake, so the robot must not have driven");
        assertTrue(world.bodies().get(0).x() < startedAt - 0.01,
                "a running intake should have pulled the ball towards the robot; it went from "
                        + startedAt + " to " + world.bodies().get(0).x());
    }

    @Test
    void aBallSweptUpWhileDrivingTravelsWithTheRobot() {
        FakeHardwareMap hardware = hardware();
        hardware.drive().setPose(Pose2d.ORIGIN);
        FieldPhysics world = worldWith(hardware, GameElement.pollen(0.8, 0.0));

        // Driving at a ball with the intake running is the ordinary way a driver picks one up, and
        // it is a different problem from picking up a ball while parked: the roller's surface has to
        // beat the closing speed, or the chassis simply shoves the ball down the field. That is
        // what the first version of this did, and no parked test noticed.
        hardware.get(com.qualcomm.robotcore.hardware.CRServo.class, "intake").setPower(1.0);
        drive(hardware, 0.35);
        run(hardware, 1.2);
        drive(hardware, 0.0);
        run(hardware, 0.4);

        double heldAt = world.bodies().get(0).x() - hardware.drive().pose().x();
        assertTrue(hardware.touchSensor.get("intakeTouch").isPressed(),
                "after driving into a ball with the intake running it should be in the mouth; it is "
                        + heldAt + " m ahead of the robot's centre");

        // And it stays there while the robot keeps going: carried, not left behind or pushed away.
        drive(hardware, 0.35);
        run(hardware, 0.8);
        double stillHeldAt = world.bodies().get(0).x() - hardware.drive().pose().x();
        assertEquals(heldAt, stillHeldAt, 0.03,
                "a held ball should travel with the robot, but it moved from " + heldAt + " to "
                        + stillHeldAt + " m ahead of centre");
        assertTrue(hardware.touchSensor.get("intakeTouch").isPressed(),
                "the sensor should still see the ball it is carrying");
    }

    /**
     * The bug a dashboard session showed and no test did: a ball held in a running intake has a
     * velocity forever and goes nowhere, so a world that called that "moving" published fifty
     * identical body frames a second for as long as a driver held the trigger &mdash; and had the
     * browser interpolating and redrawing a field that was not changing.
     */
    @Test
    void aBallHeldInARunningIntakeStopsBeingReportedAsMoving() {
        FakeHardwareMap hardware = hardware();
        hardware.drive().setPose(Pose2d.ORIGIN);
        FieldPhysics world = worldWith(hardware, GameElement.pollen(0.30, 0.0));

        hardware.get(com.qualcomm.robotcore.hardware.CRServo.class, "intake").setPower(1.0);
        run(hardware, 2.0);

        // The trigger is still held, and the ball is still in the mouth: what has stopped is the
        // ball going anywhere.
        assertTrue(hardware.touchSensor.get("intakeTouch").isPressed(),
                "the ball should still be held, or this asserts nothing");
        int reported = 0;
        for (int tick = 0; tick < 50; tick++) {
            hardware.advance(TICK);
            if (world.moving()) {
                reported++;
            }
        }
        assertTrue(reported <= 2,
                "a held ball that is going nowhere was reported as moving on " + reported
                        + " of 50 ticks");
    }

    /**
     * A roller reaches a ball through friction, so it can only pull as hard as it can grip. The
     * first version pulled with whatever it took to reach surface speed in one solver step, which
     * a seated ball resists forever: balls ended up two centimetres into the floor, and one was
     * forced inside the robot's own footprint.
     */
    @Test
    void aBallHeldAgainstTheChassisIsNotCrushedIntoTheFloorOrTheRobot() {
        FakeHardwareMap hardware = hardware();
        hardware.drive().setPose(Pose2d.ORIGIN);
        double radius = GameElement.POLLEN_DIAMETER_METRES / 2.0;
        FieldPhysics world = worldWith(hardware, GameElement.pollen(0.30, 0.0));

        hardware.get(com.qualcomm.robotcore.hardware.CRServo.class, "intake").setPower(1.0);
        run(hardware, 5.0);

        BodyState ball = world.bodies().get(0);
        assertEquals(radius, ball.z(), 2e-3,
                "a held ball should still be resting on the floor, not pressed into it");
        assertTrue(ball.x() > robot.chassis().lengthMetres() / 2.0,
                "a held ball should be in front of the bumper, not inside the robot: it is at "
                        + ball.x() + " and the bumper is at "
                        + robot.chassis().lengthMetres() / 2.0);
    }

    @Test
    void aReversedIntakeSpitsTheBallBackOut() {
        FakeHardwareMap hardware = hardware();
        hardware.drive().setPose(Pose2d.ORIGIN);
        FieldPhysics world = worldWith(hardware, GameElement.pollen(0.26, 0.0));

        hardware.get(com.qualcomm.robotcore.hardware.CRServo.class, "intake").setPower(1.0);
        run(hardware, 1.0);
        double heldAt = world.bodies().get(0).x();
        hardware.get(com.qualcomm.robotcore.hardware.CRServo.class, "intake").setPower(-1.0);
        run(hardware, 1.0);

        // The same surface, running the other way. Nothing had to be told it was holding a ball,
        // which is why it cannot forget that it was.
        assertTrue(world.bodies().get(0).x() > heldAt + 0.01,
                "reversing the intake should have pushed the ball away; it went from " + heldAt
                        + " to " + world.bodies().get(0).x());
    }

    @Test
    void theTouchSensorReportsABallTheIntakeIsHolding() {
        FakeHardwareMap hardware = hardware();
        hardware.drive().setPose(Pose2d.ORIGIN);
        worldWith(hardware, GameElement.pollen(0.23, 0.0));

        run(hardware, 0.1);

        assertTrue(hardware.touchSensor.get("intakeTouch").isPressed(),
                "a ball sitting in the intake's mouth should press the sensor watching it");
    }

    @Test
    void theTouchSensorSaysNothingWhenTheMouthIsEmpty() {
        FakeHardwareMap hardware = hardware();
        hardware.drive().setPose(Pose2d.ORIGIN);
        // A metre away: in front of the robot, nowhere near the intake.
        worldWith(hardware, GameElement.pollen(1.2, 0.0));

        run(hardware, 0.1);

        assertFalse(hardware.touchSensor.get("intakeTouch").isPressed(),
                "a ball a metre away must not press a sensor watching the intake");
    }

    @Test
    void theColorSensorTellsPollenFromNectar() {
        FakeHardwareMap hardware = hardware();
        hardware.drive().setPose(Pose2d.ORIGIN);
        worldWith(hardware, GameElement.blueNectar(0.23, 0.0));

        run(hardware, 0.1);

        // BLUE NECTAR is rgb(30, 70, 200). A sensor that reported a configured colour, or the
        // first element in the arrangement, would pass a test that asserted "some colour" -- so
        // what is asserted is that blue dominates, which only the ball in the mouth can produce.
        com.qualcomm.robotcore.hardware.ColorSensor seen = hardware.colorSensor.get("intakeColor");
        assertTrue(seen.blue() > seen.red() && seen.blue() > seen.green(),
                "a blue NECTAR in the mouth should read blue, got rgb(" + seen.red() + ", "
                        + seen.green() + ", " + seen.blue() + ")");
    }

    @Test
    void theColorSensorReadsTheFloorWhenThereIsNothingToSee() {
        FakeHardwareMap hardware = hardware();
        hardware.drive().setPose(Pose2d.ORIGIN);
        worldWith(hardware);

        run(hardware, 0.1);

        // Grey, not black. An OpMode that waited for "anything but black" would sail through a
        // test against an empty intake and then trigger on the foam tile in its first match.
        com.qualcomm.robotcore.hardware.ColorSensor seen = hardware.colorSensor.get("intakeColor");
        assertTrue(seen.red() > 0 && seen.red() == seen.blue() && seen.red() == seen.green(),
                "an empty mouth should read the floor, got rgb(" + seen.red() + ", "
                        + seen.green() + ", " + seen.blue() + ")");
    }

    @Test
    void theDistanceSensorMeasuresTheBallInFrontOfIt() {
        FakeHardwareMap hardware = hardware();
        hardware.drive().setPose(Pose2d.ORIGIN);
        double radius = GameElement.POLLEN_DIAMETER_METRES / 2.0;
        worldWith(hardware, GameElement.pollen(0.9, 0.0));

        run(hardware, 0.1);

        // The lens is 0.20 m out and the ball's near surface is at 0.9 - radius, so the beam
        // crosses 0.665 m of air. A centre-to-centre measurement would read 0.70, and a sensor
        // that reported the range would read 2.0.
        double expected = 0.9 - radius - 0.20;
        double measured = hardware.get(com.qualcomm.robotcore.hardware.DistanceSensor.class,
                "frontRange").getDistance(
                        org.firstinspires.ftc.robotcore.external.navigation.DistanceUnit.METER);
        assertEquals(expected, measured, 0.02,
                "the beam should stop at the ball's surface");
    }

    @Test
    void theDistanceSensorReadsOutOfRangeWithNothingInFrontOfIt() {
        FakeHardwareMap hardware = hardware();
        // Facing the middle of an empty field from a metre off centre: the nearest wall is 2.6 m
        // away, past the sensor's 2 m range.
        hardware.drive().setPose(new Pose2d(-1.5, 0.0, 0.0));
        worldWith(hardware);

        run(hardware, 0.1);

        double measured = hardware.get(com.qualcomm.robotcore.hardware.DistanceSensor.class,
                "frontRange").getDistance(
                        org.firstinspires.ftc.robotcore.external.navigation.DistanceUnit.METER);
        assertTrue(Double.isNaN(measured),
                "nothing within range should read NaN, the way the SDK's own sensors do; got "
                        + measured);
    }

    @Test
    void theDistanceSensorSeesTheWallItIsDrivenTowards() {
        FakeHardwareMap hardware = hardware();
        double half = FieldConfig.standard().halfExtentMetres();
        hardware.drive().setPose(new Pose2d(half - 1.0, 0.0, 0.0));
        worldWith(hardware);

        run(hardware, 0.1);

        double measured = hardware.get(com.qualcomm.robotcore.hardware.DistanceSensor.class,
                "frontRange").getDistance(
                        org.firstinspires.ftc.robotcore.external.navigation.DistanceUnit.METER);
        // A metre of field minus the 0.20 m the lens already sticks out.
        assertEquals(0.80, measured, 0.02, "the beam should have stopped at the perimeter");
    }

    @Test
    void theBatterySagsWhileTheRobotDrives() {
        FakeHardwareMap hardware = hardware();
        hardware.drive().setPose(Pose2d.ORIGIN);
        worldWith(hardware);

        run(hardware, 0.1);
        double rested = hardware.voltageSensor.get("battery").getVoltage();
        drive(hardware, 1.0);
        run(hardware, 0.1);
        double driving = hardware.voltageSensor.get("battery").getVoltage();

        assertTrue(rested > 12.0,
                "a robot doing nothing should read close to a rested pack, got " + rested);
        assertTrue(driving < rested - 0.2,
                "four motors at full power should pull the pack down; it went from " + rested
                        + " to " + driving);
    }

    @Test
    void aSensorTheRobotDoesNotHaveIsRefusedAtBuildTime() {
        // A configuration that declares a sensor the hardware map has no device for is a mistake
        // that would otherwise surface as a reading of zero all match -- which looks exactly like
        // a sensor that works and sees nothing.
        try {
            FakeHardwareMap.builder()
                    .addMotor("FL").addMotor("FR").addMotor("BL").addMotor("BR")
                    .addImu("imu", ImuBehaviors.followingChassis())
                    .addCRServo("intake")
                    .withDrivetrain(robot, FieldConfig.standard())
                    .withMechanisms(robot)
                    .build();
            throw new AssertionError("a missing sensor should have been refused");
        } catch (IllegalArgumentException refused) {
            assertTrue(refused.getMessage().contains("intakeTouch"),
                    "the complaint should name the device that is missing, got: "
                            + refused.getMessage());
        }
    }

    /** The sensor geometry the volume queries are driven from, straight out of the file. */
    private SensorConfig sensor(String name) {
        SensorConfig found = robot.sensors().get(name);
        assertTrue(found != null, "the fixture declares a sensor named " + name);
        return found;
    }

    @Test
    void aVolumeIsMeasuredFromTheRobotRatherThanTheField() {
        FakeHardwareMap hardware = hardware();
        FieldPhysics world = worldWith(hardware, GameElement.pollen(0.0, 1.0));

        // The ball is a metre along field +Y. Turned to face it, the robot's intake is over it;
        // facing +X, the same ball is off to one side. A volume that had been left in field
        // coordinates would answer the same both times.
        hardware.drive().setPose(new Pose2d(0.0, 0.77, Math.PI / 2.0));
        List<GameElement> facing = world.touching(robot.servos().get("intake").sweep());
        hardware.drive().setPose(new Pose2d(0.0, 0.77, 0.0));
        List<GameElement> sideways = world.touching(robot.servos().get("intake").sweep());

        assertEquals(1, facing.size(), "turned towards the ball, the intake should be over it");
        assertEquals(Collections.emptyList(), sideways,
                "turned away, the same intake should be over nothing");
    }
}
