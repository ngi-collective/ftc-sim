package org.ngicollective.testframework.camera;

/**
 * A point or direction in three dimensions, in metres.
 *
 * <p>Every length in this package is metres and every angle is radians, with no exceptions and no
 * unit-carrying types. The game manual and the field CAD are both in inches, so a conversion has
 * to happen somewhere; it happens once, at the configuration boundary, and never again. A type
 * that could hold either unit would move that decision into arithmetic, which is where mixed-unit
 * bugs live: they do not throw, they just put the tag half a metre from where it belongs.</p>
 *
 * <p>Immutable and {@code double}-precision. The FTC SDK's {@code VectorF} was the alternative and
 * was rejected on both counts: it is mutable, and it is {@code float}, which is fine for a pose
 * that came out of a camera but not for a chain of rotations we are asserting to the pixel.</p>
 */
public final class Vec3 {

    /** The field origin: centre of the field, on the floor. */
    public static final Vec3 ZERO = new Vec3(0.0, 0.0, 0.0);

    private final double x;
    private final double y;
    private final double z;

    public Vec3(double xMetres, double yMetres, double zMetres) {
        if (!isFinite(xMetres) || !isFinite(yMetres) || !isFinite(zMetres)) {
            throw new IllegalArgumentException("a vector must be finite; got x=" + xMetres
                    + " y=" + yMetres + " z=" + zMetres);
        }
        this.x = xMetres;
        this.y = yMetres;
        this.z = zMetres;
    }

    public double x() {
        return x;
    }

    public double y() {
        return y;
    }

    public double z() {
        return z;
    }

    public Vec3 plus(Vec3 other) {
        return new Vec3(x + other.x, y + other.y, z + other.z);
    }

    public Vec3 minus(Vec3 other) {
        return new Vec3(x - other.x, y - other.y, z - other.z);
    }

    /** This vector scaled by {@code factor}, used to walk a distance along a basis direction. */
    public Vec3 scaled(double factor) {
        return new Vec3(x * factor, y * factor, z * factor);
    }

    public double dot(Vec3 other) {
        return x * other.x + y * other.y + z * other.z;
    }

    public double length() {
        return Math.sqrt(x * x + y * y + z * z);
    }

    @Override
    public String toString() {
        return String.format("(%.4f, %.4f, %.4f)m", x, y, z);
    }

    private static boolean isFinite(double value) {
        return !Double.isNaN(value) && !Double.isInfinite(value);
    }
}
