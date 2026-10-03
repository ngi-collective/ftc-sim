package org.firstinspires.ftc.teamcode;

import org.firstinspires.ftc.teamcode.simulated.ExampleRobot;
import org.junit.jupiter.api.Test;
import org.ngicollective.ftcsim.camera.CameraIntrinsics;
import org.ngicollective.ftcsim.camera.Pose3d;
import org.ngicollective.ftcsim.camera.SceneFrameSource;
import org.ngicollective.ftcsim.camera.SimulatedCamera;
import org.ngicollective.ftcsim.camera.SyntheticFrame;
import org.ngicollective.ftcsim.camera.Vec3;
import org.ngicollective.ftcsim.hardware.FakeHardwareMap;
import org.ngicollective.ftcsim.hardware.FakeWebcam;
import org.ngicollective.ftcsim.season.BioBuzzScenario;
import org.ngicollective.ftcsim.sim.FieldConfig;
import org.ngicollective.ftcsim.sim.Pose2d;
import org.ngicollective.ftcsim.sim.SimConfigFiles;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * That the robot's own camera, as configured, actually sees the field.
 *
 * <p>On a plain JVM, deliberately. Everything from the mount in {@code example.json} through the
 * pose the drive model reports to the pixels in the frame is ordinary arithmetic, so it can be
 * checked in milliseconds. Only the SDK's detector needs a device, and it has its own test.</p>
 */
class SimulatedCameraWiringTest {

    private static FakeHardwareMap robotLookingAt(String scenario) {
        return new ExampleRobot(SimConfigFiles.robot("example"), FieldConfig.standard(),
                BioBuzzScenario.named(scenario).scene()).create();
    }

    @Test
    void theConfiguredCameraSeesTheTagsAboveIt() {
        FakeHardwareMap hardware = robotLookingAt("hives-tipped-back");
        FakeWebcam webcam = hardware.webcam("Webcam 1");
        assertNotNull(webcam.frameSource());

        // Under the red HIVE, facing across the field; example.json pitches the camera up 35
        // degrees, which is what lets it see plates hanging 3 to 4 feet up.
        hardware.drive().setPose(new Pose2d(-0.324, -0.85, Math.toRadians(90.0)));

        SyntheticFrame frame = new SyntheticFrame(640, 480);
        webcam.frameSource().render(frame);

        assertTrue(darkFraction(frame) > 0.001,
                "the camera should see printed tags, not an empty field");
    }

    @Test
    void aScenariosBallsAreWhereABallCameraCouldSeeThem() {
        // Not through the example robot's camera, and that is the point. Its mount pitches up 35 degrees to
        // reach plates hanging 35 to 50 inches up; balls on the floor sit a few degrees *below*
        // the horizon. Covering both would need about 80 degrees of vertical view and a 640x480
        // frame through this lens has 46, so one camera cannot do both jobs on this field. This
        // checks the scenario's ball positions against the camera that would hunt them.
        SimulatedCamera ballCamera = new SimulatedCamera("Ball Cam",
                CameraIntrinsics.approximate(640, 480),
                Pose3d.ofDegrees(new Vec3(0.16, 0.0, 0.25), 0.0, -10.0, 0.0));
        SceneFrameSource source = new SceneFrameSource(
                BioBuzzScenario.named("practice-balls").scene(), ballCamera,
                new SceneFrameSource.PoseSource() {
                    @Override
                    public Pose2d pose() {
                        return new Pose2d(-0.40, -1.75, Math.toRadians(90.0));
                    }
                });

        SyntheticFrame frame = new SyntheticFrame(640, 480);
        source.render(frame);

        int reddish = 0;
        int yellowish = 0;
        for (int y = 0; y < frame.height(); y++) {
            for (int x = 0; x < frame.width(); x++) {
                int red = frame.redAt(x, y);
                int green = frame.greenAt(x, y);
                int blue = frame.blueAt(x, y);
                if (red > 150 && green < 100 && blue < 100) {
                    reddish++;
                } else if (red > 150 && green > 150 && blue < 100) {
                    yellowish++;
                }
            }
        }

        assertTrue(reddish > 50, "expected red NECTAR in frame, found " + reddish + " px");
        assertTrue(yellowish > 50, "expected POLLEN in frame, found " + yellowish + " px");
    }

    @Test
    void drivingChangesWhatTheCameraSees() {
        // The camera is bolted to the robot, so the view has to follow it. A frame source that
        // captured the pose once would pass every other test in this file.
        FakeHardwareMap hardware = robotLookingAt("hives-tipped-back");
        SyntheticFrame frame = new SyntheticFrame(640, 480);

        hardware.drive().setPose(new Pose2d(-0.324, -0.85, Math.toRadians(90.0)));
        hardware.webcam("Webcam 1").frameSource().render(frame);
        double facingTheTags = darkFraction(frame);

        // Same spot, nose at the far wall: the HIVE is now behind the robot.
        hardware.drive().setPose(new Pose2d(-0.324, -0.85, Math.toRadians(-90.0)));
        hardware.webcam("Webcam 1").frameSource().render(frame);
        double facingAway = darkFraction(frame);

        assertTrue(facingTheTags > facingAway,
                "turning away should show fewer tags: " + facingTheTags + " then " + facingAway);
        assertEquals(0.0, facingAway, 1e-9, "facing away, no tag should be in frame");
    }

    /** What share of the frame is darker than the background, which is where tags are. */
    private static double darkFraction(SyntheticFrame frame) {
        int dark = 0;
        for (int y = 0; y < frame.height(); y++) {
            for (int x = 0; x < frame.width(); x++) {
                if (frame.luminanceAt(x, y) < 40) {
                    dark++;
                }
            }
        }
        return dark / (double) (frame.width() * frame.height());
    }

    /** What share of the frame is a saturated colour, which is where balls are. */
    private static double colouredFraction(SyntheticFrame frame) {
        int coloured = 0;
        for (int y = 0; y < frame.height(); y++) {
            for (int x = 0; x < frame.width(); x++) {
                int red = frame.redAt(x, y);
                int green = frame.greenAt(x, y);
                int blue = frame.blueAt(x, y);
                int max = Math.max(red, Math.max(green, blue));
                int min = Math.min(red, Math.min(green, blue));
                if (max - min > 80) {
                    coloured++;
                }
            }
        }
        return coloured / (double) (frame.width() * frame.height());
    }
}
