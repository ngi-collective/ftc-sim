package org.ngicollective.testframework.hardware;

/**
 * A team's simulated robot configuration: the same device names and types their real robot
 * configuration file declares.
 *
 * <p>Code-first on purpose. The configuration lives in version control next to the OpModes it
 * serves, so a test and an interactive dashboard session are guaranteed to be driving the same
 * robot, and a device rename shows up as a compile-or-test failure rather than a mystery on the
 * field.</p>
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
