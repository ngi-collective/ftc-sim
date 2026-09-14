package org.ngicollective.testframework.camera;

/**
 * Where a simulated camera's pixels come from.
 *
 * <p>The seam that lets the same camera plumbing serve two purposes. A rendered scene is the point
 * of the exercise, but a real webcam behind the same interface is worth keeping: when a detection
 * looks wrong, being able to put a printed tag in front of an actual lens answers "is the detector
 * broken, or is my renderer?" without changing a line of the OpMode under test.</p>
 *
 * <p>Deliberately free of Android and OpenCV, so a source can be exercised on a plain JVM.</p>
 */
public interface FrameSource {

    /**
     * Draws the next frame.
     *
     * <p>The frame is the caller's, and its size is the resolution the OpMode asked for. A source
     * is expected to adapt to it rather than impose its own, because a real camera does.</p>
     */
    void render(SyntheticFrame frame);
}
