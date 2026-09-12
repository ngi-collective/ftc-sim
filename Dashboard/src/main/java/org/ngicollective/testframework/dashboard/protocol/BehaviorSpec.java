package org.ngicollective.testframework.dashboard.protocol;

import org.ngicollective.testframework.behavior.Behavior;
import org.ngicollective.testframework.behavior.ImuBehaviors;
import org.ngicollective.testframework.behavior.ImuState;
import org.ngicollective.testframework.behavior.MotorBehaviors;
import org.ngicollective.testframework.behavior.MotorState;
import org.ngicollective.testframework.behavior.ServoBehaviors;
import org.ngicollective.testframework.behavior.ServoState;

/**
 * A behavior named on the wire, from a closed catalog.
 *
 * <p>The wire carries a name and numbers, never code: a browser can only ever select one of the
 * behaviors the framework ships, which keeps the dashboard from becoming a remote code execution
 * surface and keeps the protocol stable as new behaviors appear.</p>
 */
public final class BehaviorSpec {

    public final String type;
    /** The behavior's single tuning number, where it has one (ramp seconds, degrees/second, ...). */
    public final double value;

    public BehaviorSpec(String type, double value) {
        this.type = type;
        this.value = value;
    }

    public Behavior<MotorState> asMotorBehavior() {
        switch (type) {
            case "ideal":
                return MotorBehaviors.ideal();
            case "ramping":
                return MotorBehaviors.ramping(positive("ramping"));
            case "stalled":
                return MotorBehaviors.stalled();
            default:
                throw unknown("motor", "ideal, ramping, stalled");
        }
    }

    public Behavior<ServoState> asServoBehavior() {
        switch (type) {
            case "instant":
                return ServoBehaviors.instant();
            case "sweeping":
                return ServoBehaviors.sweeping(positive("sweeping"));
            case "jammed":
                return ServoBehaviors.jammed();
            default:
                throw unknown("servo", "instant, sweeping, jammed");
        }
    }

    public Behavior<ImuState> asImuBehavior() {
        switch (type) {
            case "stationary":
                return ImuBehaviors.stationary();
            case "rotating":
                return ImuBehaviors.rotating(value);
            case "followingYawRate":
                return ImuBehaviors.followingYawRate();
            default:
                throw unknown("imu", "stationary, rotating, followingYawRate");
        }
    }

    private double positive(String name) {
        if (!(value > 0.0)) {
            throw new IllegalArgumentException(
                    "behavior \"" + name + "\" needs a positive value, got " + value);
        }
        return value;
    }

    private IllegalArgumentException unknown(String kind, String known) {
        return new IllegalArgumentException(
                "unknown " + kind + " behavior \"" + type + "\"; known behaviors are " + known);
    }
}
