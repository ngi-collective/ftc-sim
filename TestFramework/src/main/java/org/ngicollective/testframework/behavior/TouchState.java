package org.ngicollective.testframework.behavior;

/**
 * Simulated state of a touch sensor.
 *
 * <p>{@link #isTouching()} is the truth the world writes: something is inside the volume the sensor
 * was configured with. {@link #isPressed()} is what the OpMode reads. Only
 * {@link TouchBehaviors#sensing()} keeps them equal; every other behavior is a lie the sensor
 * tells.</p>
 */
public class TouchState {

    private volatile boolean touching;
    private volatile boolean pressed;

    /** Whether the simulated world has something in the sensor's volume. */
    public boolean isTouching() {
        return touching;
    }

    public void setTouching(boolean touching) {
        this.touching = touching;
    }

    /** What {@code TouchSensor.isPressed()} returns. */
    public boolean isPressed() {
        return pressed;
    }

    public void setPressed(boolean pressed) {
        this.pressed = pressed;
    }
}
