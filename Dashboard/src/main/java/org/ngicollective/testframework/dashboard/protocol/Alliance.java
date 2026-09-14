package org.ngicollective.testframework.dashboard.protocol;

import com.google.gson.annotations.SerializedName;

/**
 * Which driver station the session is played from.
 *
 * <p>Sim-wide rather than a camera preset: alliance decides where "away from the wall behind me" is,
 * and that is what an OpMode's heading zero means after {@code resetYaw()}. A camera preset that
 * only moved the eye would leave the robot's reported heading disagreeing with the driver's view,
 * which is the single most confusing thing a field-centric drive can do.</p>
 */
public enum Alliance {

    /** Red station looks along +Y, so facing away from it is FTC heading +90&deg;. */
    @SerializedName("red")
    RED(90.0),

    /** Blue station looks along &minus;Y, so facing away from it is FTC heading &minus;90&deg;. */
    @SerializedName("blue")
    BLUE(-90.0);

    /**
     * The IMU yaw offset that makes reported heading zero when the robot faces away from this
     * alliance's station, which is how a driver-facing OpMode expects to start.
     */
    public final double yawOffsetDegrees;

    Alliance(double yawOffsetDegrees) {
        this.yawOffsetDegrees = yawOffsetDegrees;
    }

    /** Parses the lowercase name the protocol uses. */
    public static Alliance fromWire(String value) {
        for (Alliance alliance : values()) {
            if (alliance.name().equalsIgnoreCase(value)) {
                return alliance;
            }
        }
        throw new IllegalArgumentException("alliance must be \"red\" or \"blue\", not \"" + value + "\"");
    }
}
