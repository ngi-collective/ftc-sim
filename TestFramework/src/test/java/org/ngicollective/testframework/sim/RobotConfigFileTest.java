package org.ngicollective.testframework.sim;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Putting a camera mount back into the file it came from without wrecking the file.
 *
 * <p>The fixture is deliberately formatted the way {@code TeamCode/robot-config/verity.json} is:
 * compact one-line blocks for the small things and a motor table with its columns lined up by
 * hand. That formatting is the point. Someone nudging a camera slider gets a diff of six numbers,
 * and an implementation that reads the document into Gson and writes it back would instead
 * reformat every line of it &mdash; which is why the assertions below are about bytes rather than
 * about what the loader makes of them.</p>
 */
class RobotConfigFileTest {

    /** Everything before the camera block, verbatim, hand-formatted. */
    private static final String HEAD = "{\n"
            + "  \"version\": 1,\n"
            + "  \"name\": \"Fixture\",\n"
            + "  \"chassis\": { \"widthMetres\": 0.38, \"lengthMetres\": 0.40,"
            + " \"heightMetres\": 0.05, \"deckHeightMetres\": 0.105,"
            + " \"massKilograms\": 14.0 },\n"
            + "  \"drivetrain\": { \"type\": \"mecanum\", \"wheelRadiusMetres\": 0.048,"
            + " \"gearRatio\": 1.0, \"trackWidthMetres\": 0.32, \"wheelBaseMetres\": 0.29,"
            + " \"strafeEfficiency\": 0.8, \"gripCoefficient\": 0.9 },\n"
            + "  \"imu\": { \"name\": \"imu\" },\n";

    /** Everything after it, including the aligned motor table. */
    private static final String TAIL = ",\n"
            + "  \"motors\": {\n"
            + "    \"FL\": { \"role\": \"frontLeft\",  \"mirrored\": true,  \"rpm\": 312.0,"
            + " \"ticksPerRevolution\": 537.7 },\n"
            + "    \"FR\": { \"role\": \"frontRight\", \"mirrored\": false, \"rpm\": 312.0,"
            + " \"ticksPerRevolution\": 537.7 },\n"
            + "    \"BL\": { \"role\": \"backLeft\",   \"mirrored\": true,  \"rpm\": 312.0,"
            + " \"ticksPerRevolution\": 537.7 },\n"
            + "    \"BR\": { \"role\": \"backRight\",  \"mirrored\": false, \"rpm\": 312.0,"
            + " \"ticksPerRevolution\": 537.7 }\n"
            + "  }\n"
            + "}\n";

    /** The whole mount, written out the way the team's file writes it. */
    private static final String FULL_CAMERA = "  \"camera\": {\n"
            + "    \"name\": \"Webcam 1\",\n"
            + "    \"forwardMetres\": 0.16,\n"
            + "    \"leftMetres\": 0.0,\n"
            + "    \"heightMetres\": 0.105,\n"
            + "    \"yawDegrees\": 0.0,\n"
            + "    \"pitchDegrees\": 35.0,\n"
            + "    \"rollDegrees\": 0.0,\n"
            + "    \"framesPerSecond\": 30.0\n"
            + "  }";

    /** The same block with no height, which the schema allows: it is inherited from the deck. */
    private static final String CAMERA_WITHOUT_HEIGHT = "  \"camera\": {\n"
            + "    \"name\": \"Webcam 1\",\n"
            + "    \"forwardMetres\": 0.16,\n"
            + "    \"leftMetres\": 0.0,\n"
            + "    \"yawDegrees\": 0.0,\n"
            + "    \"pitchDegrees\": 35.0,\n"
            + "    \"rollDegrees\": 0.0,\n"
            + "    \"framesPerSecond\": 30.0\n"
            + "  }";

    @TempDir
    Path configDirectory;

    private Path write(String camera) throws IOException {
        Path file = configDirectory.resolve("robot.json");
        Files.write(file, (HEAD + camera + TAIL).getBytes(UTF_8));
        return file;
    }

    private String contents(Path file) throws IOException {
        return new String(Files.readAllBytes(file), UTF_8);
    }

    @Test
    void aWrittenMountIsWhatTheNextLoadReports() throws IOException {
        Path file = write(FULL_CAMERA);
        // Six different numbers, none of them the ones already in the file: a writer that put a
        // value under the wrong key would otherwise pass by coincidence.
        CameraMount aimed = new CameraMount(0.21, -0.04, 0.32, 12.5, -7.25, 180.0);

        RobotConfigFile.writeCameraMount(file, aimed);

        assertEquals(aimed, RobotConfig.load(file).camera().mount());
    }

    @Test
    void everythingOutsideTheCameraBlockKeepsItsFormattingToTheByte() throws IOException {
        Path file = write(FULL_CAMERA);

        RobotConfigFile.writeCameraMount(file,
                new CameraMount(0.21, -0.04, 0.32, 12.5, -7.25, 0.0));

        String after = contents(file);
        assertTrue(after.startsWith(HEAD),
                "the compact chassis and imu blocks were reformatted:\n" + after);
        assertTrue(after.endsWith(TAIL),
                "the hand-aligned motor table was reformatted:\n" + after);
    }

    /**
     * Height may be absent, and a save has to supply it: a file that went on inheriting the deck
     * height would quietly ignore the one slider a person is most likely to have moved.
     */
    @Test
    void aBlockWithNoHeightGainsOneAndKeepsTheOrderOfTheKeysItAlreadyHad() throws IOException {
        Path file = write(CAMERA_WITHOUT_HEIGHT);

        RobotConfigFile.writeCameraMount(file, new CameraMount(0.16, 0.0, 0.22, 0.0, 35.0, 0.0));

        assertEquals(HEAD + "  \"camera\": {\n"
                + "    \"name\": \"Webcam 1\",\n"
                + "    \"forwardMetres\": 0.16,\n"
                + "    \"leftMetres\": 0.0,\n"
                + "    \"yawDegrees\": 0.0,\n"
                + "    \"pitchDegrees\": 35.0,\n"
                + "    \"rollDegrees\": 0.0,\n"
                + "    \"framesPerSecond\": 30.0,\n"
                + "    \"heightMetres\": 0.22\n"
                + "  }" + TAIL, contents(file));
    }

    @Test
    void aFileWithNoCameraBlockIsRefusedByName() throws IOException {
        Path file = configDirectory.resolve("blind.json");
        // HEAD already ends with the comma that separated it from the camera block; dropping the
        // one TAIL starts with joins the two into a robot that declares no camera at all.
        Files.write(file, (HEAD + TAIL.substring(2)).getBytes(UTF_8));

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> RobotConfigFile.writeCameraMount(file,
                        new CameraMount(0.16, 0.0, 0.105, 0.0, 35.0, 0.0)));

        assertTrue(thrown.getMessage().contains(file.toString()), thrown.getMessage());
    }
}
