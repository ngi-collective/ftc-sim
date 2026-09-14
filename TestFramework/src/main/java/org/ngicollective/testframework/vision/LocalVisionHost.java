package org.ngicollective.testframework.vision;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.util.Size;

import org.firstinspires.ftc.robotcore.external.hardware.camera.BuiltinCameraDirection;
import org.firstinspires.ftc.robotcore.internal.camera.calibration.CameraCalibration;
import org.firstinspires.ftc.robotcore.internal.camera.calibration.CameraCalibrationIdentity;
import org.firstinspires.ftc.robotcore.internal.system.AppUtil;
import org.firstinspires.ftc.vision.VisionProcessor;
import org.opencv.android.OpenCVLoader;
import org.opencv.android.Utils;
import org.opencv.core.Mat;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Runs real {@code VisionProcessor} implementations against a real camera, without
 * {@code VisionPortal}.
 *
 * <p>What this replaces and why: {@code VisionPortal} reaches a webcam through
 * {@code WebcamName}, which is built on USB device enumeration and native libuvc. An Android
 * emulator hands the host's webcam to the guest through the Camera2 HAL and never creates a USB
 * device node, so no amount of configuration makes {@code VisionPortal} see it. This class opens
 * that Camera2 device directly and calls {@code init}/{@code processFrame}/{@code onDrawFrame} on
 * each processor itself &mdash; the processors are the unmodified SDK ones, so what runs here is
 * the same detection code that runs on a Control Hub.</p>
 *
 * <p>The cost is the one deliberate exception to this framework's "OpModes run unchanged" rule: a
 * vision OpMode aimed at this host builds a {@code LocalVisionHost} where it would have built a
 * {@code VisionPortal}. Everything downstream &mdash; the processor objects, their
 * {@code getDetections()}/{@code getBlobs()} results, the overlay drawing &mdash; is untouched.</p>
 *
 * <p>Requires an Android runtime: the OpenCV and AprilTag native libraries ship only as Android
 * {@code .so}s, so this works in the emulator execution target and not in plain-JVM tests. The
 * app loads them at robot start via the SDK's own {@code LibLoader} hook.</p>
 *
 * <pre>
 * AprilTagProcessor tags = AprilTagProcessor.easyCreateWithDefaults();
 * LocalVisionHost host = LocalVisionHost.builder()
 *         .addProcessor(tags)
 *         .setCameraResolution(new Size(640, 480))
 *         .build();
 * // ... tags.getDetections() as usual ...
 * host.close();
 * </pre>
 */
public final class LocalVisionHost implements AutoCloseable {

    public static final String TAG = "LocalVisionHost";

    /**
     * Approximate focal length as a fraction of frame width, about a 60&deg; horizontal field of
     * view. Typical of the USB webcams on a dev desk, and close enough that pose solving produces
     * sane numbers; a developer who has calibrated their own camera passes a real calibration.
     */
    private static final double DEFAULT_FOCAL_LENGTH_RATIO = 0.866;

    private static final int DEFAULT_JPEG_QUALITY = 60;
    private static final long OPEN_TIMEOUT_SECONDS = 10;

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {

        private final List<VisionProcessor> processors = new ArrayList<>();
        private final List<VisionFrameListener> frameListeners = new ArrayList<>();
        private Size resolution = new Size(640, 480);
        private BuiltinCameraDirection direction = BuiltinCameraDirection.BACK;
        private CameraCalibration calibration;
        private Context context;
        private int jpegQuality = DEFAULT_JPEG_QUALITY;

        private Builder() {
        }

        /** Processors run in the order they are added, all drawing onto the same overlay. */
        public Builder addProcessor(VisionProcessor processor) {
            if (processor == null) {
                throw new IllegalArgumentException("processor may not be null");
            }
            processors.add(processor);
            return this;
        }

        /**
         * Requested frame size. The closest size the camera actually offers for
         * {@code YUV_420_888} is used, since a webcam's mode list is its own business.
         */
        public Builder setCameraResolution(Size resolution) {
            this.resolution = resolution;
            return this;
        }

        /** Which of the emulator's Camera2 devices to open. Defaults to {@code BACK}. */
        public Builder setCameraDirection(BuiltinCameraDirection direction) {
            this.direction = direction;
            return this;
        }

        /**
         * Overrides the built-in approximate calibration. Pass a real one when pose accuracy,
         * rather than exercising the processor, is the point.
         */
        public Builder setCameraCalibration(CameraCalibration calibration) {
            this.calibration = calibration;
            return this;
        }

        /** Frames are only rendered and encoded when at least one listener is registered. */
        public Builder addFrameListener(VisionFrameListener listener) {
            frameListeners.add(listener);
            return this;
        }

        public Builder setJpegQuality(int jpegQuality) {
            this.jpegQuality = jpegQuality;
            return this;
        }

        /** Defaults to the robot controller application; set explicitly outside the app. */
        public Builder setContext(Context context) {
            this.context = context;
            return this;
        }

        /** Opens the camera and starts streaming. Frames are flowing when this returns. */
        public LocalVisionHost build() {
            if (processors.isEmpty()) {
                throw new IllegalStateException("a LocalVisionHost needs at least one processor");
            }
            Context resolved = context != null ? context : AppUtil.getInstance().getApplication();
            return new LocalVisionHost(resolved, processors, frameListeners, resolution, direction,
                    calibration, jpegQuality);
        }
    }

    /** A processor plus the per-frame state the SPI threads between its two calls. */
    private static final class Registered {
        final VisionProcessor processor;
        volatile boolean enabled = true;
        Object userContext;

        Registered(VisionProcessor processor) {
            this.processor = processor;
        }
    }

    private final CopyOnWriteArrayList<Registered> registered = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<VisionFrameListener> frameListeners =
            new CopyOnWriteArrayList<>();
    private final int jpegQuality;
    private final Size resolution;
    private final Yuv420Rgba converter;
    private final HandlerThread cameraThread;
    private final Handler cameraHandler;
    private final ImageReader imageReader;
    // Not final: assigned inside the constructor's try, so a failed open can unwind cleanly.
    private CameraDevice camera;
    private CameraCaptureSession session;

    /** Allocated on first use, and only when something is listening for frames. */
    private Bitmap overlayBitmap;
    private ByteArrayOutputStream jpegBuffer;

    private volatile long frameCount;
    private volatile long firstFrameNanos;
    private volatile long lastFrameNanos;
    private volatile boolean closed;

    private LocalVisionHost(Context context, List<VisionProcessor> processors,
            List<VisionFrameListener> listeners, Size requestedResolution,
            BuiltinCameraDirection direction, CameraCalibration suppliedCalibration,
            int jpegQuality) {
        this.jpegQuality = jpegQuality;
        this.frameListeners.addAll(listeners);
        for (VisionProcessor processor : processors) {
            registered.add(new Registered(processor));
        }

        CameraManager manager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
        String cameraId = selectCamera(manager, direction);
        this.resolution = chooseResolution(manager, cameraId, requestedResolution);

        int width = resolution.getWidth();
        int height = resolution.getHeight();
        CameraCalibration calibration = suppliedCalibration != null
                ? suppliedCalibration
                : approximateCalibration(width, height);

        // The app normally loads OpenCV at robot start, via the SDK's LibLoader hook. Doing it
        // here too costs nothing (loadLibrary is idempotent) and turns an UnsatisfiedLinkError
        // thrown from a frame callback into a comprehensible failure at construction.
        if (!OpenCVLoader.initDebug()) {
            throw new IllegalStateException("could not load the OpenCV native library; vision "
                    + "processors cannot run without it");
        }

        // Before any frame arrives, so a processor is never handed a frame it has not sized itself
        // for. This is also where a processor's native detector gets built.
        for (Registered entry : registered) {
            entry.processor.init(width, height, calibration);
        }

        this.converter = new Yuv420Rgba(width, height);
        this.cameraThread = new HandlerThread("LocalVisionHost");
        cameraThread.start();
        this.cameraHandler = new Handler(cameraThread.getLooper());

        // Two buffers: one being converted while the HAL fills the next.
        this.imageReader = ImageReader.newInstance(width, height, ImageFormat.YUV_420_888, 2);
        imageReader.setOnImageAvailableListener(this::onImageAvailable, cameraHandler);

        try {
            this.camera = openCamera(manager, cameraId);
            this.session = startSession();
        } catch (RuntimeException e) {
            // A half-open host is worse than no host: unwind what did open.
            closeQuietly();
            throw e;
        }
        Log.i(TAG, String.format("streaming %dx%d from camera %s to %d processor(s)",
                width, height, cameraId, registered.size()));
    }

    private static String selectCamera(CameraManager manager, BuiltinCameraDirection direction) {
        int wanted = direction == BuiltinCameraDirection.FRONT
                ? CameraCharacteristics.LENS_FACING_FRONT
                : CameraCharacteristics.LENS_FACING_BACK;
        try {
            String[] ids = manager.getCameraIdList();
            if (ids.length == 0) {
                throw new IllegalStateException("no Camera2 devices are present. On an emulator, "
                        + "the AVD needs a camera-capable system image (the AOSP 'default' images "
                        + "have none) and hw.camera.back set to a webcam");
            }
            for (String id : ids) {
                Integer facing = manager.getCameraCharacteristics(id)
                        .get(CameraCharacteristics.LENS_FACING);
                if (facing != null && facing == wanted) {
                    return id;
                }
            }
            Log.w(TAG, "no camera faces " + direction + "; falling back to " + ids[0]);
            return ids[0];
        } catch (CameraAccessException e) {
            throw new IllegalStateException("could not enumerate Camera2 devices", e);
        }
    }

    private static Size chooseResolution(CameraManager manager, String cameraId, Size requested) {
        try {
            StreamConfigurationMap map = manager.getCameraCharacteristics(cameraId)
                    .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            Size[] supported = map == null ? null : map.getOutputSizes(ImageFormat.YUV_420_888);
            if (supported == null || supported.length == 0) {
                throw new IllegalStateException(
                        "camera " + cameraId + " offers no YUV_420_888 output sizes");
            }
            Size best = null;
            long bestCost = Long.MAX_VALUE;
            for (Size candidate : supported) {
                if (candidate.equals(requested)) {
                    return candidate;
                }
                long cost = Math.abs((long) candidate.getWidth() * candidate.getHeight()
                        - (long) requested.getWidth() * requested.getHeight());
                if (cost < bestCost) {
                    bestCost = cost;
                    best = candidate;
                }
            }
            Log.w(TAG, "camera cannot do " + requested + "; using " + best);
            return best;
        } catch (CameraAccessException e) {
            throw new IllegalStateException("could not read camera characteristics", e);
        }
    }

    /**
     * A plausible pinhole model for the frame size, with no distortion.
     *
     * <p>Marked as a real (not fake) calibration so the SDK's processors use it rather than
     * substituting a built-in one for a camera that is not actually attached.</p>
     */
    private static CameraCalibration approximateCalibration(int width, int height) {
        float focalLength = (float) (DEFAULT_FOCAL_LENGTH_RATIO * width);
        CameraCalibrationIdentity identity = () -> false;
        return new CameraCalibration(
                identity,
                new org.firstinspires.ftc.robotcore.external.android.util.Size(width, height),
                focalLength, focalLength,
                width / 2.0f, height / 2.0f,
                new float[8],
                false,
                false);
    }

    private CameraDevice openCamera(CameraManager manager, String cameraId) {
        CountDownLatch opened = new CountDownLatch(1);
        CameraDevice[] result = new CameraDevice[1];
        String[] failure = new String[1];
        try {
            manager.openCamera(cameraId, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(CameraDevice device) {
                    result[0] = device;
                    opened.countDown();
                }

                @Override
                public void onDisconnected(CameraDevice device) {
                    device.close();
                    failure[0] = "camera " + cameraId + " disconnected";
                    opened.countDown();
                }

                @Override
                public void onError(CameraDevice device, int error) {
                    device.close();
                    failure[0] = "camera " + cameraId + " failed to open, error " + error;
                    opened.countDown();
                }
            }, cameraHandler);
        } catch (CameraAccessException | SecurityException e) {
            throw new IllegalStateException("could not open camera " + cameraId, e);
        }
        await(opened, "camera " + cameraId + " did not open");
        if (failure[0] != null) {
            throw new IllegalStateException(failure[0]);
        }
        return result[0];
    }

    private CameraCaptureSession startSession() {
        CountDownLatch configured = new CountDownLatch(1);
        CameraCaptureSession[] result = new CameraCaptureSession[1];
        String[] failure = new String[1];
        try {
            camera.createCaptureSession(
                    Collections.singletonList(imageReader.getSurface()),
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(CameraCaptureSession configuredSession) {
                            result[0] = configuredSession;
                            try {
                                CaptureRequest.Builder request = camera.createCaptureRequest(
                                        CameraDevice.TEMPLATE_PREVIEW);
                                request.addTarget(imageReader.getSurface());
                                configuredSession.setRepeatingRequest(
                                        request.build(), null, cameraHandler);
                            } catch (CameraAccessException | IllegalStateException e) {
                                failure[0] = "could not start streaming: " + e;
                            }
                            configured.countDown();
                        }

                        @Override
                        public void onConfigureFailed(CameraCaptureSession failedSession) {
                            failure[0] = "capture session configuration failed";
                            configured.countDown();
                        }
                    },
                    cameraHandler);
        } catch (CameraAccessException e) {
            throw new IllegalStateException("could not create a capture session", e);
        }
        await(configured, "capture session did not configure");
        if (failure[0] != null) {
            throw new IllegalStateException(failure[0]);
        }
        return result[0];
    }

    private static void await(CountDownLatch latch, String timeoutMessage) {
        try {
            if (!latch.await(OPEN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                throw new IllegalStateException(
                        timeoutMessage + " within " + OPEN_TIMEOUT_SECONDS + "s");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while opening the camera", e);
        }
    }

    private void onImageAvailable(ImageReader reader) {
        // Latest, not next: a slow processor should drop frames rather than fall behind.
        try (Image image = reader.acquireLatestImage()) {
            if (image == null || closed) {
                return;
            }
            long captureTimeNanos = image.getTimestamp();
            Mat frame = converter.convert(image);
            frameCount++;
            lastFrameNanos = System.nanoTime();
            if (firstFrameNanos == 0) {
                firstFrameNanos = lastFrameNanos;
            }

            for (Registered entry : registered) {
                if (entry.enabled) {
                    entry.userContext = entry.processor.processFrame(frame, captureTimeNanos);
                }
            }
            if (!frameListeners.isEmpty()) {
                publishAnnotatedFrame(frame, captureTimeNanos);
            }
        } catch (RuntimeException e) {
            // One bad frame must not kill the camera thread and silently stop the stream.
            Log.e(TAG, "frame " + frameCount + " failed", e);
        }
    }

    private void publishAnnotatedFrame(Mat frame, long captureTimeNanos) {
        int width = resolution.getWidth();
        int height = resolution.getHeight();
        if (overlayBitmap == null) {
            overlayBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            jpegBuffer = new ByteArrayOutputStream(width * height / 4);
        }
        Utils.matToBitmap(frame, overlayBitmap);

        // The processors' own annotation code, so the preview shows exactly what the Driver
        // Station would. Canvas is frame-sized, hence unity scaling.
        Canvas canvas = new Canvas(overlayBitmap);
        for (Registered entry : registered) {
            if (entry.enabled) {
                entry.processor.onDrawFrame(canvas, width, height, 1.0f, 1.0f, entry.userContext);
            }
        }

        jpegBuffer.reset();
        overlayBitmap.compress(Bitmap.CompressFormat.JPEG, jpegQuality, jpegBuffer);
        VisionFrame published = new VisionFrame(
                width, height, frameCount, captureTimeNanos, jpegBuffer.toByteArray());
        for (VisionFrameListener listener : frameListeners) {
            listener.onVisionFrame(published);
        }
    }

    /** A disabled processor stops being called at all, matching {@code VisionPortal}. */
    public void setProcessorEnabled(VisionProcessor processor, boolean enabled) {
        for (Registered entry : registered) {
            if (entry.processor == processor) {
                entry.enabled = enabled;
                return;
            }
        }
        throw new IllegalArgumentException("processor was not added to this host");
    }

    public boolean getProcessorEnabled(VisionProcessor processor) {
        for (Registered entry : registered) {
            if (entry.processor == processor) {
                return entry.enabled;
            }
        }
        throw new IllegalArgumentException("processor was not added to this host");
    }

    public void addFrameListener(VisionFrameListener listener) {
        frameListeners.add(listener);
    }

    public void removeFrameListener(VisionFrameListener listener) {
        frameListeners.remove(listener);
    }

    /** The size actually being streamed, which may not be the size that was requested. */
    public Size getCameraResolution() {
        return resolution;
    }

    public long getFrameCount() {
        return frameCount;
    }

    /** Mean frame rate since the first frame, or 0 before there are two of them. */
    public double getFps() {
        long frames = frameCount;
        long elapsed = lastFrameNanos - firstFrameNanos;
        if (frames < 2 || elapsed <= 0) {
            return 0;
        }
        return (frames - 1) * 1_000_000_000.0 / elapsed;
    }

    @Override
    public void close() {
        closeQuietly();
    }

    private void closeQuietly() {
        if (closed) {
            return;
        }
        closed = true;
        if (session != null) {
            try {
                session.close();
            } catch (RuntimeException e) {
                Log.w(TAG, "closing the capture session failed", e);
            }
        }
        if (camera != null) {
            camera.close();
        }
        imageReader.close();
        cameraThread.quitSafely();
        try {
            cameraThread.join(TimeUnit.SECONDS.toMillis(2));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        // Only after the camera thread is done, since it is the one using them.
        converter.release();
        if (overlayBitmap != null) {
            overlayBitmap.recycle();
            overlayBitmap = null;
        }
    }
}
