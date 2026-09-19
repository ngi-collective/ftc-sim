package org.ngicollective.testframework.sim;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;

/**
 * What a robot description is allowed to say, and what happens when it says it wrongly.
 *
 * <p>Every configuration here is written into a temporary directory by the test itself, never read
 * from {@code TeamCode/robot-config/verity.json}. Two reasons, and the second is the one that
 * matters: TeamCode's configuration is not on this module's path at all, and a test pinned to the
 * competition robot would start failing the day someone moves the intake a centimetre, which is a
 * fact about the robot and not about the loader.</p>
 */
class RobotConfigTest {

    /**
     * A complete version 1 robot down to its {@code motors} block, so a test can supply that and
     * then append the blocks it is about. Deliberately a robot with no servos and no sensors: that
     * is the shape every configuration file in the repository had before mechanisms were
     * simulated.
     */
    private static final String HEAD = "{\n"
            + "  \"version\": 1,\n"
            + "  \"name\": \"Fixture\",\n"
            + "  \"chassis\": { \"widthMetres\": 0.38, \"lengthMetres\": 0.40,"
            + " \"heightMetres\": 0.05, \"deckHeightMetres\": 0.105,"
            + " \"massKilograms\": 14.0 },\n"
            + "  \"drivetrain\": { \"type\": \"mecanum\", \"wheelRadiusMetres\": 0.048,"
            + " \"gearRatio\": 1.0, \"trackWidthMetres\": 0.32, \"wheelBaseMetres\": 0.29,"
            + " \"strafeEfficiency\": 0.8, \"gripCoefficient\": 0.9 },\n"
            + "  \"imu\": { \"name\": \"imu\" },\n"
            + "  \"camera\": { \"name\": \"Webcam 1\", \"forwardMetres\": 0.16,"
            + " \"leftMetres\": 0.0, \"yawDegrees\": 0.0, \"pitchDegrees\": 35.0,"
            + " \"rollDegrees\": 0.0, \"framesPerSecond\": 30.0 },\n";

    private static final String WHEELS = "  \"motors\": {\n"
            + "    \"FL\": { \"role\": \"frontLeft\",  \"mirrored\": true,  \"rpm\": 312.0,"
            + " \"ticksPerRevolution\": 537.7 },\n"
            + "    \"FR\": { \"role\": \"frontRight\", \"mirrored\": false, \"rpm\": 312.0,"
            + " \"ticksPerRevolution\": 537.7 },\n"
            + "    \"BL\": { \"role\": \"backLeft\",   \"mirrored\": true,  \"rpm\": 312.0,"
            + " \"ticksPerRevolution\": 537.7 },\n"
            + "    \"BR\": { \"role\": \"backRight\",  \"mirrored\": false, \"rpm\": 312.0,"
            + " \"ticksPerRevolution\": 537.7 }\n"
            + "  }";

    /** A bare 5203 with a flywheel on it, for the tests about launchers. */
    private static final String FLYWHEEL = "  \"motors\": {\n"
            + "    \"flywheel\": { \"role\": \"launcher\", \"mirrored\": false, \"rpm\": 6000.0,"
            + " \"ticksPerRevolution\": 28.0 }\n"
            + "  }";

    /** The mouth of an intake at the nose of that chassis: 0.20 m out, low enough to scoop. */
    private static final String MOUTH = "{ \"forwardMetres\": 0.23, \"leftMetres\": 0.0,"
            + " \"heightMetres\": 0.045, \"lengthMetres\": 0.08, \"widthMetres\": 0.28,"
            + " \"tallMetres\": 0.09 }";

    @TempDir
    Path configDirectory;

    /** Writes a four-wheeled fixture plus {@code blocks}, which must start with its own comma. */
    private Path write(String blocks) throws IOException {
        return write(WHEELS, blocks);
    }

    private Path write(String motors, String blocks) throws IOException {
        Path file = configDirectory.resolve("robot.json");
        Files.write(file, (HEAD + motors + blocks + "\n}\n").getBytes(UTF_8));
        return file;
    }

    @Test
    void aRobotThatDeclaresNeitherServosNorSensorsStillLoads() throws IOException {
        RobotConfig robot = RobotConfig.load(write(""));

        assertTrue(robot.servos().isEmpty(), "absent \"servos\" must mean none, not a failure");
        assertTrue(robot.sensors().isEmpty(), "absent \"sensors\" must mean none, not a failure");
        assertEquals(4, robot.motors().size(), "the rest of the file must read as it always did");
    }

    @Test
    void anUnknownSensorTypeIsRefusedWithTheFileAndTheValueThatWasWritten() throws IOException {
        Path file = write(",\n  \"sensors\": {\n"
                + "    \"intakeColor\": { \"type\": \"colour\", \"volume\": " + MOUTH + " }\n"
                + "  }");

        IllegalArgumentException thrown =
                assertThrows(IllegalArgumentException.class, () -> RobotConfig.load(file));

        assertTrue(thrown.getMessage().contains(file.toString()), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("colour"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("color"),
                "and what is understood, so the fix is in the message: " + thrown.getMessage());
    }

    @Test
    void aVolumeMissingADimensionIsRefusedRatherThanTreatedAsZeroSized() throws IOException {
        Path file = write(",\n  \"sensors\": {\n"
                + "    \"intakeTouch\": { \"type\": \"touch\", \"volume\": {"
                + " \"forwardMetres\": 0.23, \"leftMetres\": 0.0, \"heightMetres\": 0.045,"
                + " \"lengthMetres\": 0.08, \"widthMetres\": 0.28 } }\n"
                + "  }");

        IllegalArgumentException thrown =
                assertThrows(IllegalArgumentException.class, () -> RobotConfig.load(file));

        assertTrue(thrown.getMessage().contains("tallMetres"), thrown.getMessage());
    }

    @Test
    void aZeroSizedVolumeIsRefusedToo() throws IOException {
        Path file = write(",\n  \"sensors\": {\n"
                + "    \"intakeTouch\": { \"type\": \"touch\", \"volume\": {"
                + " \"forwardMetres\": 0.23, \"leftMetres\": 0.0, \"heightMetres\": 0.045,"
                + " \"lengthMetres\": 0.08, \"widthMetres\": 0.0, \"tallMetres\": 0.09 } }\n"
                + "  }");

        IllegalArgumentException thrown =
                assertThrows(IllegalArgumentException.class, () -> RobotConfig.load(file));

        assertTrue(thrown.getMessage().contains("widthMetres"), thrown.getMessage());
    }

    @Test
    void aDistanceSensorWithoutARangeIsRefusedBecauseItCouldSeeNothing() throws IOException {
        Path file = write(",\n  \"sensors\": {\n"
                + "    \"frontRange\": { \"type\": \"distance\", \"forwardMetres\": 0.20,"
                + " \"leftMetres\": 0.0, \"heightMetres\": 0.05, \"yawDegrees\": 0.0,"
                + " \"pitchDegrees\": 0.0, \"maxRangeMetres\": 0.0 }\n"
                + "  }");

        IllegalArgumentException thrown =
                assertThrows(IllegalArgumentException.class, () -> RobotConfig.load(file));

        assertTrue(thrown.getMessage().contains("maxRangeMetres"), thrown.getMessage());
    }

    @Test
    void eachKindOfSensorReadsOnlyTheGeometryThatKindHas() throws IOException {
        RobotConfig robot = RobotConfig.load(write(",\n  \"sensors\": {\n"
                + "    \"intakeTouch\": { \"type\": \"touch\", \"volume\": " + MOUTH + " },\n"
                + "    \"frontRange\": { \"type\": \"distance\", \"forwardMetres\": 0.20,"
                + " \"leftMetres\": 0.0, \"heightMetres\": 0.05, \"yawDegrees\": 0.0,"
                + " \"pitchDegrees\": 0.0, \"maxRangeMetres\": 2.0 },\n"
                + "    \"battery\": { \"type\": \"voltage\" }\n"
                + "  }"));

        SensorConfig touch = robot.sensors().get("intakeTouch");
        assertEquals(SensorConfig.Kind.TOUCH, touch.kind());
        // The extents are three different numbers on purpose: a loader that read width where it
        // meant length would build a mouth 0.28 m deep and 0.08 m wide, which swallows the robot.
        assertEquals(0.08, touch.volume().lengthMetres(), 1e-9);
        assertEquals(0.28, touch.volume().widthMetres(), 1e-9);
        assertEquals(0.09, touch.volume().tallMetres(), 1e-9);
        assertEquals(0.23, touch.forwardMetres(), 1e-9, "a touch sensor is where its box is");
        assertTrue(Double.isNaN(touch.maxRangeMetres()), "a box has no range along a ray");

        SensorConfig range = robot.sensors().get("frontRange");
        assertNull(range.volume(), "a distance sensor is a ray, not a box");
        assertEquals(2.0, range.maxRangeMetres(), 1e-9);
        assertEquals(0.20, range.forwardMetres(), 1e-9);

        // The point of this one: the battery declared no geometry at all and still loaded.
        SensorConfig battery = robot.sensors().get("battery");
        assertEquals(SensorConfig.Kind.VOLTAGE, battery.kind());
        assertNull(battery.volume());
        assertTrue(Double.isNaN(battery.forwardMetres()),
                "zero would put the battery monitor at the middle of the robot");
    }

    @Test
    void aSweepingServoCarriesTheSpeedItsRollersTurnAt() throws IOException {
        RobotConfig robot = RobotConfig.load(write(",\n  \"servos\": {\n"
                + "    \"intake\": { \"type\": \"continuous\","
                + " \"surfaceMetresPerSecond\": 1.4, \"sweep\": " + MOUTH + " },\n"
                + "    \"claw\": { \"type\": \"positional\" }\n"
                + "  }"));

        ServoConfig intake = robot.servos().get("intake");
        assertTrue(intake.continuous());
        assertTrue(intake.sweepsBalls());
        assertEquals(1.4, intake.surfaceMetresPerSecond(), 1e-9);
        assertEquals(0.23, intake.sweep().forwardMetres(), 1e-9);

        ServoConfig claw = robot.servos().get("claw");
        assertFalse(claw.continuous());
        assertFalse(claw.sweepsBalls(), "a claw declares no volume to drag balls in");
        assertNull(claw.sweep());
    }

    @Test
    void aSweepWithoutASurfaceSpeedIsRefusedBecauseItWouldDragNothing() throws IOException {
        Path file = write(",\n  \"servos\": {\n"
                + "    \"intake\": { \"type\": \"continuous\", \"sweep\": " + MOUTH + " }\n"
                + "  }");

        IllegalArgumentException thrown =
                assertThrows(IllegalArgumentException.class, () -> RobotConfig.load(file));

        assertTrue(thrown.getMessage().contains("surfaceMetresPerSecond"), thrown.getMessage());
    }

    @Test
    void anUnknownServoTypeIsRefusedWithTheValueThatWasWritten() throws IOException {
        Path file = write(",\n  \"servos\": {\n"
                + "    \"intake\": { \"type\": \"continous\","
                + " \"surfaceMetresPerSecond\": 1.4, \"sweep\": " + MOUTH + " }\n"
                + "  }");

        IllegalArgumentException thrown =
                assertThrows(IllegalArgumentException.class, () -> RobotConfig.load(file));

        assertTrue(thrown.getMessage().contains(file.toString()), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("continous"), thrown.getMessage());
    }

    @Test
    void servosAndSensorsComeBackInTheOrderTheFileDeclaresThem() throws IOException {
        RobotConfig robot = RobotConfig.load(write(",\n  \"servos\": {\n"
                + "    \"wrist\": { \"type\": \"positional\" },\n"
                + "    \"intake\": { \"type\": \"continuous\","
                + " \"surfaceMetresPerSecond\": 1.4, \"sweep\": " + MOUTH + " },\n"
                + "    \"claw\": { \"type\": \"positional\" }\n"
                + "  },\n  \"sensors\": {\n"
                + "    \"battery\": { \"type\": \"voltage\" },\n"
                + "    \"intakeTouch\": { \"type\": \"touch\", \"volume\": " + MOUTH + " },\n"
                + "    \"intakeColor\": { \"type\": \"color\", \"volume\": " + MOUTH + " }\n"
                + "  }"));

        // Neither list is alphabetical, and neither is the reverse of the other: a map that sorted
        // its keys, or one that lost the order Gson read them in, cannot pass both of these.
        assertEquals(Arrays.asList("wrist", "intake", "claw"),
                new ArrayList<>(robot.servos().keySet()));
        assertEquals(Arrays.asList("battery", "intakeTouch", "intakeColor"),
                new ArrayList<>(robot.sensors().keySet()));
    }

    @Test
    void aLauncherCarriesTheWheelItThrowsWithAndWhereItPoints() throws IOException {
        RobotConfig robot = RobotConfig.load(write(FLYWHEEL, ",\n  \"launchers\": {"
                + "\n    \"flywheel\": { \"wheelRadiusMetres\": 0.0508,"
                + " \"transferEfficiency\": 0.5, \"spinUpSeconds\": 1.2,"
                + " \"exitYawDegrees\": -8.0, \"exitPitchDegrees\": 75.0,"
                + " \"mouth\": " + MOUTH + " }\n  }"));

        LauncherConfig launcher = robot.launchers().get("flywheel");
        assertEquals(1, robot.launchers().size(), "one launcher was declared");
        assertEquals("flywheel", launcher.motorName(),
                "a launcher is named by the motor that spins it");
        assertEquals(0.0508, launcher.wheelRadiusMetres(), 1e-9);
        assertEquals(0.5, launcher.transferEfficiency(), 1e-9);
        assertEquals(1.2, launcher.spinUpSeconds(), 1e-9);
        // Signed, and read as written: a launcher canted to the right is an ordinary robot.
        assertEquals(-8.0, launcher.exitYawDegrees(), 1e-9);
        assertEquals(75.0, launcher.exitPitchDegrees(), 1e-9);
        assertEquals(0.23, launcher.mouth().forwardMetres(), 1e-9);
    }

    @Test
    void aLauncherOnAMotorTheRobotDoesNotHaveIsRefused() throws IOException {
        Path file = write(FLYWHEEL, ",\n  \"launchers\": {"
                + "\n    \"flywheal\": { \"wheelRadiusMetres\": 0.0508,"
                + " \"transferEfficiency\": 0.5, \"spinUpSeconds\": 1.2,"
                + " \"exitYawDegrees\": 0.0, \"exitPitchDegrees\": 75.0,"
                + " \"mouth\": " + MOUTH + " }\n  }");

        IllegalArgumentException thrown =
                assertThrows(IllegalArgumentException.class, () -> RobotConfig.load(file));

        // The misspelling and the names it could have meant, because the alternative is a flywheel
        // that never turns and reads as broken physics.
        assertTrue(thrown.getMessage().contains("flywheal"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("flywheel"), thrown.getMessage());
    }

    @Test
    void aLauncherThatThrowsFasterThanItsWheelTurnsIsRefused() throws IOException {
        Path file = write(FLYWHEEL, ",\n  \"launchers\": {"
                + "\n    \"flywheel\": { \"wheelRadiusMetres\": 0.0508,"
                + " \"transferEfficiency\": 50.0, \"spinUpSeconds\": 1.2,"
                + " \"exitYawDegrees\": 0.0, \"exitPitchDegrees\": 75.0,"
                + " \"mouth\": " + MOUTH + " }\n  }");

        // 50 where 0.5 was meant is one keystroke, and the result would be a ball leaving this
        // flywheel at over a kilometre a second: free energy, refused where every other ratio in
        // this file is.
        IllegalArgumentException thrown =
                assertThrows(IllegalArgumentException.class, () -> RobotConfig.load(file));

        assertTrue(thrown.getMessage().contains("transferEfficiency"), thrown.getMessage());
    }
}
