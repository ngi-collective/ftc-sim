package org.ngicollective.testframework.sim;

import java.util.Locale;

/**
 * One sensor as the configuration file describes it: what kind it is, and where on the robot it
 * has to be for the simulator to work out what it would see.
 *
 * <p>Nothing here says what the sensor <em>reads</em>. That is the point of the block: a touch
 * sensor's reading is "a ball is in this box", and a distance sensor's is "the nearest thing along
 * this ray is that far away", both answered from the simulated field. A configured reading would
 * only be a number a test wrote down twice.</p>
 *
 * <p>Each kind declares the geometry it uses and no more. A voltage sensor reads the battery, which
 * is nowhere in particular, so it is not asked to pretend it is mounted somewhere; a distance
 * sensor is a point and a direction, not a volume. Asking every kind for every field would make
 * half of every sensor's declaration a number nobody measured.</p>
 */
public final class SensorConfig {

    /** The sensors the simulator knows how to answer from the world. */
    public enum Kind {
        /** Something solid is inside a box on the robot. */
        TOUCH,
        /** The colour of whatever is inside a box on the robot. */
        COLOR,
        /** How far away the nearest thing along a ray is. */
        DISTANCE,
        /** What the battery is doing. */
        VOLTAGE
    }

    private final String name;
    private final Kind kind;
    private final VolumeConfig volume;
    private final double x;
    private final double y;
    private final double z;
    private final double yaw;
    private final double pitch;
    private final double maxRange;

    SensorConfig(String name, Kind kind, VolumeConfig volume,
                 double forwardMetres, double leftMetres, double heightMetres,
                 double yawDegrees, double pitchDegrees, double maxRangeMetres) {
        this.name = name;
        this.kind = kind;
        this.volume = volume;
        this.x = forwardMetres;
        this.y = leftMetres;
        this.z = heightMetres;
        this.yaw = yawDegrees;
        this.pitch = pitchDegrees;
        this.maxRange = maxRangeMetres;
    }

    static SensorConfig from(String name, ConfigJson json) {
        Kind kind = kindOf(name, json);
        switch (kind) {
            case TOUCH:
            case COLOR:
                // The mount is the middle of what the sensor watches; a plunger or a lens does not
                // have a position independent of the space it covers, and two sets of coordinates
                // for one sensor could only ever disagree.
                VolumeConfig volume = VolumeConfig.from(json.child("volume"));
                return new SensorConfig(name, kind, volume,
                        volume.forwardMetres(), volume.leftMetres(), volume.heightMetres(),
                        Double.NaN, Double.NaN, Double.NaN);
            case DISTANCE:
                return new SensorConfig(name, kind, null,
                        json.number("forwardMetres"),
                        json.number("leftMetres"),
                        json.number("heightMetres"),
                        json.number("yawDegrees"),
                        json.number("pitchDegrees"),
                        json.positive("maxRangeMetres"));
            case VOLTAGE:
                return new SensorConfig(name, kind, null,
                        Double.NaN, Double.NaN, Double.NaN,
                        Double.NaN, Double.NaN, Double.NaN);
            default:
                throw new AssertionError("unhandled sensor kind " + kind);
        }
    }

    /**
     * The declared {@code "type"} as a {@link Kind}, or a failure naming both what was written and
     * what is understood. A sensor whose type went unrecognised would have to read nothing, and a
     * sensor that reads nothing looks exactly like hardware that is unplugged.
     */
    private static Kind kindOf(String name, ConfigJson json) {
        String type = json.string("type");
        for (Kind candidate : Kind.values()) {
            if (keyword(candidate).equals(type)) {
                return candidate;
            }
        }
        StringBuilder understood = new StringBuilder();
        for (Kind candidate : Kind.values()) {
            if (understood.length() > 0) {
                understood.append(", ");
            }
            understood.append(keyword(candidate));
        }
        throw new IllegalArgumentException(json.source() + ": sensor \"" + name
                + "\" has \"type\": \"" + type + "\", which is not one of " + understood);
    }

    /** Spelt as the SDK spells the device, which is why {@code COLOR} is not {@code COLOUR}. */
    private static String keyword(Kind kind) {
        return kind.name().toLowerCase(Locale.ROOT);
    }

    /** The name an OpMode looks this sensor up under. */
    public String name() {
        return name;
    }

    /** Which kind of sensor this is, and so which of the numbers below mean anything. */
    public Kind kind() {
        return kind;
    }

    /**
     * The space a {@link Kind#TOUCH} or {@link Kind#COLOR} sensor watches, or null for the kinds
     * that watch no particular space.
     */
    public VolumeConfig volume() {
        return volume;
    }

    /**
     * Metres ahead of the robot's centre, or NaN for {@link Kind#VOLTAGE}.
     *
     * <p>NaN rather than zero for the kinds without a mount: zero is a legitimate place to bolt a
     * sensor, so a defaulted zero would silently put the battery monitor at the middle of the
     * robot, while NaN poisons any geometry it reaches and points at the caller that asked.</p>
     */
    public double forwardMetres() {
        return x;
    }

    /** Metres to the robot's left of centre, or NaN for {@link Kind#VOLTAGE}. */
    public double leftMetres() {
        return y;
    }

    /** Metres above the floor, or NaN for {@link Kind#VOLTAGE}. */
    public double heightMetres() {
        return z;
    }

    /**
     * Degrees a {@link Kind#DISTANCE} sensor is turned from straight ahead, counter-clockwise
     * positive, or NaN for every other kind.
     */
    public double yawDegrees() {
        return yaw;
    }

    /**
     * Degrees a {@link Kind#DISTANCE} sensor is aimed above the horizon, or NaN for every other
     * kind.
     */
    public double pitchDegrees() {
        return pitch;
    }

    /**
     * How far a {@link Kind#DISTANCE} sensor can see, in metres, or NaN for every other kind.
     *
     * <p>A real one reports nothing useful past its range rather than a larger number, so this is
     * the boundary between a reading and a shrug.</p>
     */
    public double maxRangeMetres() {
        return maxRange;
    }
}
