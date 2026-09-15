package org.ngicollective.testframework.hardware;

import java.nio.file.Path;

/**
 * A team's simulated robot configuration: the same device names and types their real robot
 * configuration file declares.
 *
 * <p>Device <em>names</em> are code on purpose. They live in version control next to the OpModes
 * they serve, so a test and an interactive dashboard session are guaranteed to be driving the same
 * robot, and a device rename shows up as a compile-or-test failure rather than a mystery on the
 * field.</p>
 *
 * <p>Device <em>physics</em> &mdash; encoder resolution, free speed, wheel radius, chassis
 * dimensions &mdash; is data instead, read from {@code TeamCode/robot-config/}. The drive model
 * integrates those numbers into a field pose and the 3D scene draws from the same file, so a single
 * copy is the only way the simulator and its picture can stay in agreement.</p>
 *
 * <p>Implementations must return a fresh map on every call: a session that has stalled a motor or
 * spun the IMU must not leak that state into the next run.</p>
 */
public interface SimulatedRobot {

    /** A human-readable name for this configuration, e.g. {@code "Verity"}. */
    String name();

    /** Builds a fresh simulated robot. */
    FakeHardwareMap create();

    /**
     * The configuration file this robot's numbers came from, or null when they did not come from
     * one.
     *
     * <p>Null is the answer for a robot handed its {@code RobotConfig} by a test, and it means
     * "these numbers cannot be written back": there is no file to write them into. A caller that
     * wants to save something a person adjusted &mdash; the dashboard saving a camera mount
     * &mdash; has to ask, because the alternative is inventing a path and creating a
     * configuration file nobody asked for.</p>
     */
    default Path configurationFile() {
        return null;
    }
}
