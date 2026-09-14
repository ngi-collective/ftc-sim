package org.ngicollective.testframework.behavior;

import org.firstinspires.ftc.robotcore.external.navigation.AngleUnit;

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

    /**
     * An IMU bolted to the chassis: it reports whatever heading the drive model derived from the
     * wheels, and an angular velocity derived from how that heading actually changed.
     *
     * <p>This is the default for a robot with a drivetrain, and it makes the other presets in this
     * class fault injection rather than convenience. A {@link #stationary()} IMU on a robot that is
     * pivoting, or a {@link #rotating(double)} one that drifts a degree a second past the truth, is
     * a sensor lying to the OpMode &mdash; which is a real failure, and the reason those presets
     * stay.</p>
     */
    public static Behavior<ImuState> followingChassis() {
        return (state, elapsedSeconds) -> {
            double chassisYaw = state.getChassisYawDegrees();
            if (elapsedSeconds > 0.0) {
                // Differencing the heading rather than being told the rate: an IMU measures its own
                // motion, so a heading forced from elsewhere has to show up in the rate too.
                double turned = AngleUnit.normalizeDegrees(chassisYaw - state.getYaw());
                state.setYawRateDegreesPerSecond(turned / elapsedSeconds);
            }
            state.setYaw(chassisYaw);
        };
    }
}
