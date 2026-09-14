package org.ngicollective.testframework.dashboard;

import org.ngicollective.testframework.camera.FrameSource;
import org.ngicollective.testframework.dashboard.protocol.Alliance;
import org.ngicollective.testframework.dashboard.protocol.BehaviorSpec;
import org.ngicollective.testframework.dashboard.protocol.DeviceState;
import org.ngicollective.testframework.dashboard.protocol.GamepadState;
import org.ngicollective.testframework.dashboard.protocol.OpModeInfo;
import org.ngicollective.testframework.dashboard.protocol.OpModeStatus;
import org.ngicollective.testframework.dashboard.protocol.SimConfigPayload;
import org.ngicollective.testframework.dashboard.protocol.SimPose;
import org.ngicollective.testframework.dashboard.protocol.SimStatus;
import org.ngicollective.testframework.dashboard.protocol.TelemetryFrame;

import java.util.List;
import java.util.function.Consumer;

/**
 * Everything the dashboard needs from whatever is running the OpMode.
 *
 * <p>The point of the seam: the browser and the wire protocol are identical whether the OpMode runs
 * in this JVM ({@link LocalDashboardBackend}) or inside the real Robot Controller app on an
 * emulator. Only this interface gets a second implementation.</p>
 *
 * <p>Implementations must be safe to call from the WebSocket server's threads.</p>
 */
public interface DashboardBackend {

    /** The OpModes a user can select, as the Driver Station would list them. */
    List<OpModeInfo> listOpModes();

    /**
     * Selects an OpMode and runs its init phase on a freshly built simulated robot.
     *
     * @param className the {@link OpModeInfo#className} of the OpMode to initialize
     */
    void initOpMode(String className);

    /** The PLAY button. */
    void start();

    /** The STOP button. Safe to call when nothing is running. */
    void stop();

    /** Current lifecycle state; also pushed to {@link #subscribeStatus} whenever it changes. */
    OpModeStatus status();

    void subscribeStatus(Consumer<OpModeStatus> listener);

    /** Every {@code telemetry.update()} the running OpMode makes. */
    void subscribeTelemetry(Consumer<TelemetryFrame> listener);

    /** Periodic snapshots of every simulated device. */
    void subscribeDeviceState(Consumer<List<DeviceState>> listener);

    /**
     * Where the simulated chassis is, once per control cycle.
     *
     * <p>Every tick, not on the device-snapshot cadence: this drives an animation, and a robot
     * redrawn ten times a second reads as stuttering rather than as fast.</p>
     *
     * <p>Flows whether or not an OpMode is running, because the robot exists either way. Silent
     * only for a robot whose configuration declares no drivetrain &mdash; there is no pose to
     * report for hardware that cannot drive.</p>
     */
    void subscribeSimPose(Consumer<SimPose> listener);

    /**
     * The robot and field geometry this session's robot was built with, or null for a robot that
     * declares no drivetrain.
     */
    SimConfigPayload simConfig();

    /** Fires on every OpMode init, because a re-init re-reads the configuration files. */
    void subscribeSimConfig(Consumer<SimConfigPayload> listener);

    /**
     * What the simulated robot's camera can see, for the Dashboard Camera View to render.
     *
     * <p>Available whenever the session has a robot with a camera, OpMode or no OpMode: the view
     * exists to answer "is the tag in shot from here", and needing to start an OpMode first would
     * make it useless for exactly that. This is a deliberate divergence from real hardware, where
     * nothing produces frames until a {@code VisionPortal} opens the camera.</p>
     *
     * <p>Null when the robot declares no camera. Callers render from it directly and must not
     * assume any streaming state.</p>
     */
    FrameSource cameraFrames();

    /** How the simulation is being run; also pushed to {@link #subscribeSimStatus} on change. */
    SimStatus simStatus();

    void subscribeSimStatus(Consumer<SimStatus> listener);

    /**
     * Sets time dilation and the pause flag.
     *
     * @param multiplier simulated seconds per wall-clock second; implementations clamp it to a
     *                   range where the control loop still behaves like a control loop
     * @param paused     true to freeze simulated time without ending the run
     */
    void setSimTime(double multiplier, boolean paused);

    /**
     * Advances exactly {@code ticks} control cycles while paused, one per tick of the event loop,
     * so a person can walk a manoeuvre forward and watch each cycle's effect.
     */
    void stepSim(int ticks);

    /** Teleports the chassis, for setting up a situation without driving to it. Degrees. */
    void placeRobot(double xMetres, double yMetres, double headingDegrees);

    /**
     * Picks the driver station the session is played from, which sets the IMU yaw offset so heading
     * zero means "facing away from my own wall".
     */
    void setAlliance(Alliance alliance);

    /**
     * Applies driver input.
     *
     * @param gamepad 1 or 2
     */
    void injectGamepadState(int gamepad, GamepadState state);

    /** Swaps a device's behavior mid-run, to inject a fault. */
    void overrideDeviceBehavior(String device, BehaviorSpec spec);

    /** Restores the behavior the simulated robot declared for this device. */
    void resetDeviceBehavior(String device);

    /** Stops anything running and releases the backend's threads. */
    void close();
}
