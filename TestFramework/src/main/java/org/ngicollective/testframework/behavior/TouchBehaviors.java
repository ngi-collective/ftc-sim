package org.ngicollective.testframework.behavior;

/** The built-in catalog of touch sensor behaviors. */
public final class TouchBehaviors {

    private TouchBehaviors() {
    }

    /** A working sensor: pressed whenever the world has something in its volume. */
    public static Behavior<TouchState> sensing() {
        return (state, elapsedSeconds) -> state.setPressed(state.isTouching());
    }

    /**
     * A sensor that reports pressed forever &mdash; a plunger jammed in, or a switch shorted
     * closed. An autonomous that waits for a limit switch to release hangs on this.
     */
    public static Behavior<TouchState> stuckPressed() {
        return (state, elapsedSeconds) -> state.setPressed(true);
    }

    /** A sensor that never reports pressed: an unplugged wire, or a switch wired open. */
    public static Behavior<TouchState> disconnected() {
        return (state, elapsedSeconds) -> state.setPressed(false);
    }
}
