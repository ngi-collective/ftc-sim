package org.ngicollective.testframework.vision;

/**
 * Receives annotated frames from a {@link LocalVisionHost}.
 *
 * <p>Registering even one listener makes the host do real work per frame &mdash; bitmap copy,
 * overlay draw, JPEG encode &mdash; so a host with no listeners skips all of it.</p>
 *
 * <p>Called on the host's camera thread. Do not block: hand the frame off and return.</p>
 */
public interface VisionFrameListener {
    void onVisionFrame(VisionFrame frame);
}
