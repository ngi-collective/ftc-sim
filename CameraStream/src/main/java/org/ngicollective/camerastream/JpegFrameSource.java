package org.ngicollective.camerastream;

/**
 * Where an {@link MjpegServer} gets its pictures.
 *
 * <p>JPEG bytes and not pixels: what a frame is rendered from is the caller's business, and keeping
 * that out of this module is what lets it stay a plain-JVM library with no idea that robots
 * exist.</p>
 *
 * <p>Called on a stream's own thread, once per frame interval at most, however many clients are
 * watching.</p>
 */
public interface JpegFrameSource {

    /**
     * The newest frame, encoded as JPEG, or null when there is nothing to look at &mdash; no camera
     * configured, or a simulation that has not produced one yet.
     *
     * <p>Null is a normal answer, not a failure: a client that connects before there is a camera is
     * told so, and a stream that goes null mid-flight waits rather than ending.</p>
     */
    byte[] nextFrame();
}
