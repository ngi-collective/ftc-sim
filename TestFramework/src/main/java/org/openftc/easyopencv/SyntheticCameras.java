package org.openftc.easyopencv;

import org.ngicollective.testframework.vision.SyntheticCameraFactory;

/**
 * Installs the simulated camera factory into EasyOpenCV.
 *
 * <h2>Why this class lives in someone else's package</h2>
 *
 * <p>{@code VisionPortal.Builder.build()} constructs {@code new VisionPortalImpl(...)} directly,
 * so the portal cannot be subclassed or substituted. What it does do, on every portal, is read
 * {@code OpenCvCameraFactory.getInstance()}, which is a plain read of a static field:</p>
 *
 * <pre>
 *   static org.openftc.easyopencv.OpenCvCameraFactory theInstance;
 *     flags: (0x0008) ACC_STATIC
 * </pre>
 *
 * <p>Package-private, and deliberately noted: <em>not</em> final. A class declared in this package
 * can therefore assign it with an ordinary field write &mdash; no reflection, no
 * {@code setAccessible}, nothing that a future Android release or R8 pass can take away. This
 * class is that one line, and it is the entire mechanism by which an OpMode written from a stock
 * FTC sample runs unmodified against a simulated camera.</p>
 *
 * <p>The alternatives were tried and rejected. Shipping our own
 * {@code org.firstinspires.ftc.vision.VisionPortal} fails D8 with "type is defined multiple
 * times" and no packaging or classpath arrangement avoids it. Supplying only a fake
 * {@code WebcamName} reaches the SDK's real {@code UsbResiliantWebcam} and lands in
 * {@code CameraState.ERROR}. Asking OpModes to call a test-only builder was ruled out by
 * ADR 0001: students write sample-derived code and will not adapt it for a test framework.</p>
 *
 * <p>Because this sits in a vendor package, an SDK upgrade that renames or finalises the field is
 * a <em>compile error</em> here rather than a silent behavioural change &mdash; which is the
 * property that makes this safe to depend on.</p>
 */
public final class SyntheticCameras {

    private SyntheticCameras() {
    }

    /**
     * Makes every {@code VisionPortal} built from now on use a simulated camera.
     *
     * <p>Idempotent, and safe to call before the first portal exists: the field is read per
     * portal, not cached at class initialisation.</p>
     */
    public static void install() {
        OpenCvCameraFactory.theInstance = new SyntheticCameraFactory();
    }

    /** Whether the simulated factory is the one EasyOpenCV would hand out. */
    public static boolean isInstalled() {
        return OpenCvCameraFactory.theInstance instanceof SyntheticCameraFactory;
    }
}
