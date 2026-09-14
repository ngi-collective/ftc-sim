package org.ngicollective.testframework.vision;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.Manifest;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.os.Environment;
import android.util.Log;
import android.util.Size;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.rule.GrantPermissionRule;

import org.firstinspires.ftc.robotcore.internal.camera.calibration.CameraCalibration;
import org.firstinspires.ftc.vision.VisionProcessor;
import org.firstinspires.ftc.vision.apriltag.AprilTagDetection;
import org.firstinspires.ftc.vision.apriltag.AprilTagProcessor;
import org.firstinspires.ftc.vision.opencv.ColorBlobLocatorProcessor;
import org.firstinspires.ftc.vision.opencv.ColorRange;
import org.firstinspires.ftc.vision.opencv.ImageRegion;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.opencv.android.OpenCVLoader;
import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Drives {@link LocalVisionHost} against whatever camera the device actually has.
 *
 * <p>This is the test that the emulator execution target's vision path exists at all: it needs a
 * real Android runtime for the OpenCV/AprilTag natives, and a real camera behind Camera2. On an
 * emulator that means an AVD built from a camera-capable system image (the AOSP {@code default}
 * images have no camera HAL) with {@code hw.camera.back} pointed at a host webcam.</p>
 */
@RunWith(AndroidJUnit4.class)
public class LocalVisionHostCameraTest {

    private static final long FRAME_TIMEOUT_SECONDS = 20;

    /**
     * Enough frames to cover a webcam's startup: a USB camera's first frames are commonly black
     * while it ramps exposure, so asserting on content needs a window, not the first frame.
     */
    private static final int FRAMES_WANTED = 45;

    @Rule
    public GrantPermissionRule permissions = GrantPermissionRule.grant(
            Manifest.permission.CAMERA, Manifest.permission.WRITE_EXTERNAL_STORAGE);

    /**
     * The SDK's processors allocate OpenCV {@code Mat}s in their own constructors, so the native
     * library has to be up before any of them is built. In the app that happens at robot start,
     * through the {@code @OpModeRegistrar} hook in {@code org.openftc.opencvrepackaged.LibLoader};
     * a test has no robot, so it does the same thing itself.
     */
    @BeforeClass
    public static void loadOpenCv() {
        assertTrue("OpenCV native library failed to load", OpenCVLoader.initDebug());
    }

    /** Records what the SPI actually handed over, so the contract can be asserted on. */
    private static final class RecordingProcessor implements VisionProcessor {

        final CountDownLatch frames = new CountDownLatch(FRAMES_WANTED);
        final List<String> frameShapes = new CopyOnWriteArrayList<>();
        final List<Long> captureTimes = new CopyOnWriteArrayList<>();
        volatile int initWidth;
        volatile int initHeight;
        volatile CameraCalibration initCalibration;
        volatile double brightestMean;
        volatile int drawCalls;

        @Override
        public void init(int width, int height, CameraCalibration calibration) {
            initWidth = width;
            initHeight = height;
            initCalibration = calibration;
        }

        @Override
        public Object processFrame(Mat frame, long captureTimeNanos) {
            frameShapes.add(
                    frame.cols() + "x" + frame.rows() + "/" + CvType.typeToString(frame.type()));
            captureTimes.add(captureTimeNanos);
            double mean = Core.mean(frame).val[0];
            brightestMean = Math.max(brightestMean, mean);
            Log.i(LocalVisionHost.TAG, "frame " + frameShapes.size() + " mean=" + mean);
            frames.countDown();
            return "context-" + frameShapes.size();
        }

        @Override
        public void onDrawFrame(Canvas canvas, int onscreenWidth,
                int onscreenHeight, float scaleBmpPxToCanvasPx, float scaleCanvasDensity,
                Object userContext) {
            drawCalls++;
        }
    }

    @Test
    public void streamsRealFramesThroughRealProcessors() throws Exception {
        // The genuine SDK processors, unmodified: the whole point is that these run untouched.
        AprilTagProcessor aprilTag = AprilTagProcessor.easyCreateWithDefaults();
        ColorBlobLocatorProcessor colorLocator = new ColorBlobLocatorProcessor.Builder()
                .setTargetColorRange(ColorRange.BLUE)
                .setRoi(ImageRegion.entireFrame())
                .setContourMode(ColorBlobLocatorProcessor.ContourMode.EXTERNAL_ONLY)
                .setDrawContours(true)
                .build();
        RecordingProcessor recorder = new RecordingProcessor();

        List<VisionFrame> published = new CopyOnWriteArrayList<>();
        LocalVisionHost host = LocalVisionHost.builder()
                .addProcessor(aprilTag)
                .addProcessor(colorLocator)
                .addProcessor(recorder)
                .setCameraResolution(new Size(640, 480))
                .setContext(InstrumentationRegistry.getInstrumentation().getTargetContext())
                .addFrameListener(published::add)
                .build();

        try {
            assertTrue("no frames arrived from the camera within " + FRAME_TIMEOUT_SECONDS + "s",
                    recorder.frames.await(FRAME_TIMEOUT_SECONDS, TimeUnit.SECONDS));

            Size resolution = host.getCameraResolution();

            // The processors are sized before they are fed, and fed what they expect: RGBA, since
            // the SDK's own processors run conversions that reject a 3-channel frame.
            assertEquals(resolution.getWidth(), recorder.initWidth);
            assertEquals(resolution.getHeight(), recorder.initHeight);
            assertNotNull("processors must be handed a calibration", recorder.initCalibration);
            String expectedShape =
                    resolution.getWidth() + "x" + resolution.getHeight() + "/CV_8UC4";
            for (String shape : recorder.frameShapes) {
                assertEquals(expectedShape, shape);
            }

            // The preview path renders through each processor's own onDrawFrame overlay hook.
            assertTrue("no annotated frames were published", published.size() > 0);
            assertTrue("overlay hook was never called", recorder.drawCalls > 0);
            VisionFrame frame = published.get(published.size() - 1);
            Bitmap decoded =
                    BitmapFactory.decodeByteArray(frame.getJpeg(), 0, frame.getJpeg().length);
            assertNotNull("published frame was not decodable JPEG", decoded);
            assertEquals(resolution.getWidth(), decoded.getWidth());
            assertEquals(resolution.getHeight(), decoded.getHeight());

            // Before the content assertions, so a black-frame failure still leaves the evidence.
            saveForInspection(frame);

            // Frames are live capture, not one buffer handed over repeatedly.
            assertTrue("camera timestamps must advance",
                    recorder.captureTimes.get(recorder.captureTimes.size() - 1)
                            > recorder.captureTimes.get(0));
            assertTrue("fps must be measurable", host.getFps() > 0);
            assertTrue("frames are uniformly black: the host camera is not actually being "
                            + "captured. On macOS, the terminal or IDE that launched the emulator "
                            + "needs Camera access in System Settings > Privacy & Security.",
                    recorder.brightestMean > 0);

            // The real processors survived real frames, which is what cannot be faked. Whether
            // they found anything depends on where the camera is pointed, so it is logged rather
            // than asserted: a test that demands a tag in view fails on an unaimed camera.
            assertNotNull(aprilTag.getDetections());
            assertNotNull(colorLocator.getBlobs());
            Log.i(LocalVisionHost.TAG, "detections: " + aprilTag.getDetections().size()
                    + " april tag(s) " + describe(aprilTag.getDetections())
                    + ", " + colorLocator.getBlobs().size() + " blob(s)");
        } finally {
            host.close();
        }
    }

    private static String describe(List<AprilTagDetection> detections) {
        StringBuilder text = new StringBuilder("[");
        for (AprilTagDetection detection : detections) {
            if (text.length() > 1) {
                text.append(", ");
            }
            text.append("id=").append(detection.id)
                    .append(" centre=").append((int) detection.center.x)
                    .append(',').append((int) detection.center.y);
        }
        return text.append(']').toString();
    }

    /**
     * Writes the annotated frame where a human can look at it. Asserting that pixels decode
     * proves the pipeline; only an eyeball proves the camera is pointed at the room rather than
     * at a synthetic test pattern.
     */
    private void saveForInspection(VisionFrame frame) throws IOException {
        // Shared storage, not the app's own directory: Gradle uninstalls the test APK when the
        // run finishes, which would take the evidence with it.
        File directory = new File(Environment.getExternalStorageDirectory(), "Download");
        File file = new File(directory, "local-vision-frame.jpg");
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(frame.getJpeg());
        }
        Log.i(LocalVisionHost.TAG, "wrote annotated frame to " + file);
    }
}
