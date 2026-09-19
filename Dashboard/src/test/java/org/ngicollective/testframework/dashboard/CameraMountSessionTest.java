package org.ngicollective.testframework.dashboard;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.ngicollective.testframework.camera.CameraIntrinsics;
import org.ngicollective.testframework.camera.FrameSource;
import org.ngicollective.testframework.camera.SceneFrameSource;
import org.ngicollective.testframework.camera.SimulatedCamera;
import org.ngicollective.testframework.camera.SimulatedScene;
import org.ngicollective.testframework.camera.SyntheticFrame;
import org.ngicollective.testframework.dashboard.protocol.CameraMountPayload;
import org.ngicollective.testframework.dashboard.protocol.OpModeInfo;
import org.ngicollective.testframework.hardware.FakeHardwareMap;
import org.ngicollective.testframework.hardware.SimulatedRobot;
import org.ngicollective.testframework.sim.CameraConfig;
import org.ngicollective.testframework.sim.CameraMount;
import org.ngicollective.testframework.sim.RobotConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.function.Supplier;

/**
 * Aiming the simulated camera from the dashboard, which is what makes finding a good angle
 * possible at all.
 *
 * <p>The behaviour these are about is that a mount arrives, the very next frame is taken through
 * it, and nothing is written anywhere until somebody asks. A session that needed an INIT to apply
 * an angle would turn "drag until the tag is in shot" into a guess-and-check loop several seconds
 * long, and one that wrote the file on every drag would commit every angle anyone tried to the
 * robot the team drives.</p>
 */
class CameraMountSessionTest {

    /**
     * The robot's own mount.
     *
     * <p>Sixty degrees of pitch on purpose. {@code toDegrees(toRadians(60.0))} is
     * 59.99999999999999, so a session that read its camera's aim back as plain degrees would
     * decide a mount identical to the file's was unsaved, and would write that spelling of sixty
     * into the team's configuration file the moment anyone pressed save.</p>
     */
    private static final CameraMount ON_FILE = new CameraMount(0.16, 0.0, 0.105, 0.0, 60.0, 0.0);

    /** Somewhere else entirely, so a frame taken from it cannot resemble one taken from above. */
    private static final CameraMount AIMED = new CameraMount(0.21, -0.05, 0.3, 25.0, -30.0, 3.5);

    @TempDir
    Path configDirectory;

    private Path configFile;
    private LocalDashboardBackend backend;

    @BeforeEach
    void setUp() throws IOException {
        configFile = configDirectory.resolve("mountbot.json");
        Files.write(configFile, robotJson(ON_FILE).getBytes(UTF_8));
    }

    @AfterEach
    void tearDown() {
        if (backend != null) {
            backend.close();
        }
    }

    @Test
    void aMountSetLiveIsWhatTheVeryNextFrameIsTakenThrough() {
        backend = sessionOn(new ConfiguredRobot(configFile));
        SceneFrameSource frames = (SceneFrameSource) backend.cameraFrames();
        byte[] before = rendered(frames);

        backend.setCameraMount(AIMED.forwardMetres(), AIMED.leftMetres(), AIMED.heightMetres(),
                AIMED.yawDegrees(), AIMED.pitchDegrees(), AIMED.rollDegrees());

        assertEquals(AIMED, mountOf(frames), "the live camera is still aimed the old way");
        assertFalse(Arrays.equals(before, rendered(frames)),
                "the same frame came back from a camera that has been pointed somewhere else");
    }

    /**
     * An init and a stop each build a whole new robot from the configuration file. The angle
     * someone is in the middle of choosing is theirs, not the robot's, so it has to outlive both
     * &mdash; otherwise running the OpMode you were aiming for throws the aim away.
     */
    @Test
    void anUnsavedMountOutlivesAnInitAndAStop() {
        backend = sessionOn(new ConfiguredRobot(configFile));
        backend.setCameraMount(AIMED.forwardMetres(), AIMED.leftMetres(), AIMED.heightMetres(),
                AIMED.yawDegrees(), AIMED.pitchDegrees(), AIMED.rollDegrees());

        backend.initOpMode(TickingTeleOp.class.getName());
        assertEquals(AIMED, mountOf((SceneFrameSource) backend.cameraFrames()),
                "INIT rebuilt the robot and put the file's angle back");

        backend.stop();
        assertEquals(AIMED, mountOf((SceneFrameSource) backend.cameraFrames()),
                "STOP rebuilt the robot and put the file's angle back");
        assertTrue(backend.cameraMount().unsaved);
    }

    @Test
    void savingWritesTheMountIntoTheRobotsConfigurationFile() {
        backend = sessionOn(new ConfiguredRobot(configFile));
        backend.setCameraMount(AIMED.forwardMetres(), AIMED.leftMetres(), AIMED.heightMetres(),
                AIMED.yawDegrees(), AIMED.pitchDegrees(), AIMED.rollDegrees());

        Path saved = backend.saveCameraMount();

        assertEquals(configFile, saved, "the saver has to be told which file to commit");
        assertEquals(AIMED, RobotConfig.load(configFile).camera().mount());
        assertFalse(backend.cameraMount().unsaved,
                "the session and the file now agree, so there is nothing left to warn about");
    }

    @Test
    void revertingPutsTheCameraBackWhereTheFileSaysItIs() {
        backend = sessionOn(new ConfiguredRobot(configFile));
        backend.setCameraMount(AIMED.forwardMetres(), AIMED.leftMetres(), AIMED.heightMetres(),
                AIMED.yawDegrees(), AIMED.pitchDegrees(), AIMED.rollDegrees());

        backend.revertCameraMount();

        assertEquals(ON_FILE, mountOf((SceneFrameSource) backend.cameraFrames()));
        assertFalse(backend.cameraMount().unsaved);
    }

    /**
     * The mount the file already describes is not an unsaved edit. Worth its own test because the
     * comparison runs on numbers that have been through the camera's radians and back, and one
     * angle in eight does not survive that trip unchanged.
     */
    @Test
    void aMountIdenticalToTheFilesIsNotReportedAsUnsaved() {
        backend = sessionOn(new ConfiguredRobot(configFile));
        assertEquals(60.0, backend.cameraMount().pitchDegrees, 0.0,
                "the file says sixty degrees, so that is what the sliders must start at");

        backend.setCameraMount(ON_FILE.forwardMetres(), ON_FILE.leftMetres(),
                ON_FILE.heightMetres(), ON_FILE.yawDegrees(), ON_FILE.pitchDegrees(),
                ON_FILE.rollDegrees());

        assertFalse(backend.cameraMount().unsaved,
                "nothing has been changed, so nothing is at risk of being lost");
    }

    /** The frustum the browser draws comes from the lens the frames are rendered through. */
    @Test
    void theReportedFieldOfViewIsTheLensTheFramesAreDrawnWith() {
        backend = sessionOn(new ConfiguredRobot(configFile));

        CameraMountPayload mount = backend.cameraMount();

        CameraIntrinsics lens = LENS;
        assertEquals(lens.horizontalFieldOfViewDegrees(), mount.horizontalFovDegrees, 1e-9);
        assertEquals(lens.verticalFieldOfViewDegrees(), mount.verticalFovDegrees, 1e-9);
        assertTrue(mount.horizontalFovDegrees > mount.verticalFovDegrees,
                "a 4:3 frame is wider than it is tall, and swapping the two would draw the "
                        + "frustum the wrong shape round");
    }

    /**
     * A robot whose numbers were handed to it rather than read from a file has nowhere to save to,
     * and saying so is better than creating a configuration file nobody asked for.
     */
    @Test
    void savingARobotThatReadNoConfigurationFileIsRefused() {
        backend = sessionOn(new SimulatedRobot() {
            @Override
            public String name() {
                return "HandBuiltBot";
            }

            @Override
            public FakeHardwareMap create() {
                return cameraHardware("Webcam 1", ON_FILE);
            }
        });
        backend.setCameraMount(AIMED.forwardMetres(), AIMED.leftMetres(), AIMED.heightMetres(),
                AIMED.yawDegrees(), AIMED.pitchDegrees(), AIMED.rollDegrees());

        assertThrows(IllegalStateException.class, backend::saveCameraMount);
    }

    @Test
    void aRobotWithNoCameraHasNoMountAndCannotBeAimed() {
        backend = sessionOn(new SimulatedRobot() {
            @Override
            public String name() {
                return "BlindBot";
            }

            @Override
            public FakeHardwareMap create() {
                return FakeHardwareMap.builder().addMotor("drive").build();
            }
        });

        assertNull(backend.cameraMount(), "a blind robot must not claim to have a camera");
        assertThrows(IllegalStateException.class,
                () -> backend.setCameraMount(0.1, 0.0, 0.2, 0.0, 0.0, 0.0));
    }

    // Helpers.

    /** Small frames: these tests ask whether the view changed, not what it looks like. */
    private static final CameraIntrinsics LENS = CameraIntrinsics.approximate(64, 48);

    /** A robot that reads a configuration file, and can say which one, as the team's robot does. */
    private static final class ConfiguredRobot implements SimulatedRobot {

        private final Path file;

        ConfiguredRobot(Path file) {
            this.file = file;
        }

        @Override
        public String name() {
            return "MountBot";
        }

        @Override
        public Path configurationFile() {
            return file;
        }

        @Override
        public FakeHardwareMap create() {
            // Read afresh on every build, like VerityRobot: a session that parsed its file once
            // could not see a mount someone had just saved into it.
            CameraConfig camera = RobotConfig.load(file).camera();
            return cameraHardware(camera.name(), camera.mount());
        }
    }

    private static FakeHardwareMap cameraHardware(String name, CameraMount mount) {
        SimulatedCamera camera = new SimulatedCamera(name, LENS, mount.pose());
        return FakeHardwareMap.builder()
                .addMotor("drive")
                .addWebcam(name, new SceneFrameSource(emptyField(), camera))
                .build();
    }

    /** A robot description with the given mount, minimal but complete enough to load. */
    private static String robotJson(CameraMount mount) {
        return "{\n"
                + "  \"version\": 1,\n"
                + "  \"name\": \"MountBot\",\n"
                + "  \"chassis\": { \"widthMetres\": 0.38, \"lengthMetres\": 0.40,"
                + " \"heightMetres\": 0.05, \"deckHeightMetres\": 0.105,"
                + " \"massKilograms\": 14.0 },\n"
                + "  \"drivetrain\": { \"type\": \"mecanum\", \"wheelRadiusMetres\": 0.048,"
                + " \"gearRatio\": 1.0, \"trackWidthMetres\": 0.32, \"wheelBaseMetres\": 0.29,"
                + " \"strafeEfficiency\": 0.8, \"gripCoefficient\": 0.9 },\n"
                + "  \"imu\": { \"name\": \"imu\" },\n"
                + "  \"camera\": {\n"
                + "    \"name\": \"Webcam 1\",\n"
                + "    \"forwardMetres\": " + mount.forwardMetres() + ",\n"
                + "    \"leftMetres\": " + mount.leftMetres() + ",\n"
                + "    \"heightMetres\": " + mount.heightMetres() + ",\n"
                + "    \"yawDegrees\": " + mount.yawDegrees() + ",\n"
                + "    \"pitchDegrees\": " + mount.pitchDegrees() + ",\n"
                + "    \"rollDegrees\": " + mount.rollDegrees() + ",\n"
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
    }

    /**
     * The bare competition field: tiles, perimeter and alliance ends, and nothing standing on it.
     *
     * <p>Enough for a frame to depend on where the camera is pointed, which is the only thing
     * these tests read out of a rendered frame.</p>
     */
    private static SimulatedScene emptyField() {
        return new SimulatedScene(Collections.emptyList(), Collections.emptyList());
    }

    private static LocalDashboardBackend sessionOn(SimulatedRobot robot) {
        Supplier<com.qualcomm.robotcore.eventloop.opmode.OpMode> factory = TickingTeleOp::new;
        OpModeInfo info = new OpModeInfo(
                "Ticking TeleOp", "", "TeleOp", TickingTeleOp.class.getName());
        return new LocalDashboardBackend(
                robot, Collections.singletonList(new OpModeEntry(info, factory)));
    }

    /** Where the session's camera is actually aimed, read off the source that renders it. */
    private static CameraMount mountOf(FrameSource frames) {
        return CameraMount.of(((SceneFrameSource) frames).camera().mount());
    }

    private static byte[] rendered(FrameSource frames) {
        SyntheticFrame frame = new SyntheticFrame(LENS.width(), LENS.height());
        frames.render(frame);
        return frame.rgba().clone();
    }
}
