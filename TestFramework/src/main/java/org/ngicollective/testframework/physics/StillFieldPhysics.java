package org.ngicollective.testframework.physics;

import org.ngicollective.testframework.camera.GameElement;
import org.ngicollective.testframework.camera.Vec3;

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

    StillFieldPhysics(List<GameElement> arrangement) {
        this.arrangement = Collections.unmodifiableList(new ArrayList<>(arrangement));

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

    /** Nothing to release: there is no solver behind this, only the list handed in. */
    @Override
    public void destroy() {
    }
}
