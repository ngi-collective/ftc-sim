package org.ngicollective.testframework.vision;

/**
 * One camera frame after every enabled {@code VisionProcessor} has drawn its overlay on it.
 *
 * <p>This is what a preview surface consumes: the JPEG already has the AprilTag outlines, axes and
 * blob contours burned in, because it is rendered through each processor's real
 * {@code onDrawFrame} hook rather than re-implementing the annotations.</p>
 */
public final class VisionFrame {

    private final int width;
    private final int height;
    private final long frameNumber;
    private final long captureTimeNanos;
    private final byte[] jpeg;

    VisionFrame(int width, int height, long frameNumber, long captureTimeNanos, byte[] jpeg) {
        this.width = width;
        this.height = height;
        this.frameNumber = frameNumber;
        this.captureTimeNanos = captureTimeNanos;
        this.jpeg = jpeg;
    }

    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
    }

    /** Monotonic counter, starting at 1, of frames delivered to the processors. */
    public long getFrameNumber() {
        return frameNumber;
    }

    /** The camera's own timestamp for the frame, the same value handed to {@code processFrame}. */
    public long getCaptureTimeNanos() {
        return captureTimeNanos;
    }

    /**
     * JPEG bytes of the annotated frame.
     *
     * <p>Owned by the caller: a fresh array per frame, so holding onto it is safe.</p>
     */
    public byte[] getJpeg() {
        return jpeg;
    }
}
