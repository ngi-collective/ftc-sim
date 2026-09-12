package org.ngicollective.testframework.dashboard.protocol;

/**
 * The subset of {@code Gamepad} the dashboard drives.
 *
 * <p>Field names match the SDK's {@code Gamepad} exactly, so the JSON a browser sends reads the same
 * as the OpMode code that consumes it.</p>
 */
public final class GamepadState {

    public float left_stick_x;
    public float left_stick_y;
    public float right_stick_x;
    public float right_stick_y;
    public float left_trigger;
    public float right_trigger;

    public boolean dpad_up;
    public boolean dpad_down;
    public boolean dpad_left;
    public boolean dpad_right;

    public boolean a;
    public boolean b;
    public boolean x;
    public boolean y;
    public boolean options;
}
