package org.ngicollective.testframework.behavior;

/**
 * A simulated camera's state: whether it is streaming, and how many frames it owes.
 *
 * <p>Frames are paced off the <em>simulated</em> clock rather than the wall clock, which is what
 * makes a vision test repeatable. Run the same scenario twice and the camera delivers frames at
 * the same simulated instants both times; fast-forward the simulation and the frame count scales
 * with it instead of the run quietly seeing fewer frames on a slower machine.</p>
 */
public final class CameraState {

    private final double framesPerSecond;

    private boolean streaming;
    private double secondsSinceFrame;
    private int framesDue;
    private int framesDelivered;
    private double elapsedSeconds;

    public CameraState(double framesPerSecond) {
        if (!(framesPerSecond > 0.0)) {
            throw new IllegalArgumentException(
                    "a camera must have a positive frame rate; got " + framesPerSecond);
        }
        this.framesPerSecond = framesPerSecond;
    }

    /** The rate this camera streams at. */
    public double framesPerSecond() {
        return framesPerSecond;
    }

    /** Whether the camera has been told to stream. */
    public boolean isStreaming() {
        return streaming;
    }

    public void setStreaming(boolean streaming) {
        this.streaming = streaming;
        if (!streaming) {
            // Nothing is owed by a stopped camera, and a stale debt would otherwise arrive as a
            // burst of frames the moment it restarted.
            framesDue = 0;
            secondsSinceFrame = 0.0;
        }
    }

    /** Simulated seconds since this camera was created. */
    public double elapsedSeconds() {
        return elapsedSeconds;
    }

    /** How many frames have actually been handed to a pipeline. */
    public int framesDelivered() {
        return framesDelivered;
    }

    /** Advances the clock, accruing whole frames as their intervals pass. */
    void tick(double seconds) {
        elapsedSeconds += seconds;
        if (!streaming) {
            return;
        }
        secondsSinceFrame += seconds;
        double interval = 1.0 / framesPerSecond;
        while (secondsSinceFrame >= interval) {
            secondsSinceFrame -= interval;
            framesDue++;
        }
    }

    /** Claims the frames owed, resetting the debt. */
    public int takeFramesDue() {
        int due = framesDue;
        framesDue = 0;
        framesDelivered += due;
        return due;
    }
}
