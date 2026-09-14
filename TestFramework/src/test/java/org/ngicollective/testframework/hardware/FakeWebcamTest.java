package org.ngicollective.testframework.hardware;

import org.junit.jupiter.api.Test;
import org.ngicollective.testframework.camera.FrameSource;
import org.ngicollective.testframework.camera.SyntheticFrame;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * That a simulated camera's frames are paced by simulated time, not by the host.
 *
 * <p>This is what makes a vision run repeatable. Run the same scenario twice and the camera
 * delivers frames at the same simulated instants both times; a camera paced off the wall clock
 * would hand a slow machine fewer frames and quietly change what an OpMode saw.</p>
 */
class FakeWebcamTest {

    /** Counts renders; the pixels are irrelevant to pacing. */
    private static final class CountingSource implements FrameSource {

        private int renders;

        @Override
        public void render(SyntheticFrame frame) {
            renders++;
        }
    }

    private static FakeHardwareMap mapWithCamera(FrameSource source, double fps) {
        return FakeHardwareMap.builder().addWebcam("Webcam 1", source, fps).build();
    }

    @Test
    void aStreamingCameraDeliversItsFrameRatePerSimulatedSecond() {
        CountingSource source = new CountingSource();
        FakeHardwareMap hardware = mapWithCamera(source, 30.0);
        FakeWebcam webcam = hardware.webcam("Webcam 1");
        List<Long> timestamps = new ArrayList<>();
        webcam.setFrameListener(timestamps::add);
        webcam.state().setStreaming(true);

        hardware.advance(1.0);

        assertEquals(30, timestamps.size(), "one simulated second at 30 fps is 30 frames");
        assertEquals(30, webcam.state().framesDelivered());
    }

    @Test
    void theFrameCountDoesNotDependOnHowTheTimeIsDividedUp() {
        // Fifty ticks of a 50 Hz loop or one big step: the camera owes the same frames either way.
        // Accumulating a remainder per tick rather than rounding is what makes this true.
        CountingSource fine = new CountingSource();
        FakeHardwareMap fineMap = mapWithCamera(fine, 30.0);
        fineMap.webcam("Webcam 1").state().setStreaming(true);
        fineMap.webcam("Webcam 1").setFrameListener(timestamp -> { });

        for (int tick = 0; tick < 50; tick++) {
            fineMap.advance(0.02);
        }

        CountingSource coarse = new CountingSource();
        FakeHardwareMap coarseMap = mapWithCamera(coarse, 30.0);
        coarseMap.webcam("Webcam 1").state().setStreaming(true);
        coarseMap.webcam("Webcam 1").setFrameListener(timestamp -> { });

        coarseMap.advance(1.0);

        assertEquals(coarseMap.webcam("Webcam 1").state().framesDelivered(),
                fineMap.webcam("Webcam 1").state().framesDelivered());
    }

    @Test
    void framesAreStampedWithSimulatedTime() {
        CountingSource source = new CountingSource();
        FakeHardwareMap hardware = mapWithCamera(source, 10.0);
        FakeWebcam webcam = hardware.webcam("Webcam 1");
        List<Long> timestamps = new ArrayList<>();
        webcam.setFrameListener(timestamps::add);
        webcam.state().setStreaming(true);

        hardware.advance(0.5);

        assertEquals(5, timestamps.size());
        // A recorded run has to replay with the timestamps it was captured with, so these come
        // off the simulated clock rather than System.nanoTime.
        for (int i = 1; i < timestamps.size(); i++) {
            assertTrue(timestamps.get(i) >= timestamps.get(i - 1),
                    "timestamps must not go backwards: " + timestamps);
        }
        assertTrue(timestamps.get(timestamps.size() - 1) <= 500_000_000L,
                "half a simulated second is 5e8 ns, got " + timestamps);
    }

    @Test
    void aCameraThatIsNotStreamingOwesNothing() {
        // Before VisionPortal starts a stream, and after it stops one. A camera that banked frames
        // while stopped would deliver them all at once the moment it restarted.
        CountingSource source = new CountingSource();
        FakeHardwareMap hardware = mapWithCamera(source, 30.0);
        FakeWebcam webcam = hardware.webcam("Webcam 1");
        List<Long> timestamps = new ArrayList<>();
        webcam.setFrameListener(timestamps::add);

        hardware.advance(1.0);
        assertEquals(0, timestamps.size(), "a stopped camera should deliver nothing");

        webcam.state().setStreaming(true);
        hardware.advance(0.1);
        int afterStarting = timestamps.size();
        assertTrue(afterStarting > 0, "a started camera should deliver frames");

        webcam.state().setStreaming(false);
        hardware.advance(1.0);
        assertEquals(afterStarting, timestamps.size(),
                "stopping should stop frames, not bank them");

        webcam.state().setStreaming(true);
        hardware.advance(1.0 / 30.0);
        assertEquals(afterStarting + 1, timestamps.size(),
                "restarting should not flush a backlog accrued while stopped");
    }

    @Test
    void aCameraWithNoListenerRendersNothingAndBanksNothing() {
        // The rendering cost is only paid for a camera something is actually consuming, and the
        // frames that went unrendered must not arrive in a burst when one attaches.
        CountingSource source = new CountingSource();
        FakeHardwareMap hardware = mapWithCamera(source, 30.0);
        FakeWebcam webcam = hardware.webcam("Webcam 1");
        webcam.state().setStreaming(true);

        hardware.advance(1.0);
        assertEquals(0, source.renders);

        List<Long> timestamps = new ArrayList<>();
        webcam.setFrameListener(timestamps::add);
        hardware.advance(1.0 / 30.0);

        assertEquals(1, timestamps.size(),
                "attaching a listener should not flush a second of unrendered frames");
    }
}
