package org.ngicollective.testframework.camera;

/**
 * One posed primitive: a box or a cylinder, somewhere in the field frame, in one flat colour.
 *
 * <p>The unit the field's {@link Structure}s are described in. A primitive rather than a mesh
 * because primitives are what two of the three things that consume this want natively &mdash; the
 * physics world builds a collider straight from one, and the browser builds a {@code BoxGeometry}
 * or {@code CylinderGeometry} straight from one and can then light it. Only this package's own
 * rasteriser wants polygons, and turning a primitive into them is {@link SolidSurfaces}. See
 * {@code docs/adr/0004-structures-are-published-as-posed-primitives.md}.</p>
 *
 * <h2>Local axes</h2>
 *
 * <p>A solid's own frame is {@link Pose3d}'s: local +X is {@link Pose3d#forward()}, +Y is
 * {@link Pose3d#left()}, +Z is {@link Pose3d#up()}. A box's three lengths run along those axes in
 * that order, and <b>a cylinder's axis is its local +Z</b>, which is also the axis ode4j's own
 * cylinder is built along, so a collider needs no extra rotation to agree with a drawing.</p>
 *
 * <h2>A cylinder is a shell</h2>
 *
 * <p>Open at both ends, with no caps. Every cylinder on this field is a ring or a pipe &mdash; the
 * mouth of a FLOWER, one of the four uprights holding it together &mdash; and a cap would hide the
 * POLLEN sitting inside, which is the thing anyone looking at a FLOWER is looking for. Nothing here
 * needs a solid cylinder; when something does, it wants a second factory rather than a flag,
 * because a capped cylinder and a tube are not the same collider either.</p>
 */
public final class Solid {

    public enum Shape {
        BOX,
        CYLINDER,
    }

    private final Shape shape;
    private final Pose3d pose;
    private final double lengthX;
    private final double lengthY;
    private final double lengthZ;
    private final double radius;
    private final double length;
    private final int red;
    private final int green;
    private final int blue;

    private Solid(Shape shape, Pose3d pose, double lengthX, double lengthY, double lengthZ,
                  double radius, double length, int red, int green, int blue) {
        this.shape = shape;
        this.pose = pose;
        this.lengthX = lengthX;
        this.lengthY = lengthY;
        this.lengthZ = lengthZ;
        this.radius = radius;
        this.length = length;
        this.red = red;
        this.green = green;
        this.blue = blue;
    }

    /** A box of these three lengths, centred on {@code pose} and turned with it. */
    public static Solid box(Pose3d pose, double lengthX, double lengthY, double lengthZ,
                            int red, int green, int blue) {
        requirePositive("a box's lengths", lengthX, lengthY, lengthZ);
        return new Solid(Shape.BOX, pose, lengthX, lengthY, lengthZ, 0.0, 0.0, red, green, blue);
    }

    /** A cylindrical shell of this radius and length, on {@code pose}'s local +Z axis. */
    public static Solid cylinder(Pose3d pose, double radiusMetres, double lengthMetres,
                                 int red, int green, int blue) {
        requirePositive("a cylinder's radius and length", radiusMetres, lengthMetres);
        return new Solid(Shape.CYLINDER, pose, 0.0, 0.0, 0.0, radiusMetres, lengthMetres,
                red, green, blue);
    }

    /**
     * A square-section bar between two points, with its length along the line joining them.
     *
     * <p>For the things a field is braced with: the HIVE frame's four legs each run from a foot on
     * the tiles up to the apex the pivot sits on, leaning in two axes at once. Those endpoints are
     * what the CAD measures and what a tape measure would give; the yaw and pitch that aim a box
     * along them are not, and deriving them by hand per leg is how one leg ends up mirrored.</p>
     *
     * <p>The cross-section is square because roll is then meaningless, and a bar whose section is
     * <em>not</em> square needs a roll reference this signature cannot express &mdash; it would
     * have to invent one, and silently turn the bar the wrong way about its own axis.</p>
     */
    public static Solid beam(Vec3 from, Vec3 to, double thickness,
                             int red, int green, int blue) {
        Vec3 along = to.minus(from);
        double length = along.length();
        if (!(length > 0.0)) {
            throw new IllegalArgumentException("a beam needs two distinct ends; both are " + from);
        }
        requirePositive("a beam's thickness", thickness);

        // A pose's up() is its local +Z, which is the axis a box's third length runs along. Read
        // off up() = (-cos(yaw)sin(pitch), -sin(yaw)sin(pitch), cos(pitch)) at zero roll: the
        // vertical component fixes the pitch outright, and the horizontal part then fixes the yaw.
        // A vertical beam leaves yaw undetermined, and atan2(0, 0) answers 0, which is as good as
        // any: every yaw gives the same square bar.
        Vec3 unit = along.scaled(1.0 / length);
        double pitch = Math.acos(Math.max(-1.0, Math.min(1.0, unit.z())));
        double yaw = Math.atan2(-unit.y(), -unit.x());

        Vec3 middle = from.plus(along.scaled(0.5));
        return box(Pose3d.of(middle, yaw, pitch, 0.0), thickness, thickness, length,
                red, green, blue);
    }

    private static void requirePositive(String what, double... values) {
        for (double value : values) {
            if (!(value > 0.0)) {
                throw new IllegalArgumentException(what + " must be positive; got " + value);
            }
        }
    }

    public Shape shape() {
        return shape;
    }

    /** Where this solid's centre is and which way it is turned. */
    public Pose3d pose() {
        return pose;
    }

    /** Along local +X. Zero for a cylinder. */
    public double lengthX() {
        return lengthX;
    }

    /** Along local +Y. Zero for a cylinder. */
    public double lengthY() {
        return lengthY;
    }

    /** Along local +Z. Zero for a cylinder. */
    public double lengthZ() {
        return lengthZ;
    }

    /** Zero for a box. */
    public double radiusMetres() {
        return radius;
    }

    /** Along the axis, which is local +Z. Zero for a box. */
    public double lengthMetres() {
        return length;
    }

    /**
     * The same solid, moved and turned by a rotation about an axis: one panel of a tipping HIVE.
     *
     * <p>Dimensions and colour survive untouched, which is the whole point of doing this to a
     * primitive rather than to a mesh &mdash; a rotated box is a box, so a structure on a pivot
     * costs nothing to redraw at a new angle and the browser can keep sharing one
     * {@code BoxGeometry} across every angle it is ever drawn at.</p>
     */
    public Solid rotatedAbout(Vec3 point, Vec3 axis, double radians) {
        Pose3d turned = pose.rotatedAbout(point, axis, radians);
        return shape == Shape.BOX
                ? new Solid(Shape.BOX, turned, lengthX, lengthY, lengthZ, 0.0, 0.0,
                        red, green, blue)
                : new Solid(Shape.CYLINDER, turned, 0.0, 0.0, 0.0, radius, length,
                        red, green, blue);
    }

    public int red() {
        return red;
    }

    public int green() {
        return green;
    }

    public int blue() {
        return blue;
    }

    @Override
    public String toString() {
        return shape == Shape.BOX
                ? String.format("box %.3fx%.3fx%.3f at %s", lengthX, lengthY, lengthZ, pose)
                : String.format("cylinder r=%.3f l=%.3f at %s", radius, length, pose);
    }
}
