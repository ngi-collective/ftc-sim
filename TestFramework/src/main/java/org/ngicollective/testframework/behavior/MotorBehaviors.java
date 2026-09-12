package org.ngicollective.testframework.behavior;

import com.qualcomm.robotcore.hardware.DcMotor;

/** The built-in catalog of motor behaviors. */
public final class MotorBehaviors {

    private MotorBehaviors() {
    }

    /**
     * A motor with no inertia: it reaches the demanded speed instantly and integrates position from
     * it. RUN_TO_POSITION stops exactly on target rather than overshooting past it.
     */
    public static Behavior<MotorState> ideal() {
        return (state, elapsedSeconds) -> {
            if (state.getMode() == DcMotor.RunMode.STOP_AND_RESET_ENCODER) {
                state.setPosition(0.0);
                state.setVelocity(0.0);
                return;
            }
            state.setVelocity(state.demandedVelocity());
            integrate(state, elapsedSeconds);
        };
    }

    /**
     * A motor with inertia: speed converges on the demand at a constant acceleration.
     *
     * <p>When the demand is zero, {@link DcMotor.ZeroPowerBehavior#FLOAT} coasts down three times
     * slower than {@link DcMotor.ZeroPowerBehavior#BRAKE} stops. That ratio is a modelling
     * approximation, not a measured constant &mdash; use
     * {@link #ramping(double, double)} to set it explicitly.</p>
     *
     * @param secondsToFullSpeed simulated seconds to go from a standstill to full speed
     */
    public static Behavior<MotorState> ramping(double secondsToFullSpeed) {
        return ramping(secondsToFullSpeed, secondsToFullSpeed * 3.0);
    }

    /**
     * @param secondsToFullSpeed simulated seconds to go from a standstill to full speed
     * @param secondsToCoastDown simulated seconds to coast from full speed to a standstill when the
     *                           demand is zero and the zero-power behavior is FLOAT
     */
    public static Behavior<MotorState> ramping(double secondsToFullSpeed, double secondsToCoastDown) {
        if (secondsToFullSpeed <= 0.0 || secondsToCoastDown <= 0.0) {
            throw new IllegalArgumentException("ramp times must be positive");
        }
        return (state, elapsedSeconds) -> {
            if (state.getMode() == DcMotor.RunMode.STOP_AND_RESET_ENCODER) {
                state.setPosition(0.0);
                state.setVelocity(0.0);
                return;
            }
            double demand = state.demandedVelocity();
            boolean coasting = demand == 0.0
                    && state.getZeroPowerBehavior() == DcMotor.ZeroPowerBehavior.FLOAT;
            double seconds = coasting ? secondsToCoastDown : secondsToFullSpeed;
            double maxStep = state.getMaxTicksPerSecond() / seconds * elapsedSeconds;
            double error = demand - state.getVelocity();
            double step = Math.abs(error) <= maxStep ? error : Math.signum(error) * maxStep;
            state.setVelocity(state.getVelocity() + step);
            integrate(state, elapsedSeconds);
        };
    }

    /**
     * A motor that never turns however it is commanded &mdash; a jammed mechanism, a dead motor, or
     * an unplugged encoder cable. Position never changes.
     */
    public static Behavior<MotorState> stalled() {
        return (state, elapsedSeconds) -> state.setVelocity(0.0);
    }

    private static void integrate(MotorState state, double elapsedSeconds) {
        double next = state.getPosition() + state.getVelocity() * elapsedSeconds;
        if (state.getMode() == DcMotor.RunMode.RUN_TO_POSITION) {
            // Do not sail past the target within a single tick.
            double target = state.physicalTargetPosition();
            boolean overshot = (state.getVelocity() > 0.0 && next > target)
                    || (state.getVelocity() < 0.0 && next < target);
            if (overshot) {
                state.setPosition(target);
                state.setVelocity(0.0);
                return;
            }
        }
        state.setPosition(next);
    }
}
