package org.ngicollective.testframework.physics;

/**
 * Where one body is and how it is turned, at the instant it was asked.
 *
 * <p>A snapshot rather than a live handle. The simulation's own bodies are mutated in place by
 * whoever is stepping them, and the readers &mdash; the wire, a test, the camera &mdash; want a
 * value that cannot change underneath them halfway through being serialised.</p>
 *
 * <p>The {@link #id()} is the body's position in the arrangement it was created from, so the same
 * number identifies the same ball in {@code sim/scene} (which carries its colour and radius, once)
 * and in {@code sim/bodies} (which carries where it is, every tick). A body keyed by array index
 * would break the moment a body was added or removed mid-session.</p>
 *
 * <p>Orientation is a unit quaternion in the FTC field frame, in {@code (x, y, z, w)} order. It is
 * carried even though a flat-shaded sphere cannot show it, because a ball that slides when it
 * should roll is a wrong-friction bug and the spin is the only place that shows up.</p>
 */
public final class BodyState {

    private final int id;
    private final double x;
    private final double y;
    private final double z;
    private final double qx;
    private final double qy;
    private final double qz;
    private final double qw;

    public BodyState(int id, double x, double y, double z,
                     double qx, double qy, double qz, double qw) {
        this.id = id;
        this.x = x;
        this.y = y;
        this.z = z;
        this.qx = qx;
        this.qy = qy;
        this.qz = qz;
        this.qw = qw;
    }

    /** This body's place in the arrangement, and its identity on the wire. */
    public int id() {
        return id;
    }

    public double x() {
        return x;
    }

    public double y() {
        return y;
    }

    /** Height of the centre above the floor, in metres. */
    public double z() {
        return z;
    }

    public double quaternionX() {
        return qx;
    }

    public double quaternionY() {
        return qy;
    }

    public double quaternionZ() {
        return qz;
    }

    public double quaternionW() {
        return qw;
    }

    @Override
    public String toString() {
        return String.format("body %d at (%.3f, %.3f, %.3f)", id, x, y, z);
    }
}
