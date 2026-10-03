package org.firstinspires.ftc.teamcode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.qualcomm.robotcore.eventloop.opmode.OpMode;

import org.firstinspires.ftc.teamcode.simulated.ExampleRobot;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.ngicollective.ftcsim.camera.Pose3d;
import org.ngicollective.ftcsim.camera.SceneFrameSource;
import org.ngicollective.ftcsim.dashboard.LocalDashboardBackend;
import org.ngicollective.ftcsim.dashboard.OpModeEntry;
import org.ngicollective.ftcsim.dashboard.protocol.OpModeInfo;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;

/**
 * Editing the robot's configuration file changes the robot the next INIT builds.
 *
 * <p>This is the contract that makes the file worth having. A dashboard session keeps one robot
 * for the life of the process &mdash; an OpMode runs <em>on</em> a robot, it does not bring one
 * into existence &mdash; so if that robot parsed its numbers on the way in, then moving the webcam
 * on the robot, or widening the chassis, or changing a gear ratio, could not be seen without
 * restarting the dashboard. It was not: a moved camera rendered from its old mount for as long as
 * the process lived, and INIT looked like it had done nothing.</p>
 *
 * <p>Asserted on the camera mount because that is the field of the configuration with the most
 * visible consequence and the one that was reported broken. The mechanism is not camera-specific:
 * the whole file is read again.</p>
 */
class ConfigReloadTest {

    private static final String NAME = ReloadTeleOp.class.getName();

    /** Where the mount ends up when nothing has been edited, in metres and degrees. */
    private static final double FORWARD = 0.16;
    private static final double HEIGHT = 0.105;
    private static final double PITCH_DEGREES = 35.0;

    /** Metres and degrees are exact in the file and exact in a double; this is round-trip slack. */
    private static final double EXACT = 1e-9;

    @TempDir
    Path configDirectory;

    private Path configFile;
    private LocalDashboardBackend backend;

    @BeforeEach
    void setUp() throws IOException {
        configFile = configDirectory.resolve("example.json");
        writeCameraMount(FORWARD, 0.0, HEIGHT, PITCH_DEGREES);
        backend = session();
    }

    @AfterEach
    void tearDown() {
        backend.close();
    }

    @Test
    void movingTheCameraOnTheRobotChangesWhereTheNextInitRendersFrom() throws IOException {
        assertEquals(FORWARD, mount().position().x(), EXACT);
        assertEquals(PITCH_DEGREES, Math.toDegrees(mount().pitch()), EXACT);

        // Somebody unbolts the webcam, moves it to the back of the robot and aims it at the floor.
        writeCameraMount(-0.18, 0.05, 0.42, -20.0);
        backend.initOpMode(NAME);

        assertEquals(-0.18, mount().position().x(), EXACT, "the mount is read from the file again");
        assertEquals(0.05, mount().position().y(), EXACT);
        assertEquals(0.42, mount().position().z(), EXACT);
        assertEquals(-20.0, Math.toDegrees(mount().pitch()), EXACT);
    }

    @Test
    void aConfigurationFileThatWillNotParseLeavesTheLastGoodRobotRunning() throws IOException {
        Files.write(configFile, "{ \"version\": 1, \"name\":".getBytes(StandardCharsets.UTF_8));

        IllegalArgumentException thrown =
                assertThrows(IllegalArgumentException.class, () -> backend.initOpMode(NAME));

        // Which file, because a session reads several and the answer decides where to look.
        assertTrue(thrown.getMessage().contains(configFile.toString()), thrown.getMessage());

        // The session is still the robot that did parse, still rendering from its mount. A typo at
        // nine in the evening before a competition costs an init, not the dashboard.
        assertEquals(PITCH_DEGREES, Math.toDegrees(mount().pitch()), EXACT);

        writeCameraMount(FORWARD, 0.0, HEIGHT, -20.0);
        backend.initOpMode(NAME);
        assertEquals(-20.0, Math.toDegrees(mount().pitch()), EXACT,
                "a session that survived a bad file has to accept the corrected one");
    }

    private Pose3d mount() {
        return ((SceneFrameSource) backend.cameraFrames()).camera().mount();
    }

    private LocalDashboardBackend session() {
        OpModeInfo info = new OpModeInfo("Reload TeleOp", "", "TeleOp", NAME);
        return new LocalDashboardBackend(new ExampleRobot(configDirectory),
                Collections.singletonList(new OpModeEntry(info, ReloadTeleOp::new)));
    }

    /**
     * Writes a complete robot description with the given camera mount.
     *
     * <p>The same shape as {@code TeamCode/robot-config/example.json}, trimmed to what a robot with
     * a camera needs. Written rather than copied and patched, so that what this test asserts about
     * is exactly what it put on disk.</p>
     */
    private void writeCameraMount(double forwardMetres, double leftMetres, double heightMetres,
                                  double pitchDegrees) throws IOException {
        String json = "{\n"
                + "  \"version\": 1,\n"
                + "  \"name\": \"Example\",\n"
                + "  \"chassis\": { \"widthMetres\": 0.38, \"lengthMetres\": 0.40,"
                + " \"heightMetres\": 0.05, \"deckHeightMetres\": 0.105,"
                + " \"massKilograms\": 14.0 },\n"
                + "  \"drivetrain\": {\n"
                + "    \"type\": \"mecanum\",\n"
                + "    \"wheelRadiusMetres\": 0.048,\n"
                + "    \"gearRatio\": 1.0,\n"
                + "    \"trackWidthMetres\": 0.32,\n"
                + "    \"wheelBaseMetres\": 0.29,\n"
                + "    \"strafeEfficiency\": 0.8,\n"
                + "    \"gripCoefficient\": 0.9\n"
                + "  },\n"
                + "  \"imu\": { \"name\": \"imu\" },\n"
                + "  \"camera\": {\n"
                + "    \"name\": \"Webcam 1\",\n"
                + "    \"forwardMetres\": " + forwardMetres + ",\n"
                + "    \"leftMetres\": " + leftMetres + ",\n"
                + "    \"heightMetres\": " + heightMetres + ",\n"
                + "    \"yawDegrees\": 0.0,\n"
                + "    \"pitchDegrees\": " + pitchDegrees + ",\n"
                + "    \"rollDegrees\": 0.0,\n"
                + "    \"framesPerSecond\": 30.0\n"
                + "  },\n"
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
        Files.write(configFile, json.getBytes(StandardCharsets.UTF_8));
    }

    /** Something for INIT to load: what it does is irrelevant, the rebuild is the subject. */
    public static class ReloadTeleOp extends OpMode {

        @Override
        public void init() {
        }

        @Override
        public void loop() {
        }
    }
}
