package org.ngicollective.testframework.dashboard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.ngicollective.testframework.camera.FrameSource;
import org.ngicollective.testframework.camera.SyntheticFrame;
import org.ngicollective.testframework.dashboard.protocol.OpModeInfo;
import org.ngicollective.testframework.hardware.FakeHardwareMap;
import org.ngicollective.testframework.hardware.SimulatedRobot;

import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * What the Dashboard Camera View is allowed to assume about a session's camera.
 *
 * <p>The behaviour under test is a deliberate divergence from real hardware, where nothing produces
 * frames until a {@code VisionPortal} opens the camera: here the view is live whenever the
 * simulation is, because "can the camera see the tag from this spot" is a question you ask
 * <em>before</em> writing the OpMode that needs the answer.</p>
 */
class CameraViewFramesTest {

    /** Paints each robot it builds a different shade, so a frame says which robot rendered it. */
    private static final class CountingRobot implements SimulatedRobot {

        final AtomicInteger generation = new AtomicInteger();

        @Override
        public String name() {
            return "CameraBot";
        }

        @Override
        public FakeHardwareMap create() {
            final int shade = generation.incrementAndGet();
            return FakeHardwareMap.builder()
                    .addMotor("drive")
                    .addWebcam("Webcam 1", frame -> frame.fillGrey(shade))
                    .build();
        }
    }

    private LocalDashboardBackend backend;

    @AfterEach
    void tearDown() {
        if (backend != null) {
            backend.close();
        }
    }

    @Test
    void aCameraIsLiveBeforeAnyOpModeRuns() {
        backend = sessionOn(new CountingRobot());

        FrameSource frames = backend.cameraFrames();

        assertNotNull(frames, "the camera view has to work on a session with no OpMode selected");
        assertEquals(1, renderedShade(frames));
    }

    /**
     * Every run builds a new robot, so the view has to follow. A frame source captured once would
     * keep rendering from the pose of the robot the previous run was driving - a view that looks
     * live and is lying, which is worse than no view.
     */
    @Test
    void theViewFollowsTheRobotAcrossRuns() {
        CountingRobot robot = new CountingRobot();
        backend = sessionOn(robot);
        assertEquals(1, renderedShade(backend.cameraFrames()));

        backend.initOpMode(TickingTeleOp.class.getName());
        assertEquals(2, renderedShade(backend.cameraFrames()));

        backend.stop();
        assertEquals(3, renderedShade(backend.cameraFrames()),
            "stopping an OpMode has to leave a fresh robot behind, not a dead camera");
    }

    @Test
    void aRobotWithNoWebcamHasNoCameraView() {
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

        assertNull(backend.cameraFrames());
        assertNull(new CameraViewFrames(backend).nextFrame(),
                "a blind robot must serve no stream, so the endpoint can refuse honestly");
    }

    /** The panel gets JPEG bytes, which is all the browser knows how to consume. */
    @Test
    void framesArriveAsJpeg() {
        backend = sessionOn(new CountingRobot());

        byte[] jpeg = new CameraViewFrames(backend).nextFrame();

        assertNotNull(jpeg);
        // SOI and EOI markers: a browser rejects anything else, however plausible the length.
        assertEquals((byte) 0xFF, jpeg[0]);
        assertEquals((byte) 0xD8, jpeg[1]);
        assertEquals((byte) 0xFF, jpeg[jpeg.length - 2]);
        assertEquals((byte) 0xD9, jpeg[jpeg.length - 1]);
        assertNotEquals(0, jpeg.length);
    }

    private static LocalDashboardBackend sessionOn(SimulatedRobot robot) {
        Supplier<com.qualcomm.robotcore.eventloop.opmode.OpMode> factory = TickingTeleOp::new;
        OpModeInfo info = new OpModeInfo(
                "Ticking TeleOp", "", "TeleOp", TickingTeleOp.class.getName());
        return new LocalDashboardBackend(
                robot, Collections.singletonList(new OpModeEntry(info, factory)));
    }

    /** Which robot's camera this is, read straight out of a rendered pixel. */
    private static int renderedShade(FrameSource frames) {
        SyntheticFrame frame = new SyntheticFrame(8, 8);
        frames.render(frame);
        return frame.redAt(4, 4);
    }
}
