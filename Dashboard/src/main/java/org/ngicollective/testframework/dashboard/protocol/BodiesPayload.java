package org.ngicollective.testframework.dashboard.protocol;

import java.util.List;

/**
 * Where every moving body on the field is, pushed on every control cycle that moved one.
 *
 * <p>Separate from {@link ScenePayload} because the two change at wildly different rates and cost
 * wildly different amounts to apply. A scene carries sixteen AprilTags with four corners and
 * sixty-four pattern cells each, and the browser turns it into geometry and textures; republishing
 * that fifty times a second to move six balls three millimetres would rebuild every tag mesh on
 * every tick. So the scene says what is on the field and what it looks like, once, and this says
 * where it is, continuously.</p>
 *
 * <p>{@link Body#id} is the index of the element in the scene's {@code elements} list, which is
 * what ties the two together: colour and radius come from the scene, position from here.</p>
 *
 * <p>Like the pose, this rides the un-throttled path to the 3D view rather than React state. It is
 * also only sent while something is actually in motion &mdash; a field nobody has driven into
 * publishes its arrangement once and then goes quiet, so an idle session costs no traffic and a
 * socket log shows a rolling ball rather than drowning it.</p>
 */
public final class BodiesPayload {

    /** Wall clock, matching {@link SimPose#timestampMillis}: what the browser interpolates against. */
    public final long timestampMillis;

    /** Simulated seconds since the hardware map was built, for the same reason the pose carries it. */
    public final double elapsedSeconds;

    public final List<Body> bodies;

    /**
     * Every pivoting structure that has turned: a HIVE mid-tip, and otherwise empty.
     *
     * <p>Beside the balls rather than in a message of its own because they move for the same
     * reason at the same moment &mdash; the shot that tips a HIVE is also the shot that scatters
     * what was in it &mdash; and a browser receiving them separately would interpolate the basket
     * and its contents against two different timestamps.</p>
     *
     * <p>{@link Tip#structureName} keys into the scene's {@code structures} by name and not by
     * index: there are two of these on a field, their names are the manual's, and list order is
     * not something the scene promises.</p>
     */
    public final List<Tip> pivots;

    public BodiesPayload(long timestampMillis, double elapsedSeconds, List<Body> bodies,
                         List<Tip> pivots) {
        this.timestampMillis = timestampMillis;
        this.elapsedSeconds = elapsedSeconds;
        this.bodies = bodies;
        this.pivots = pivots;
    }

    /**
     * How far round one pivoting structure is now, in its pivot's own absolute measure.
     *
     * <p>One number, because a pivot has one degree of freedom. A quaternion would let a browser
     * draw a HIVE at an angle its own axis cannot reach, and would need the axis repeated on every
     * frame to be interpretable at all.</p>
     */
    public static final class Tip {

        public final String structureName;
        public final double angleRadians;

        public Tip(String structureName, double angleRadians) {
            this.structureName = structureName;
            this.angleRadians = angleRadians;
        }
    }

    /**
     * One body: which it is, where it is, and how it is turned.
     *
     * <p>Position is in the FTC field frame, metres, {@code +Z} up. Orientation is a unit
     * quaternion in the same frame, in {@code (x, y, z, w)} order &mdash; the order three.js takes
     * and the order the browser can hand straight to a mesh, rather than ODE's own
     * {@code (w, x, y, z)}. A flat-shaded sphere cannot show its spin, but a ball that slides when
     * it should roll is a wrong-friction bug, and this is where that shows.</p>
     */
    public static final class Body {

        public final int id;

        public final double x;
        public final double y;
        public final double z;

        public final double qx;
        public final double qy;
        public final double qz;
        public final double qw;

        public Body(int id, double x, double y, double z,
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
    }
}
