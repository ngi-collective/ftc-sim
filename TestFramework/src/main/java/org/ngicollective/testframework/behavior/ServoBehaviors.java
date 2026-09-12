package org.ngicollective.testframework.behavior;

/** The built-in catalog of servo behaviors. */
public final class ServoBehaviors {

    private ServoBehaviors() {
    }

    /** A servo that snaps to the commanded position with no travel time. */
    public static Behavior<ServoState> instant() {
        return (state, elapsedSeconds) -> state.setPosition(state.demandedPosition());
    }

    /**
     * A servo that sweeps toward the commanded position at a constant rate.
     *
     * @param secondsForFullTravel simulated seconds to sweep the whole 0..1 range
     */
    public static Behavior<ServoState> sweeping(double secondsForFullTravel) {
        if (secondsForFullTravel <= 0.0) {
            throw new IllegalArgumentException("travel time must be positive");
        }
        return (state, elapsedSeconds) -> {
            double error = state.demandedPosition() - state.getPosition();
            double maxStep = elapsedSeconds / secondsForFullTravel;
            double step = Math.abs(error) <= maxStep ? error : Math.signum(error) * maxStep;
            state.setPosition(state.getPosition() + step);
        };
    }

    /** A servo that never moves &mdash; a stripped gear or a disconnected signal wire. */
    public static Behavior<ServoState> jammed() {
        return (state, elapsedSeconds) -> {
        };
    }
}
