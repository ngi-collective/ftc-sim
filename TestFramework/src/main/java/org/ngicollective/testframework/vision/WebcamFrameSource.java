package org.ngicollective.testframework.vision;

import android.content.Context;
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
import org.ngicollective.testframework.camera.FrameSource;
import org.ngicollective.testframework.camera.SyntheticFrame;
import org.opencv.android.OpenCVLoader;
import org.opencv.core.Mat;

import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Frames from a physically attached camera, behind the same seam as the rendered ones.
 *
 * <p>Kept as an oracle. When a detection looks wrong, the useful question is "is the detector
 * broken, or is my renderer?", and the way to answer it is to put a printed tag in front of a real
 * lens. Because this sits behind {@link FrameSource}, that happens without touching the OpMode
 * under test: swap the source and the same unmodified code sees a real camera instead of a
 * simulated field.</p>
 *
 * <p>Camera2 directly, not {@code VisionPortal}. The SDK reaches a webcam over UVC through its own
 * USB stack, which on an emulator has no device node to find, so no amount of configuration makes
 * the SDK see the host camera the emulator does expose. This opens it as an ordinary Android
 * camera instead.</p>
 *
 * <p>On macOS the host asks for camera permission the first time an emulator touches it. Until it
 * is granted the guest streams uniformly black frames while everything else behaves: the camera
 * opens, frames arrive and timestamps advance. Every structural assertion passes and nothing is
 * ever detected, which is worth knowing before spending an afternoon on it.</p>
 */
public final class WebcamFrameSource implements FrameSource, AutoCloseable {

    public static final String TAG = "WebcamFrameSource";

    private static final long OPEN_TIMEOUT_SECONDS = 5;

    private final Size resolution;
    private final Yuv420Rgba converter;
    private final HandlerThread cameraThread;
    private final Handler cameraHandler;
    private final ImageReader imageReader;

    /** The most recent frame's pixels, RGBA. Guarded by {@link #latestLock}. */
    private final byte[] latest;
    private final Object latestLock = new Object();
    private boolean haveFrame;

    private CameraDevice camera;
    private CameraCaptureSession session;
    private volatile boolean closed;
    private volatile long frameCount;

    /** Opens the back camera at the closest size it supports to the one requested. */
    public WebcamFrameSource(Context context, Size requestedResolution) {
        this(context, requestedResolution, BuiltinCameraDirection.BACK);
    }

    public WebcamFrameSource(Context context, Size requestedResolution,
                             BuiltinCameraDirection direction) {
        CameraManager manager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
        String cameraId = selectCamera(manager, direction);
        this.resolution = chooseResolution(manager, cameraId, requestedResolution);

        int width = resolution.getWidth();
        int height = resolution.getHeight();

        // The YUV to RGBA conversion is OpenCV's, so the natives have to be up. Idempotent, and it
        // turns an UnsatisfiedLinkError thrown from a frame callback into a legible failure here.
        if (!OpenCVLoader.initDebug()) {
            throw new IllegalStateException("could not load the OpenCV native library");
        }

        this.converter = new Yuv420Rgba(width, height);
        this.latest = new byte[width * height * 4];
        this.cameraThread = new HandlerThread(TAG);
        cameraThread.start();
        this.cameraHandler = new Handler(cameraThread.getLooper());

        // Two buffers: one being converted while the HAL fills the next.
        this.imageReader = ImageReader.newInstance(width, height, ImageFormat.YUV_420_888, 2);
        imageReader.setOnImageAvailableListener(new ImageReader.OnImageAvailableListener() {
            @Override
            public void onImageAvailable(ImageReader reader) {
                consume(reader);
            }
        }, cameraHandler);

        try {
            this.camera = openCamera(manager, cameraId);
            this.session = startSession();
        } catch (RuntimeException e) {
            // A half-open camera is worse than none: unwind what did open.
            closeQuietly();
            throw e;
        }
        Log.i(TAG, String.format("streaming %dx%d from camera %s", width, height, cameraId));
    }

    /** The size the camera actually gave us, which may not be the size asked for. */
    public Size resolution() {
        return resolution;
    }

    /** How many frames the camera has delivered. */
    public long frameCount() {
        return frameCount;
    }

    /**
     * Copies the most recent camera frame into the caller's frame.
     *
     * <p>Before the first frame arrives this leaves the frame untouched rather than blocking: a
     * camera that has not produced anything yet is a real situation, and a stalled render thread
     * would be a worse way to express it.</p>
     *
     * @throws IllegalArgumentException if asked for a size the camera is not streaming, since
     *     scaling here would quietly invalidate the calibration the detector is using
     */
    @Override
    public void render(SyntheticFrame frame) {
        if (frame.width() != resolution.getWidth() || frame.height() != resolution.getHeight()) {
            throw new IllegalArgumentException("this camera streams "
                    + resolution.getWidth() + "x" + resolution.getHeight() + ", but a "
                    + frame.width() + "x" + frame.height() + " frame was requested");
        }
        synchronized (latestLock) {
            if (!haveFrame) {
                return;
            }
            System.arraycopy(latest, 0, frame.rgba(), 0, latest.length);
        }
    }

    private void consume(ImageReader reader) {
        // Latest, not next: a slow consumer should drop frames rather than fall behind.
        try (Image image = reader.acquireLatestImage()) {
            if (image == null || closed) {
                return;
            }
            Mat rgba = converter.convert(image);
            synchronized (latestLock) {
                // Mat to bytes, so that everything above this class stays free of OpenCV. One copy
                // on a diagnostic path is a fair price for that.
                rgba.get(0, 0, latest);
                haveFrame = true;
            }
            frameCount++;
        } catch (RuntimeException e) {
            // One bad frame must not kill the camera thread and silently stop the stream.
            Log.e(TAG, "frame " + frameCount + " failed", e);
        }
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

    private CameraDevice openCamera(CameraManager manager, final String cameraId) {
        final CountDownLatch opened = new CountDownLatch(1);
        final CameraDevice[] result = new CameraDevice[1];
        final String[] failure = new String[1];
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
        final CountDownLatch configured = new CountDownLatch(1);
        final CameraCaptureSession[] result = new CameraCaptureSession[1];
        final String[] failure = new String[1];
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

    @Override
    public void close() {
        closed = true;
        closeQuietly();
    }

    private void closeQuietly() {
        if (session != null) {
            session.close();
            session = null;
        }
        if (camera != null) {
            camera.close();
            camera = null;
        }
        if (imageReader != null) {
            imageReader.close();
        }
        cameraThread.quitSafely();
    }
}
