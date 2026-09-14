package org.ngicollective.testframework.dashboard.protocol;

/**
 * Where the Dashboard Camera View's pictures come from, and what to expect of them.
 *
 * <p>Sent once when a browser connects. The stream is plain MJPEG over HTTP on its own port, so the
 * browser has to be told a URL; advertising it here rather than letting the page guess is what
 * makes {@code --camera-port} work and what lets a page served from another machine find the
 * stream at all.</p>
 *
 * <p>Absent when the session's robot declares no camera, which is the browser's cue to say so
 * rather than to show a broken image.</p>
 */
public final class CameraStreamInfo {

    /** Absolute URL for an {@code <img>} element: MJPEG, one JPEG per part, indefinitely. */
    public final String url;

    /** Frame size in pixels &mdash; what the detector sees, not a thumbnail of it. */
    public final int width;
    public final int height;

    /**
     * How often the stream renders a new frame.
     *
     * <p>Deliberately below the camera's own rate: this is a sampled view, not a recording, and
     * every frame the detector got is a different feature. Published so the panel can say so
     * instead of implying it shows all of them.</p>
     */
    public final double framesPerSecond;

    public CameraStreamInfo(String url, int width, int height, double framesPerSecond) {
        this.url = url;
        this.width = width;
        this.height = height;
        this.framesPerSecond = framesPerSecond;
    }
}
