package org.ngicollective.testframework.sim;

/**
 * A box bolted to the robot: the space a mechanism can reach into, or the space a sensor can see.
 *
 * <p>The frame is the one {@link CameraConfig} mounts in &mdash; +X out the nose, +Y to the
 * robot's left, +Z up, measured from the floor at the centre of the footprint &mdash; because that
 * is the frame someone can hold a ruler against.</p>
 *
 * <p>A box rather than a cone or a sphere, and a centre plus extents rather than two corners, for
 * two reasons that point the same way: containment is three comparisons per ball per tick, and a
 * mouth is measured the way this file writes it down &mdash; this far out, so wide, so tall.</p>
 */
public final class VolumeConfig {

    private final double x;
    private final double y;
    private final double z;
    private final double length;
    private final double width;
    private final double tall;

    VolumeConfig(double forwardMetres, double leftMetres, double heightMetres,
                 double lengthMetres, double widthMetres, double tallMetres) {
        this.x = forwardMetres;
        this.y = leftMetres;
        this.z = heightMetres;
        this.length = lengthMetres;
        this.width = widthMetres;
        this.tall = tallMetres;
    }

    /**
     * Offsets are read as plain numbers because a volume centred on the robot's axis, or behind
     * its centre, is a real thing to declare. Extents are not: a box with a zero side contains
     * nothing, so a sensor built from one would read empty forever and look like a broken sensor
     * rather than the missing field it is.
     */
    static VolumeConfig from(ConfigJson json) {
        return new VolumeConfig(
                json.number("forwardMetres"),
                json.number("leftMetres"),
                json.number("heightMetres"),
                json.positive("lengthMetres"),
                json.positive("widthMetres"),
                json.positive("tallMetres"));
    }

    /** Metres the box's centre sits ahead of the robot's centre. */
    public double forwardMetres() {
        return x;
    }

    /** Metres the box's centre sits to the robot's left of centre. */
    public double leftMetres() {
        return y;
    }

    /** Metres the box's centre sits above the floor. */
    public double heightMetres() {
        return z;
    }

    /** How far the box reaches along the robot's forward axis, end to end. */
    public double lengthMetres() {
        return length;
    }

    /** How far the box reaches side to side. */
    public double widthMetres() {
        return width;
    }

    /** How far the box reaches from its floor to its ceiling. */
    public double tallMetres() {
        return tall;
    }
}
