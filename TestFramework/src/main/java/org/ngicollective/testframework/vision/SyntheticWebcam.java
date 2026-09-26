package org.ngicollective.testframework.vision;

import com.qualcomm.robotcore.util.RobotLog;

import org.firstinspires.ftc.robotcore.external.hardware.camera.controls.CameraControl;
import org.firstinspires.ftc.robotcore.external.hardware.camera.controls.ExposureControl;
import org.firstinspires.ftc.robotcore.external.hardware.camera.controls.FocusControl;
import org.firstinspires.ftc.robotcore.external.hardware.camera.controls.GainControl;
import org.firstinspires.ftc.robotcore.external.hardware.camera.controls.PtzControl;
import org.firstinspires.ftc.robotcore.external.hardware.camera.controls.WhiteBalanceControl;
import org.firstinspires.ftc.robotcore.internal.camera.calibration.CameraCalibrationIdentity;
import org.ngicollective.testframework.camera.CameraIntrinsics;
import org.ngicollective.testframework.camera.FrameSource;
import org.ngicollective.testframework.camera.SceneFrameSource;
import org.ngicollective.testframework.camera.SimulatedCamera;
import org.ngicollective.testframework.camera.SyntheticFrame;
import org.ngicollective.testframework.hardware.FakeWebcam;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.openftc.easyopencv.OpenCvCameraBase;
import org.openftc.easyopencv.OpenCvCameraRotation;
import org.openftc.easyopencv.OpenCvWebcam;

/**
 * A camera that renders its own frames, wearing EasyOpenCV's webcam interface.
 *
 * <p>This is what makes an unmodified OpMode work. {@code VisionPortal} builds its camera through
 * {@code OpenCvCameraFactory}, so a camera that satisfies that interface is indistinguishable from
 * a real one: the portal's own state machine, its {@code ProcessingPipeline}, the real
 * {@code VisionProcessor} implementations, {@code getFps()}, and the Driver Station's camera
 * stream all run exactly as they do on a robot.</p>
 *
 * <p>Extending {@link OpenCvCameraBase} rather than implementing {@link OpenCvWebcam} from
 * scratch is the whole reason for that last part. The base class already owns the viewport, the
 * frame timing statistics and the {@code CameraStreamSource} the Driver Station reads, so a
 * student sees their contours drawn on the robot controller screen and in the Driver Station,
 * which is how anyone actually debugs a vision pipeline.</p>
 *
 * <p>The camera controls are the honest exception. A rendered camera has no exposure, focus, pan,
 * gain or white balance to set, and returning a stub that silently accepted settings would be
 * worse than refusing: an OpMode that tunes exposure to find a game element would appear to work
 * and change nothing. {@code null} is what the SDK's own code already checks for.</p>
 */
public class SyntheticWebcam extends OpenCvCameraBase implements OpenCvWebcam {

    public static final String TAG = "SyntheticWebcam";

    private final FakeWebcam name;

    private volatile boolean open;
    private volatile boolean streaming;

    private SyntheticFrame frame;
    private Mat rgba;

    public SyntheticWebcam(FakeWebcam name) {
        super();
        this.name = name;
    }

    public SyntheticWebcam(FakeWebcam name, int viewportContainerId) {
        super(viewportContainerId);
        this.name = name;
    }

    @Override
    public int openCameraDevice() {
        // OpenCvCameraBase builds its frame timers here; handleFrame dereferences them, so
        // skipping this is a null pointer exception on the first frame rather than a warning.
        prepareForOpenCameraDevice();
        open = true;
        return 0;
    }

    @Override
    public void openCameraDeviceAsync(AsyncCameraOpenListener listener) {
        // Synchronously, on the caller's thread. There is no hardware to wait for, and a
        // needless thread hop would only make a test's failure less legible.
        int result = openCameraDevice();
        if (result == 0) {
            listener.onOpened();
        } else {
            listener.onError(result);
        }
    }

    @Override
    public void closeCameraDevice() {
        stopStreaming();
        open = false;
    }

    @Override
    public void closeCameraDeviceAsync(AsyncCameraCloseListener listener) {
        closeCameraDevice();
        listener.onClose();
    }

    @Override
    public void startStreaming(int width, int height) {
        startStreaming(width, height, getDefaultRotation());
    }

    @Override
    public void startStreaming(int width, int height, OpenCvCameraRotation rotation) {
        startStreaming(width, height, rotation, StreamFormat.YUY2);
    }

    @Override
    public synchronized void startStreaming(int width, int height, OpenCvCameraRotation rotation,
                                            StreamFormat streamFormat) {
        if (!open) {
            throw new IllegalStateException(
                    "this simulated camera has not been opened; VisionPortal normally does that");
        }
        if (streaming) {
            return;
        }

        // The processors allocate OpenCV Mats in their constructors, so the natives have to be up
        // before any of this. Idempotent, and it turns an UnsatisfiedLinkError thrown from a frame
        // callback into a comprehensible failure here.
        VisionNatives.ensureLoaded();

        // The renderer must draw through the same lens the pose solver will invert. See
        // WebcamCalibrations for why that means reading the SDK's own calibration back.
        CameraIntrinsics calibrated = WebcamCalibrations.forResolution(width, height);
        FrameSource source = name.frameSource();
        if (source instanceof SceneFrameSource) {
            SceneFrameSource scene = (SceneFrameSource) source;
            scene.setCamera(new SimulatedCamera(name.configuredName(), calibrated,
                    scene.camera().mount()));
        }

        prepareForStartStreaming(width, height, rotation);
        frame = new SyntheticFrame(width, height);
        rgba = new Mat(height, width, CvType.CV_8UC4);

        name.setFrameListener(new FakeWebcam.FrameListener() {
            @Override
            public void onFrameDue(long timestampNanos) {
                deliverFrame(timestampNanos);
            }
        });
        name.state().setStreaming(true);
        streaming = true;
        RobotLog.ii(TAG, "streaming %dx%d from a simulated scene through %s",
                width, height, calibrated);
    }

    @Override
    public synchronized void stopStreaming() {
        if (!streaming) {
            return;
        }
        streaming = false;
        name.state().setStreaming(false);
        name.setFrameListener(null);
    }

    /** Renders one frame and hands it to the pipeline, exactly as a camera callback would. */
    private synchronized void deliverFrame(long timestampNanos) {
        if (!streaming) {
            return;
        }
        name.frameSource().render(frame);
        // The frame's bytes are already in the RGBA order Mat expects, so this is a copy and
        // nothing more; the processors need RGBA because they convert with COLOR_RGBA2GRAY and
        // COLOR_RGBA2RGB, both of which reject three-channel input.
        rgba.put(0, 0, frame.rgba());
        handleFrame(rgba, timestampNanos);
    }

    @Override
    protected OpenCvCameraRotation getDefaultRotation() {
        return OpenCvCameraRotation.SENSOR_NATIVE;
    }

    @Override
    protected int mapRotationEnumToOpenCvRotateCode(OpenCvCameraRotation rotation) {
        // -1 means "do not rotate". A rendered camera is already in the orientation it was asked
        // for, because the mount decides where it points.
        return -1;
    }

    @Override
    protected boolean cameraOrientationIsTiedToDeviceOrientation() {
        return false;
    }

    @Override
    protected boolean isStreaming() {
        return streaming;
    }

    @Override
    public CameraCalibrationIdentity getCalibrationIdentity() {
        return WebcamCalibrations.simulatedCameraIdentity();
    }

    @Override
    public void setMillisecondsPermissionTimeout(int ms) {
        // No permission to wait for.
    }

    @Override
    public ExposureControl getExposureControl() {
        return null;
    }

    @Override
    public FocusControl getFocusControl() {
        return null;
    }

    @Override
    public PtzControl getPtzControl() {
        return null;
    }

    @Override
    public GainControl getGainControl() {
        return null;
    }

    @Override
    public WhiteBalanceControl getWhiteBalanceControl() {
        return null;
    }

    @Override
    public <T extends CameraControl> T getControl(Class<T> controlType) {
        return null;
    }
}
