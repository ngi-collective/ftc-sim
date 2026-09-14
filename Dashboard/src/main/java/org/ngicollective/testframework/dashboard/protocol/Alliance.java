package org.ngicollective.testframework.dashboard.protocol;

import com.google.gson.annotations.SerializedName;

/**
 * Which driver station the session is played from.
 *
 * <p>Sim-wide rather than a camera preset: alliance decides where "away from the wall behind me" is,
 * and that is what an OpMode's heading zero means after {@code resetYaw()}. A camera preset that
 * only moved the eye would leave the robot's reported heading disagreeing with the driver's view,
 * which is the single most confusing thing a field-centric drive can do.</p>
 *
 * <p>The stations are on the <b>X</b> axis, red at &minus;X, with the audience at &minus;Y. That
 * comes from the field CAD, through {@code BioBuzzField}'s conversion of it, and it is the same
 * authority the season's tag placement uses &mdash; which is the point: an alliance model on a
 * different axis from the tags would make every field-centric OpMode start a quarter turn out
 * while still looking plausible on screen.</p>
 */
public enum Alliance {

    /**
     * Red's station is at &minus;X, so facing away from it is FTC heading 0 and the IMU needs no
     * offset at all.
     */
    @SerializedName("red")
    RED(0.0),

    /** Blue's station is at +X, so facing away from it is FTC heading 180&deg;. */
    @SerializedName("blue")
    BLUE(180.0);

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
