package org.ngicollective.testframework.physics;

import org.ngicollective.testframework.camera.Vec3;
import org.ngicollective.testframework.sim.Pose2d;
import org.ngicollective.testframework.sim.VolumeConfig;

/**
 * The one conversion between something bolted to the robot and where it is on the field.
 *
 * <p>A mount is measured with a ruler against the robot: so many metres out of the nose, so many
 * to the left, so many up from the floor. Everything the physics world knows is in the field frame.
 * This is where those meet, and it is one place rather than one per sensor because the failure it
 * prevents is silent: a sign error here puts an intake behind the robot, where it quietly sweeps
 * nothing and reads as a mechanism that does not work.</p>
 *
 * <p>Both frames are right-handed with {@code +Z} up, so there is no handedness story &mdash; only
 * a rotation by the heading. Compare {@code driver-hub-dashboard/src/scene/frame.ts}, which does
 * have one, because three.js draws with {@code Y} up.</p>
 */
final class RobotFrame {

    private RobotFrame() {
    }

    /** Field {@code X} of a point {@code forward} out and {@code left} across from the robot. */
    static double fieldX(Pose2d pose, double forward, double left) {
        return pose.x() + forward * Math.cos(pose.heading()) - left * Math.sin(pose.heading());
    }

    /** Field {@code Y} of the same point. */
    static double fieldY(Pose2d pose, double forward, double left) {
        return pose.y() + forward * Math.sin(pose.heading()) + left * Math.cos(pose.heading());
    }

    /**
     * Whether a ball of {@code radius} centred at {@code centre} touches a box fixed to the robot.
     *
     * <p>Touches, not contains. A sensor in an intake's mouth is looking at a ball the mouth is
     * about to swallow, and that ball's centre is still outside the mouth when its surface is
     * already in it &mdash; a centre-in-box test would report an empty intake at exactly the moment
     * a driver can see the ball sitting in it. The arithmetic is the standard one: clamp the
     * centre into the box, and the box is touched if the clamped point is within a radius.</p>
     */
    static boolean touches(Pose2d pose, VolumeConfig volume, Vec3 centre, double radius) {
        return touches(pose, volume, centre.x(), centre.y(), centre.z(), radius);
    }

    /**
     * The same, from raw coordinates.
     *
     * <p>Because the sweep runs inside the solver's own loop, two hundred and fifty times a second
     * per ball, and a {@link Vec3} per test would be a few thousand short-lived objects a second
     * to answer a question about six numbers.</p>
     */
    static boolean touches(Pose2d pose, VolumeConfig volume,
                           double x, double y, double z, double radius) {
        double awayX = x - pose.x();
        double awayY = y - pose.y();
        double cos = Math.cos(pose.heading());
        double sin = Math.sin(pose.heading());

        // Into the robot's own frame: how far ahead of it, how far to its left, how far up.
        double forward = awayX * cos + awayY * sin;
        double left = -awayX * sin + awayY * cos;

        double overshootForward = Math.max(0.0,
                Math.abs(forward - volume.forwardMetres()) - volume.lengthMetres() / 2.0);
        double overshootLeft = Math.max(0.0,
                Math.abs(left - volume.leftMetres()) - volume.widthMetres() / 2.0);
        double overshootUp = Math.max(0.0,
                Math.abs(z - volume.heightMetres()) - volume.tallMetres() / 2.0);

        double gapSquared = overshootForward * overshootForward
                + overshootLeft * overshootLeft
                + overshootUp * overshootUp;
        return gapSquared <= radius * radius;
    }
}
