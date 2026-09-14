package org.ngicollective.testframework.dashboard;

import org.ngicollective.camerastream.JpegEncoder;
import org.ngicollective.camerastream.JpegFrameSource;
import org.ngicollective.testframework.camera.FrameSource;
import org.ngicollective.testframework.camera.SyntheticFrame;

/**
 * Renders the simulated camera's view for the Dashboard Camera View and hands it over as JPEG.
 *
 * <p>The join between two halves that know nothing about each other: the renderer, which makes RGBA
 * pixels out of a scene and a camera pose, and {@code MjpegServer}, which puts JPEG bytes on a
 * socket.</p>
 *
 * <p>Renders on demand rather than on a clock. The frame source reads the robot's pose when asked,
 * so a frame is only ever as old as the request for it, and a session nobody is watching does no
 * rendering at all.</p>
 */
final class CameraViewFrames implements JpegFrameSource {

    /**
     * The camera's resolution, and full size on purpose.
     *
     * <p>640&times;480 is what the SDK's webcam calibrations are written for and what the detector
     * is handed, so it is what the view has to show. Downscaling for bandwidth would hide the one
     * thing this panel exists to make visible: how many pixels across a tag actually is at range,
     * which is what decides whether it can be decoded at all.</p>
     */
    private static final int WIDTH = 640;
    private static final int HEIGHT = 480;

    private final DashboardBackend backend;
    private final JpegEncoder encoder = new JpegEncoder();

    /** Reused between frames: a 640x480 RGBA frame is 1.2 MB, and 15 of those a second is litter. */
    private final SyntheticFrame frame = new SyntheticFrame(WIDTH, HEIGHT);

    CameraViewFrames(DashboardBackend backend) {
        this.backend = backend;
    }

    static int width() {
        return WIDTH;
    }

    static int height() {
        return HEIGHT;
    }

    /**
     * One rendered frame, or null when this robot has no camera.
     *
     * <p>Synchronized because the frame buffer and the encoder are both reused: two watching
     * browsers must not render into the same pixels at once. The stream shares one frame between
     * clients anyway, so this only ever serializes the rare simultaneous first request.</p>
     */
    @Override
    public synchronized byte[] nextFrame() {
        FrameSource frames = backend.cameraFrames();
        if (frames == null) {
            return null;
        }
        frames.render(frame);
        return encoder.encode(frame.rgba(), WIDTH, HEIGHT);
    }
}
