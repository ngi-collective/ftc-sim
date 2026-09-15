package org.ngicollective.testframework.physics;

import org.ngicollective.testframework.camera.GameElement;
import org.ngicollective.testframework.camera.Vec3;
import org.ngicollective.testframework.sim.DriveModel;
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
     * The drive model, or null when this robot has no drivetrain.
     *
     * <p>Held for the sensors alone: nothing here can move, but where the robot <em>is</em> decides
     * what its intake and its sensors are looking at.</p>
     */
    private final DriveModel drive;

    StillFieldPhysics(List<GameElement> arrangement, DriveModel drive) {
        this.arrangement = Collections.unmodifiableList(new ArrayList<>(arrangement));
        this.drive = drive;

        List<BodyState> resting = new ArrayList<>(arrangement.size());
        for (int id = 0; id < arrangement.size(); id++) {
            Vec3 centre = arrangement.get(id).centre();
            resting.add(new BodyState(id, centre.x(), centre.y(), centre.z(), 0.0, 0.0, 0.0, 1.0));
        }
        this.bodies = Collections.unmodifiableList(resting);
    }

    @Override
    public void advance(double seconds) {
        if (seconds < 0.0) {
            throw new IllegalArgumentException("cannot advance physics backwards");
        }
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
        if (drive == null) {
            return Collections.emptyList();
        }
        List<GameElement> found = new ArrayList<>(2);
        for (GameElement element : arrangement) {
            if (RobotFrame.touches(drive.pose(), volume, element.centre(),
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

    /** Nothing to release: there is no solver behind this, only the list handed in. */
    @Override
    public void destroy() {
    }
}
