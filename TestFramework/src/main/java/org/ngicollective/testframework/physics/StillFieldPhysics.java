package org.ngicollective.testframework.physics;

import org.ngicollective.testframework.camera.GameElement;
import org.ngicollective.testframework.camera.Vec3;
import org.ngicollective.testframework.sim.Chassis;
import org.ngicollective.testframework.sim.FieldConfig;
import org.ngicollective.testframework.sim.RobotConfig;
import org.ngicollective.testframework.sim.SensorConfig;
import org.ngicollective.testframework.sim.VolumeConfig;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A field whose balls stay exactly where the arrangement put them.
 *
 * <p>What the simulator does when ode4j is not on the classpath. Not a stub and not a placeholder:
 * it is the behaviour this simulator had before physics existed, and it is a legitimate way to run
 * a vision test, where what matters is that a ball of a known colour is at a known place and
 * nothing is supposed to disturb it.</p>
 *
 * <p>It exists so the licence boundary can be enforced by deleting a jar rather than by trusting
 * everyone to remember. See {@link FieldPhysics} for why that matters.</p>
 */
final class StillFieldPhysics implements FieldPhysics {

    private final List<GameElement> arrangement;
    private final List<BodyState> bodies;

    /**
     * The robot, or null when this hardware map declares no drivetrain.
     *
     * <p>A kinematic chassis: it goes exactly where its wheels say and cannot be shoved by
     * anything, which is the behaviour this simulator had before a solver existed. Held here
     * rather than built by the drive model so that the rule "the world owns the robot's pose"
     * holds on both implementations &mdash; the difference between them is how the robot moves,
     * not who is allowed to say where it is.</p>
     */
    private final Chassis chassis;

    StillFieldPhysics(List<GameElement> arrangement, FieldConfig field, RobotConfig robot) {
        this.arrangement = Collections.unmodifiableList(new ArrayList<>(arrangement));
        this.chassis = robot == null ? null : Chassis.kinematic(robot, field);

        List<BodyState> resting = new ArrayList<>(arrangement.size());
        for (int id = 0; id < arrangement.size(); id++) {
            Vec3 centre = arrangement.get(id).centre();
            resting.add(new BodyState(id, centre.x(), centre.y(), centre.z(), 0.0, 0.0, 0.0, 1.0));
        }
        this.bodies = Collections.unmodifiableList(resting);
    }

    /**
     * Steps the robot, and nothing else: there is nothing here that can move on its own.
     *
     * <p>The chassis is stepped from here rather than from the drive model because the world is
     * what owns the clock, and one world stepped from two places is two clocks.</p>
     */
    @Override
    public void advance(double seconds) {
        if (seconds < 0.0) {
            throw new IllegalArgumentException("cannot advance physics backwards");
        }
        if (chassis != null) {
            chassis.step(seconds);
        }
    }

    @Override
    public Chassis chassis() {
        return chassis;
    }

    @Override
    public List<GameElement> elements() {
        return arrangement;
    }

    @Override
    public List<BodyState> bodies() {
        return bodies;
    }

    /**
     * Nothing, because nothing here can turn: a HIVE without a solver holds whatever state its
     * scenario staged it in, which is the same deal the balls get.
     */
    @Override
    public List<PivotState> pivots() {
        return Collections.emptyList();
    }

    /**
     * Never. Which is also what keeps this cheap: the dashboard publishes body frames only while
     * something is moving, so a session on this implementation sends its arrangement once and then
     * says nothing more about it.
     */
    @Override
    public boolean moving() {
        return false;
    }

    /**
     * Still answered, and exactly, because a robot can drive up to a ball that cannot move.
     *
     * <p>Which makes a session without ode4j more useful than it sounds: an intake that cannot
     * actually pick anything up still knows when a ball is in its mouth, so an OpMode's decisions
     * can be tested even where its mechanism's effects cannot.</p>
     */
    @Override
    public List<GameElement> touching(VolumeConfig volume) {
        if (chassis == null) {
            return Collections.emptyList();
        }
        List<GameElement> found = new ArrayList<>(2);
        for (GameElement element : arrangement) {
            if (RobotFrame.touches(chassis.pose(), volume, element.centre(),
                    element.radiusMetres())) {
                found.add(element);
            }
        }
        return Collections.unmodifiableList(found);
    }

    /**
     * Always out of range.
     *
     * <p>A beam needs something to cast against, and the collision world is the thing that is
     * missing here. {@code NaN} is the honest answer and the one the SDK's own sensors give when
     * they see nothing, so an OpMode meets a reading it already has to handle rather than a
     * plausible distance to a wall that was never measured.</p>
     */
    @Override
    public double rangeAlong(SensorConfig sensor) {
        return Double.NaN;
    }

    /** Nothing to run: there is no surface here, only a list of places balls are. */
    @Override
    public void setSweepPower(String servoName, double power) {
    }

    /**
     * Nothing to throw. A ball here cannot be moved by anything, and a launcher that quietly did
     * nothing is the honest answer for a build with no solver behind it.
     */
    @Override
    public void setLauncherSpeed(String motorName, double surfaceMetresPerSecond) {
    }

    /** Nothing to release: there is no solver behind this, only the list handed in. */
    @Override
    public void destroy() {
    }
}
