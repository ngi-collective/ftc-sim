package org.ngicollective.testframework.vision;

import org.firstinspires.ftc.robotcore.external.hardware.camera.WebcamName;
import org.ngicollective.testframework.hardware.FakeWebcam;
import org.openftc.easyopencv.OpenCvCameraFactory;
import org.openftc.easyopencv.OpenCvInternalCamera;
import org.openftc.easyopencv.OpenCvInternalCamera2;
import org.openftc.easyopencv.OpenCvSwitchableWebcam;
import org.openftc.easyopencv.OpenCvWebcam;

/**
 * Hands out simulated cameras in place of real ones.
 *
 * <p>{@code VisionPortalImpl} builds its camera with
 * {@code OpenCvCameraFactory.getInstance().createWebcam(...)}, reading the factory afresh for
 * every portal. Replace the factory and every {@code VisionPortal} an OpMode builds gets a
 * simulated camera, with no change to the OpMode and no change to the SDK.</p>
 *
 * <p>Only webcams are simulated. The phone-camera and switchable-camera methods throw rather than
 * returning something half-working: a team running this framework has one simulated camera on a
 * mount, and an OpMode that asked for a built-in phone camera has misunderstood something that a
 * silent stub would let it keep misunderstanding.</p>
 */
public class SyntheticCameraFactory extends OpenCvCameraFactory {

    @Override
    public OpenCvWebcam createWebcam(WebcamName cameraName) {
        return new SyntheticWebcam(require(cameraName));
    }

    @Override
    public OpenCvWebcam createWebcam(WebcamName cameraName, int viewportContainerId) {
        return new SyntheticWebcam(require(cameraName), viewportContainerId);
    }

    /**
     * The simulated camera behind a name, or a failure that says what went wrong.
     *
     * <p>Reaching this with a real {@code WebcamName} means the factory was installed but the
     * hardware map was not simulated, which is worth a clear message: the alternative symptom is
     * a camera that opens and streams nothing.</p>
     */
    private static FakeWebcam require(WebcamName cameraName) {
        if (cameraName instanceof FakeWebcam) {
            return (FakeWebcam) cameraName;
        }
        throw new IllegalArgumentException("the simulated camera factory was asked for \""
                + cameraName + "\", which is not a simulated webcam. The hardware map an OpMode"
                + " ran against was not the simulated one.");
    }

    @Override
    public OpenCvInternalCamera createInternalCamera(OpenCvInternalCamera.CameraDirection direction) {
        throw phoneCamerasNotSimulated();
    }

    @Override
    public OpenCvInternalCamera createInternalCamera(OpenCvInternalCamera.CameraDirection direction,
                                                     int viewportContainerId) {
        throw phoneCamerasNotSimulated();
    }

    @Override
    public OpenCvInternalCamera2 createInternalCamera2(
            OpenCvInternalCamera2.CameraDirection direction) {
        throw phoneCamerasNotSimulated();
    }

    @Override
    public OpenCvInternalCamera2 createInternalCamera2(
            OpenCvInternalCamera2.CameraDirection direction, int viewportContainerId) {
        throw phoneCamerasNotSimulated();
    }

    @Override
    public OpenCvSwitchableWebcam createSwitchableWebcam(WebcamName... names) {
        throw new UnsupportedOperationException(
                "the simulator has one camera; switchable webcams are not simulated");
    }

    @Override
    public OpenCvSwitchableWebcam createSwitchableWebcam(int viewportContainerId,
                                                         WebcamName... names) {
        throw new UnsupportedOperationException(
                "the simulator has one camera; switchable webcams are not simulated");
    }

    @Override
    public int[] splitLayoutForMultipleViewports(int containerId, int numViewports,
                                                 ViewportSplitMethod splitMethod) {
        throw new UnsupportedOperationException(
                "the simulator renders one viewport; splitting it is not simulated");
    }

    private static UnsupportedOperationException phoneCamerasNotSimulated() {
        return new UnsupportedOperationException("only webcams are simulated; this OpMode asked"
                + " for a built-in phone camera, which a Control Hub does not have either");
    }
}
