package org.ngicollective.testframework.sim;

/**
 * How the chassis is moving right now, in the robot's own frame: metres/second along its nose and
 * out of its left side, plus its turn rate.
 *
 * <p>Robot-frame rather than field-frame because this is what the drivetrain produces and what a
 * driver feels. Rotating it into the field is the integrator's job, and doing that in one place is
 * what keeps the heading convention from being re-derived (differently) in three of them.</p>
 */
public final class ChassisVelocity {

    /** A chassis at rest, for a model that has just been placed rather than driven. */
    public static final ChassisVelocity ZERO = new ChassisVelocity(0.0, 0.0, 0.0);

    private final double forward;
    private final double lateral;
    private final double yawRate;

    public ChassisVelocity(double forwardMetresPerSecond,
                           double lateralMetresPerSecond,
                           double yawRadiansPerSecond) {
        this.forward = forwardMetresPerSecond;
        this.lateral = lateralMetresPerSecond;
        this.yawRate = yawRadiansPerSecond;
    }

    /** Metres/second along the nose; negative is backwards. */
    public double forwardMetresPerSecond() {
        return forward;
    }

    /** Metres/second out of the robot's left side, matching the counter-clockwise heading sign. */
    public double lateralMetresPerSecond() {
        return lateral;
    }

    /** Turn rate in radians/second, counter-clockwise-positive. */
    public double yawRadiansPerSecond() {
        return yawRate;
    }

    @Override
    public String toString() {
        return String.format("(%.3f m/s fwd, %.3f m/s left, %.1f\u00b0/s)",
                forward, lateral, Math.toDegrees(yawRate));
    }
}
