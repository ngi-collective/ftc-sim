package org.firstinspires.ftc.teamcode.simulated;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.firstinspires.ftc.robotcore.external.hardware.camera.WebcamName;
import org.firstinspires.ftc.teamcode.ConceptAprilTagEasy;
import org.firstinspires.ftc.vision.VisionPortal;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.ngicollective.testframework.hardware.FakeHardwareMap;
import org.ngicollective.testframework.harness.LinearOpModeHarness;
import org.ngicollective.testframework.harness.OpModeHarness;
import org.ngicollective.testframework.sim.Pose2d;
import org.openftc.easyopencv.SyntheticCameras;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The acceptance test for the whole simulated camera: does an OpMode nobody modified see the
 * simulated field?
 *
 * <p>This is the only test in the project that exercises the real SDK vision stack end to end,
 * and so the only one that would catch the simulator lying. It runs on the plain JVM execution
 * target: the SDK's OpenCV is replaced by a desktop build, its AprilTag detector is rebuilt from
 * OpenFTC's own sources, and Robolectric supplies the Android framework underneath &mdash; see
 * {@link PlainJvmVision} and {@code docs/adr/0007-vision-runs-on-the-plain-jvm.md}. It used to
 * need an emulator, which kept it out of CI.</p>
 *
 * <p>It asserts on the OpMode's own telemetry rather than reaching inside it. That is deliberate:
 * the telemetry is the OpMode's output, and a student reads exactly these lines when deciding
 * whether their vision code works. Reaching past it for the processor would test something no
 * user can see.</p>
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class SyntheticCameraAcceptanceTest {

    /**
     * "==== Tag Cluster (RED SCORING)" or "==== (ID 34) RED AUDIENCE", as the stock sample prints.
     *
     * <p>Both forms matter, and which one appears is the season's doing rather than a choice:
     * {@code AprilTagProcessorImpl} reports the members of a recognised cluster as a single
     * cluster detection and only reports a tag individually when it belongs to no cluster. On
     * BioBuzz, where every tag is a cluster member, a camera seeing two tags of one CELL yields
     * one cluster and no singles at all.</p>
     */
    private static final Pattern DETECTION =
            Pattern.compile("==== (?:\\(ID (\\d+)\\)\\s*(\\S+)|Tag Cluster \\(([^)]+)\\))");

    /** "RBE   57.8   -0.0   -0.5  (inch, deg, deg)" as the stock sample prints it. */
    private static final Pattern RANGE_BEARING_ELEVATION =
            Pattern.compile("RBE\\s+(-?[\\d.]+)\\s+(-?[\\d.]+)\\s+(-?[\\d.]+)");

    @Test
    public void anUnmodifiedSampleOpModeSeesTheSimulatedField() throws Exception {
        PlainJvmVision.start();

        assertTrue("LiveView should resolve under this build's application id;"
                        + " a zero means the camera preview is silently disabled",
                VisionPortal.DEFAULT_VIEW_CONTAINER_ID != 0);
        assertTrue("the simulated camera factory should be installed",
                SyntheticCameras.isInstalled());

        FakeHardwareMap hardware = new VerityRobot().create();
        assertNotNull("the simulated robot should declare Webcam 1",
                hardware.tryGet(WebcamName.class, "Webcam 1"));

        // Beneath the red HIVE, facing across the field so the up-pitched camera looks into the
        // tags on the underside of the raised CELL.
        hardware.drive().setPose(new Pose2d(-0.324, -0.85, Math.toRadians(90.0)));

        // Verbatim stock sample: it builds its own VisionPortal from hardwareMap.get(...) and
        // nothing in it knows this framework exists.
        LinearOpModeHarness harness =
                OpModeHarness.forLinear(new ConceptAprilTagEasy(), hardware);
        harness.launch();
        harness.pressStart();

        List<String> detections = new ArrayList<>();
        List<String> poses = new ArrayList<>();
        for (int tick = 0; tick < 150 && detections.isEmpty(); tick++) {
            harness.advance(0.02);
            PlainJvmVision.pump();
            // Real time for the render and the detector to run; simulated time paces the frames,
            // but the work still has to happen on a real CPU.
            Thread.sleep(10);
            for (String line : harness.lastTelemetry()) {
                Matcher detection = DETECTION.matcher(line);
                if (detection.find()) {
                    detections.add(detection.group(0).trim());
                }
                Matcher rbe = RANGE_BEARING_ELEVATION.matcher(line);
                if (rbe.find()) {
                    poses.add(rbe.group(0).trim());
                }
            }
        }

        harness.pressStop();
        harness.awaitCompletion(5000);
        System.out.println("detections: " + detections);
        System.out.println("poses: " + poses);

        assertFalse("an unmodified OpMode reported no AprilTags from the simulated field",
                detections.isEmpty());

        // Only the red HIVE is in view from where the robot was placed, so every detection must
        // name it: ids 30-37, or a cluster called RED something.
        for (String line : detections) {
            Matcher matcher = DETECTION.matcher(line);
            assertTrue(matcher.find());
            if (matcher.group(1) != null) {
                int id = Integer.parseInt(matcher.group(1));
                assertTrue("expected a red-alliance BioBuzz tag, the OpMode reported " + line,
                        id >= 30 && id <= 37);
                assertTrue("a detected tag should be named from the season's library, got " + line,
                        matcher.group(2).startsWith("RED"));
            } else {
                assertTrue("a cluster should be one of the red HIVE's, got " + line,
                        matcher.group(3).startsWith("RED"));
            }
        }

        // The sample prints range in inches. Anything the camera can resolve is within a few feet,
        // and a zero or a wild number would mean no usable calibration reached the processor.
        assertFalse("the OpMode never printed a pose", poses.isEmpty());
        for (String line : poses) {
            Matcher matcher = RANGE_BEARING_ELEVATION.matcher(line);
            assertTrue(matcher.find());
            double rangeInches = Double.parseDouble(matcher.group(1));
            assertTrue("implausible range " + rangeInches + " in: " + line,
                    rangeInches > 4.0 && rangeInches < 160.0);
        }
    }
}
