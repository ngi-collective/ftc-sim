package org.ngicollective.testframework.hardware;

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
}
