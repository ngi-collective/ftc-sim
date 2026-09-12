package org.ngicollective.testframework.behavior;

/** The built-in catalog of IMU behaviors. */
public final class ImuBehaviors {

    private ImuBehaviors() {
    }

    /** A robot that never turns. Heading only changes if a test writes it directly. */
    public static Behavior<ImuState> stationary() {
        return (state, elapsedSeconds) -> state.setYawRateDegreesPerSecond(0.0);
    }

    /**
     * A robot turning at a fixed rate &mdash; enough to exercise heading-dependent code without a
     * physics model.
     *
     * @param degreesPerSecond positive turns counter-clockwise, matching the SDK's convention
     */
    public static Behavior<ImuState> rotating(double degreesPerSecond) {
        return (state, elapsedSeconds) -> {
            state.setYawRateDegreesPerSecond(degreesPerSecond);
            state.setYaw(state.getYaw() + degreesPerSecond * elapsedSeconds);
        };
    }

    /**
     * A robot whose turn rate is whatever the test most recently wrote to
     * {@link ImuState#setYawRateDegreesPerSecond(double)}. Use this to steer heading from a test or
     * the dashboard while simulated time keeps running.
     */
    public static Behavior<ImuState> followingYawRate() {
        return (state, elapsedSeconds) ->
                state.setYaw(state.getYaw() + state.getYawRateDegreesPerSecond() * elapsedSeconds);
    }
}
