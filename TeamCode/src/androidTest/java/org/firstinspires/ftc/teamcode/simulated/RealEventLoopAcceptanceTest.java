package org.firstinspires.ftc.teamcode.simulated;

import android.util.Log;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.qualcomm.robotcore.eventloop.opmode.OpModeManagerImpl;
import com.qualcomm.robotcore.hardware.HardwareMap;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.ngicollective.testframework.hardware.FakeHardwareMap;
import org.ngicollective.testframework.hardware.FakeWebcam;
import org.ngicollective.testframework.sim.Pose2d;
import org.ngicollective.testframework.vision.VisionNatives;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * That the SDK's own machinery runs an unmodified vision OpMode against the simulated camera.
 *
 * <p>This is the lifecycle half of the acceptance story: the production robot bring-up, the SDK's
 * event loop, its {@code OpModeManagerImpl} resolving an OpMode by the name its annotation gives
 * it, and LiveView attached to a real view. The other half &mdash; that the detections are
 * <em>right</em> &mdash; is {@link SyntheticCameraAcceptanceTest}, which reads the OpMode's own
 * telemetry.</p>
 *
 * <p>The two are separate classes because they must run in separate processes. Each builds a
 * {@code VisionPortal} with LiveView, and the second one in a process fails with "Viewport
 * container specified by user is not empty!": a portal's viewport is only removed when a real
 * OpMode stop tears it down, and a portal an OpMode never closed keeps the container occupied.
 * {@code mise run test-acceptance} therefore runs each class in its own invocation.</p>
 */
@RunWith(AndroidJUnit4.class)
public class RealEventLoopAcceptanceTest {

    private static final String TAG = "RealEventLoopTest";

    @BeforeClass
    public static void startTheRobot() throws Exception {
        // The processors allocate OpenCV Mats in their constructors, so the natives have to be up
        // before any of this. The app does it at robot start via the SDK's LibLoader hook; here
        // and in the plain-JVM vision tests it is the same seam either way.
        VisionNatives.ensureLoaded();
        RobotUnderTest.start();
    }

    @AfterClass
    public static void stopTheRobot() {
        RobotUnderTest.stop();
    }

    @Test
    public void theSdksOwnEventLoopRunsTheVisionOpMode() throws Exception {
        OpModeManagerImpl manager = RobotUnderTest.manager();
        assertNotNull("a robot should be running", manager);

        HardwareMap map = manager.getHardwareMap();
        assertTrue("the event loop should be running on simulated hardware, got "
                + (map == null ? "null" : map.getClass().getName()), map instanceof FakeHardwareMap);
        FakeHardwareMap hardware = (FakeHardwareMap) map;
        FakeWebcam webcam = hardware.webcam("Webcam 1");
        assertFalse("nothing should stream before an OpMode runs", webcam.state().isStreaming());

        // Beneath the red HIVE, facing across the field so the up-pitched camera sees the tags.
        hardware.drive().setPose(new Pose2d(-0.324, -0.85, Math.toRadians(90.0)));

        // The name the @TeleOp annotation gives it, which is what a Driver Station would send.
        // Resolving it at all means the SDK's OpMode registry was populated, which only happens
        // when its event loop really started.
        manager.initOpMode("Concept: AprilTag Easy");
        Thread.sleep(2000);
        assertEquals("Concept: AprilTag Easy", manager.getActiveOpModeName());

        manager.startActiveOpMode();
        int before = webcam.state().framesDelivered();
        Thread.sleep(3000);
        int delivered = webcam.state().framesDelivered() - before;

        Log.i(TAG, "the SDK's event loop streamed " + delivered + " frames in 3s");
        assertTrue("the OpMode's VisionPortal should have started the simulated camera",
                webcam.state().isStreaming());
        // 30 fps is configured, so three seconds is about ninety frames. A floor of thirty says
        // frames are flowing at a plausible rate without pinning the emulator's exact speed.
        assertTrue("expected frames to flow through the real event loop, got " + delivered,
                delivered > 30);

        manager.stopActiveOpMode();
        Thread.sleep(1000);
        assertFalse("stopping the OpMode should stop the camera", webcam.state().isStreaming());
    }
}
