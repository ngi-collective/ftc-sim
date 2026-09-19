package org.ngicollective.testframework.camera;

/**
 * Where something is in three dimensions and which way it is turned: a position plus yaw, pitch
 * and roll.
 *
 * <p>{@link org.ngicollective.testframework.sim.Pose2d} is the robot's pose, and stays 2D because
 * a mecanum robot on a flat field has no z, pitch or roll worth simulating. This is for the things
 * that do: a camera bolted to the robot at an angle, and an AprilTag plate on the underside of a
 * CELL, tilted thirty degrees and facing the floor.</p>
 *
 * <h2>One rotation convention</h2>
 *
 * <p>Rotation is {@code Rz(yaw) * Ry(-pitch) * Rx(roll)} applied to the identity frame, which
 * yields three orthonormal axes:</p>
 *
 * <ul>
 *   <li>{@link #forward()} &mdash; where the thing points. Yaw is counter-clockwise from field
 *       +X and pitch is positive upward, so a camera with {@code pitch = +60°} is aimed sixty
 *       degrees above the horizon, which is what BioBuzz demands of it.</li>
 *   <li>{@link #left()} &mdash; the thing's own left.</li>
 *   <li>{@link #up()} &mdash; the thing's own up. Roll spins the frame about {@link #forward()}.</li>
 * </ul>
 *
 * <p>There is exactly one such convention in this codebase, deliberately, and two documented ways
 * of reading it &mdash; see {@link CameraView} for the camera reading and {@link FieldTag} for the
 * tag reading. Having a second convention would be worse than having an awkward one.</p>
 *
 * <p>The basis is computed once at construction. A pose is built per scenario and read per frame
 * per tag, so caching three vectors trades a few bytes for six trigonometric calls per lookup.</p>
 */
public final class Pose3d {

    private final Vec3 position;
    private final double yaw;
    private final double pitch;
    private final double roll;

    private final Vec3 forward;
    private final Vec3 left;
    private final Vec3 up;

    private Pose3d(Vec3 position, double yaw, double pitch, double roll) {
        if (!isFinite(yaw) || !isFinite(pitch) || !isFinite(roll)) {
            throw new IllegalArgumentException("a pose's angles must be finite; got yaw=" + yaw
                    + " pitch=" + pitch + " roll=" + roll + " (radians)");
        }
        this.position = position;
        this.yaw = yaw;
        this.pitch = pitch;
        this.roll = roll;

        double cosYaw = Math.cos(yaw);
        double sinYaw = Math.sin(yaw);
        double cosPitch = Math.cos(pitch);
        double sinPitch = Math.sin(pitch);
        double cosRoll = Math.cos(roll);
        double sinRoll = Math.sin(roll);

        // The three columns of Rz(yaw) * Ry(-pitch) * Rx(roll).
        this.forward = new Vec3(cosPitch * cosYaw, cosPitch * sinYaw, sinPitch);
        this.left = new Vec3(
                -cosYaw * sinPitch * sinRoll - sinYaw * cosRoll,
                -sinYaw * sinPitch * sinRoll + cosYaw * cosRoll,
                cosPitch * sinRoll);
        this.up = new Vec3(
                -cosYaw * sinPitch * cosRoll + sinYaw * sinRoll,
                -sinYaw * sinPitch * cosRoll - cosYaw * sinRoll,
                cosPitch * cosRoll);
    }

    /** A pose from a position and three angles in radians. */
    public static Pose3d of(Vec3 position, double yawRadians, double pitchRadians,
                            double rollRadians) {
        return new Pose3d(position, yawRadians, pitchRadians, rollRadians);
    }

    /**
     * The same, in degrees &mdash; the unit a camera mount is measured in with a protractor, and
     * the unit the configuration files use.
     */
    public static Pose3d ofDegrees(Vec3 position, double yawDegrees, double pitchDegrees,
                                   double rollDegrees) {
        return new Pose3d(position, Math.toRadians(yawDegrees), Math.toRadians(pitchDegrees),
                Math.toRadians(rollDegrees));
    }

    /** Pointing along +X, level and upright. */
    public static Pose3d facingForward(Vec3 position) {
        return new Pose3d(position, 0.0, 0.0, 0.0);
    }

    public Vec3 position() {
        return position;
    }

    public double yaw() {
        return yaw;
    }

    public double pitch() {
        return pitch;
    }

    public double roll() {
        return roll;
    }

    /** Unit vector along the direction this pose points. */
    public Vec3 forward() {
        return forward;
    }

    /** Unit vector along this pose's own left. */
    public Vec3 left() {
        return left;
    }

    /** Unit vector along this pose's own up. */
    public Vec3 up() {
        return up;
    }

    /**
     * This pose, re-expressed in the field frame, given the frame it is mounted in.
     *
     * <p>Used to turn a camera's mount &mdash; measured against the robot, where a student can put
     * a ruler on it &mdash; into a pose on the field. The composition is a plain yaw addition
     * rather than a matrix product, and that is not a shortcut: the robot's own rotation is pure
     * yaw, and {@code Rz(h) * Rz(y) * Ry * Rx == Rz(h + y) * Ry * Rx}. A robot that could pitch
     * would break this, and would have broken {@code Pose2d} first.</p>
     */
    public Pose3d toFieldFrame(org.ngicollective.testframework.sim.Pose2d robot) {
        double cosHeading = Math.cos(robot.heading());
        double sinHeading = Math.sin(robot.heading());
        Vec3 rotated = new Vec3(
                position.x() * cosHeading - position.y() * sinHeading,
                position.x() * sinHeading + position.y() * cosHeading,
                position.z());
        Vec3 onField = new Vec3(rotated.x() + robot.x(), rotated.y() + robot.y(), rotated.z());
        return new Pose3d(onField, yaw + robot.heading(), pitch, roll);
    }

    /**
     * This pose, rigidly rotated about an axis through a point: what a hinge does to everything
     * bolted to it.
     *
     * <p>The HIVE's pivot is the one rotating axis on a BioBuzz field, and a tip has to carry the
     * CELL's panels, its scoring volume and the four AprilTags on its underside together. Doing
     * that as one rotation applied to whole poses is what makes it impossible for them to drift
     * apart &mdash; the alternative, re-deriving each one from a tip angle, is three places to get
     * a sign wrong and two of them only show up in a camera frame.</p>
     *
     * <h2>Why the angles are recovered rather than adjusted</h2>
     *
     * <p>{@code (yaw, pitch, roll)} is a chart, not a group: it covers the rotations with
     * {@code |pitch| < 90°} and adding an angle to one of its three numbers only composes
     * correctly while the result stays inside that. A CELL's tag plate starts at
     * {@code pitch = -60°} and a 60&deg; tip takes it to -120&deg;, which is off the chart; the
     * same rotation expressed in range is {@code yaw + 180°, pitch = -60°, roll = 180°}. Getting
     * that wrong is not a small error: the roll is what keeps a tag row's ids in the order they
     * are printed in, and a plate that lands mirrored renders 36h11 codewords that mostly are not
     * codewords, so vision reports zero detections and looks like a broken detector.</p>
     *
     * <p>So the axes are rotated and the angles read back off them. The recovery is exact except
     * where {@code cos(pitch)} vanishes, which is a pose pointing at the zenith; nothing on a
     * field does, and a caller who builds one gets a yaw and roll that trade off against each
     * other while the axes stay right.</p>
     *
     * @param point any point on the axis, in the same frame as this pose
     * @param axis the axis's direction, which need not be a unit vector
     */
    public Pose3d rotatedAbout(Vec3 point, Vec3 axis, double radians) {
        double length = Math.sqrt(axis.x() * axis.x() + axis.y() * axis.y() + axis.z() * axis.z());
        if (length == 0.0) {
            throw new IllegalArgumentException("an axis of rotation needs a direction; got " + axis);
        }
        double ax = axis.x() / length;
        double ay = axis.y() / length;
        double az = axis.z() / length;
        double cos = Math.cos(radians);
        double sin = Math.sin(radians);

        Vec3 turned = position.minus(point);
        Vec3 moved = rodrigues(turned, ax, ay, az, cos, sin);
        Vec3 nose = rodrigues(forward, ax, ay, az, cos, sin);
        Vec3 sky = rodrigues(up, ax, ay, az, cos, sin);

        return fromAxes(new Vec3(point.x() + moved.x(), point.y() + moved.y(),
                point.z() + moved.z()), nose, sky);
    }

    /**
     * A pose from its own axes, with the three angles recovered from them.
     *
     * <p>{@code forward} and {@code up} must be perpendicular unit vectors; they come from
     * rotating a pose's own, so they are. Package-private because a caller with axes in hand
     * outside this package is a caller who has done geometry that belongs in here.</p>
     */
    static Pose3d fromAxes(Vec3 position, Vec3 forward, Vec3 up) {
        double pitch = Math.asin(clamp(forward.z()));
        double yaw = Math.atan2(forward.y(), forward.x());

        // left.z and up.z are cos(pitch)*sin(roll) and cos(pitch)*cos(roll), so their ratio is the
        // roll with the pitch divided out. left is up x forward under this convention -- the same
        // right-handed triple the constructor builds -- and taking it from the two given axes
        // rather than from a third argument is one fewer thing a caller can hand over inconsistent.
        Vec3 left = new Vec3(
                up.y() * forward.z() - up.z() * forward.y(),
                up.z() * forward.x() - up.x() * forward.z(),
                up.x() * forward.y() - up.y() * forward.x());
        double roll = Math.atan2(left.z(), up.z());
        return new Pose3d(position, yaw, pitch, roll);
    }

    /** Rodrigues' rotation of one vector about a unit axis. */
    private static Vec3 rodrigues(Vec3 vector, double ax, double ay, double az,
                                  double cos, double sin) {
        double dot = ax * vector.x() + ay * vector.y() + az * vector.z();
        double crossX = ay * vector.z() - az * vector.y();
        double crossY = az * vector.x() - ax * vector.z();
        double crossZ = ax * vector.y() - ay * vector.x();
        return new Vec3(
                vector.x() * cos + crossX * sin + ax * dot * (1.0 - cos),
                vector.y() * cos + crossY * sin + ay * dot * (1.0 - cos),
                vector.z() * cos + crossZ * sin + az * dot * (1.0 - cos));
    }

    /** Guards {@code asin} against a dot product that rounding has pushed a hair past one. */
    private static double clamp(double sine) {
        return sine > 1.0 ? 1.0 : (sine < -1.0 ? -1.0 : sine);
    }

    @Override
    public String toString() {
        return String.format("%s yaw=%.1f\u00b0 pitch=%.1f\u00b0 roll=%.1f\u00b0",
                position, Math.toDegrees(yaw), Math.toDegrees(pitch), Math.toDegrees(roll));
    }

    private static boolean isFinite(double value) {
        return !Double.isNaN(value) && !Double.isInfinite(value);
    }
}
