package org.ngicollective.testframework.hardware;

import com.qualcomm.robotcore.hardware.HardwareDevice;
import com.qualcomm.robotcore.util.SerialNumber;

import org.firstinspires.ftc.robotcore.external.function.Consumer;
import org.firstinspires.ftc.robotcore.external.function.Continuation;
import org.firstinspires.ftc.robotcore.external.function.ContinuationResult;
import org.firstinspires.ftc.robotcore.external.hardware.camera.CameraCharacteristics;
import org.firstinspires.ftc.robotcore.external.hardware.camera.WebcamName;
import org.firstinspires.ftc.robotcore.internal.system.Deadline;
import org.ngicollective.testframework.behavior.Behavior;
import org.ngicollective.testframework.behavior.CameraBehaviors;
import org.ngicollective.testframework.behavior.CameraState;
import org.ngicollective.testframework.camera.FrameSource;

/**
 * A simulated webcam, as the hardware map sees it.
 *
 * <p>An OpMode's {@code hardwareMap.get(WebcamName.class, "Webcam 1")} has to return something
 * before {@code VisionPortal} is ever involved, and this is it. It is also where the camera's
 * frames come from: whoever builds the camera plumbing asks this device for its
 * {@link FrameSource}, so the name a student writes in an OpMode is what decides what that camera
 * sees.</p>
 *
 * <p>Frames are emitted from {@link #advance}, off the simulated clock, which is what makes a
 * vision run repeatable rather than dependent on how fast the host happens to be.</p>
 *
 * <p>Most of {@link WebcamName} is answered with the simplest true thing. {@code VisionPortal}
 * never asks for the serial number, USB device name, attachment state or camera characteristics
 * &mdash; verified against the SDK's own source &mdash; so elaborating them would be inventing
 * detail no caller reads. What it <em>does</em> ask is {@link #isWebcam()}, and the answer has to
 * be yes or the portal takes a different branch entirely.</p>
 */
public class FakeWebcam extends FakeDevice<CameraState> implements WebcamName {

    /** Told when the simulated clock says a frame is due. */
    public interface FrameListener {

        /**
         * @param timestampNanos when the frame was taken, on the simulated clock, so that a
         *     recorded run replays with the same timestamps it was captured with
         */
        void onFrameDue(long timestampNanos);
    }

    private final FrameSource frameSource;
    private volatile FrameListener listener;

    FakeWebcam(String configuredName, FrameSource frameSource, double framesPerSecond,
               Behavior<CameraState> behavior) {
        super(configuredName, new CameraState(framesPerSecond), behavior);
        this.frameSource = frameSource;
    }

    FakeWebcam(String configuredName, FrameSource frameSource, double framesPerSecond) {
        this(configuredName, frameSource, framesPerSecond, CameraBehaviors.streaming());
    }

    /** Where this camera's pixels come from. */
    public FrameSource frameSource() {
        return frameSource;
    }

    /**
     * Registers the camera plumbing that wants frames, or {@code null} to detach it.
     *
     * <p>One listener, not a list: this models a physical camera, and a second consumer of the
     * same sensor is not a thing that exists.</p>
     */
    public void setFrameListener(FrameListener listener) {
        this.listener = listener;
    }

    @Override
    public void advance(double elapsedSeconds) {
        super.advance(elapsedSeconds);

        FrameListener current = listener;
        if (current == null) {
            // Nothing is consuming this camera, so nothing was rendered. Forgetting the frames is
            // what stops a burst arriving the moment something attaches.
            state().dropFramesDue();
            return;
        }
        int due = state().takeFramesDue();
        for (int i = 0; i < due; i++) {
            current.onFrameDue((long) (state().elapsedSeconds() * 1_000_000_000L));
        }
    }

    @Override
    public String getDeviceName() {
        return "Simulated Webcam";
    }

    @Override
    public void resetDeviceConfigurationForOpMode() {
        // Nothing on a rendered camera is configurable, so there is nothing to put back.
    }

    @Override
    public boolean isWebcam() {
        return true;
    }

    @Override
    public boolean isCameraDirection() {
        return false;
    }

    @Override
    public boolean isSwitchable() {
        return false;
    }

    @Override
    public boolean isUnknown() {
        return false;
    }

    @Override
    public SerialNumber getSerialNumber() {
        return SerialNumber.createFake();
    }

    @Override
    public String getUsbDeviceNameIfAttached() {
        return null;
    }

    @Override
    public boolean isAttached() {
        return true;
    }

    @Override
    public boolean requestCameraPermission(Deadline deadline) {
        return true;
    }

    @Override
    public void asyncRequestCameraPermission(android.content.Context context, Deadline deadline,
                                             Continuation<? extends Consumer<Boolean>>
                                                     continuation) {
        // A simulated camera is always permitted: there is no operating system to ask.
        continuation.dispatch(new ContinuationResult<Consumer<Boolean>>() {
            @Override
            public void handle(Consumer<Boolean> consumer) {
                consumer.accept(true);
            }
        });
    }

    @Override
    public CameraCharacteristics getCameraCharacteristics() {
        // Nothing on the VisionPortal path reads this; a simulated camera has no UVC descriptors
        // to report, and inventing some would be detail with no reader.
        return null;
    }

    @Override
    public Manufacturer getManufacturer() {
        return HardwareDevice.Manufacturer.Other;
    }
}
