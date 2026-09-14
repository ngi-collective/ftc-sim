package org.ngicollective.testframework.vision;

import org.firstinspires.ftc.robotcore.external.android.util.Size;
import org.firstinspires.ftc.robotcore.internal.camera.calibration.CameraCalibration;
import org.firstinspires.ftc.robotcore.internal.camera.calibration.CameraCalibrationHelper;
import org.firstinspires.ftc.robotcore.internal.camera.calibration.CameraCalibrationIdentity;
import org.firstinspires.ftc.robotcore.internal.camera.calibration.VendorProductCalibrationIdentity;
import org.firstinspires.ftc.robotcore.internal.usb.UsbConstants;
import org.ngicollective.testframework.camera.CameraIntrinsics;

import java.util.List;

/**
 * The lens the simulated camera pretends to have, taken from the SDK's own calibration data.
 *
 * <h2>Why impersonate a real webcam</h2>
 *
 * <p>{@code VisionPortal} decides a processor's calibration for it: it asks the camera for a
 * {@link CameraCalibrationIdentity} and looks that identity up in the SDK's built-in calibration
 * table. Return no identity and the lookup yields nothing, at which point
 * {@code AprilTagProcessor} logs "pose estimates will likely be inaccurate" and solves with
 * {@code fx = fy = cx = cy = 0}. An OpMode would still run, still report detections, and every
 * pose would be nonsense &mdash; the worst possible failure for a simulator whose purpose is to be
 * trusted.</p>
 *
 * <p>So the simulated camera claims to be a Logitech C920, a camera the SDK ships a real
 * calibration for and that teams actually own, and then <em>reads that calibration back</em> to
 * configure the renderer. The projection the frames are drawn through and the projection the pose
 * solver inverts are therefore the same numbers from the same source, which is the one property
 * that has to hold. Anything else is two sets of optics that agree only by luck.</p>
 *
 * <p>The C920 rather than the C270 because the SDK calibrates it at six resolutions rather than
 * one, so an OpMode that picks 800x600 or 1920x1080 still gets a real calibration instead of a
 * scaled guess.</p>
 */
public final class WebcamCalibrations {

    /** The camera the simulator impersonates. */
    private static final CameraCalibrationIdentity SIMULATED_CAMERA =
            new VendorProductCalibrationIdentity(
                    UsbConstants.VENDOR_ID_LOGITECH, UsbConstants.PRODUCT_ID_LOGITECH_C920);

    private WebcamCalibrations() {
    }

    /** The identity a simulated webcam reports, so the SDK can find a calibration for it. */
    public static CameraCalibrationIdentity simulatedCameraIdentity() {
        return SIMULATED_CAMERA;
    }

    /**
     * The intrinsics the SDK will hand the processors at this resolution.
     *
     * @throws IllegalStateException if the SDK has no usable calibration for the requested size.
     *     Failing loudly is the point: the alternative is rendering through one lens while the
     *     detector solves through another, which produces plausible-looking poses that are
     *     quietly wrong.
     */
    public static CameraIntrinsics forResolution(int width, int height) {
        CameraCalibration calibration = CameraCalibrationHelper.getInstance()
                .getCalibration(SIMULATED_CAMERA, width, height);

        if (calibration == null || calibration.isDegenerate()) {
            throw new IllegalStateException("the simulated camera has no calibration for "
                    + width + "x" + height + "; the SDK calibrates it at " + supportedSizes()
                    + ". Ask the OpMode for one of those resolutions, or impersonate a different"
                    + " camera in WebcamCalibrations.");
        }

        return CameraIntrinsics.of(width, height,
                calibration.focalLengthX, calibration.focalLengthY,
                calibration.principalPointX, calibration.principalPointY);
    }

    /** The resolutions the impersonated camera is calibrated at, for a failure message. */
    private static String supportedSizes() {
        StringBuilder sizes = new StringBuilder();
        List<CameraCalibration> known =
                CameraCalibrationHelper.getInstance().getCalibrations(SIMULATED_CAMERA);
        for (CameraCalibration calibration : known) {
            Size size = calibration.getSize();
            if (sizes.length() > 0) {
                sizes.append(", ");
            }
            sizes.append(size.getWidth()).append('x').append(size.getHeight());
        }
        return sizes.length() == 0 ? "(none found)" : sizes.toString();
    }
}
