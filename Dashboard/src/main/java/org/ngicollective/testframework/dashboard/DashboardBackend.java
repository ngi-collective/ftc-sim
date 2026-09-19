package org.ngicollective.testframework.dashboard;

import org.ngicollective.testframework.camera.FrameSource;
import org.ngicollective.testframework.dashboard.protocol.Alliance;
import org.ngicollective.testframework.dashboard.protocol.BehaviorSpec;
import org.ngicollective.testframework.dashboard.protocol.BodiesPayload;
import org.ngicollective.testframework.dashboard.protocol.CameraMountPayload;
import org.ngicollective.testframework.dashboard.protocol.DeviceState;
import org.ngicollective.testframework.dashboard.protocol.GamepadState;
import org.ngicollective.testframework.dashboard.protocol.OpModeInfo;
import org.ngicollective.testframework.dashboard.protocol.OpModeStatus;
import org.ngicollective.testframework.dashboard.protocol.ScenariosPayload;
import org.ngicollective.testframework.dashboard.protocol.ScenePayload;
import org.ngicollective.testframework.dashboard.protocol.ScorePayload;
import org.ngicollective.testframework.dashboard.protocol.SimConfigPayload;
import org.ngicollective.testframework.dashboard.protocol.SimPose;
import org.ngicollective.testframework.dashboard.protocol.SimStatus;
import org.ngicollective.testframework.dashboard.protocol.TelemetryFrame;

import java.nio.file.Path;
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

    /**
     * What is standing on the field for the Field View to draw, or null when this session has no
     * scene to publish.
     *
     * <p>Null for the same kind of reason {@link #simConfig()} is null for a robot with no
     * drivetrain: a robot with no camera, or one whose camera renders something other than a
     * simulated scene, has no field contents to describe. Reporting an empty scene instead would
     * tell the browser the field is bare, which is a different and false statement.</p>
     */
    ScenePayload scene();

    /** Fires on every OpMode init, because a re-init rebuilds the robot and so its scene. */
    void subscribeScene(Consumer<ScenePayload> listener);

    /**
     * Where this session's camera is mounted and what it sees from there, or null when the robot
     * declares no camera.
     *
     * <p>The mount the session is <em>using</em>, which is not always the one on disk: see
     * {@link #setCameraMount}.</p>
     */
    CameraMountPayload cameraMount();

    /**
     * Aims the camera, effective on the next rendered frame.
     *
     * <p>No INIT and no file write, because the question this answers &mdash; "is the tag in shot
     * from this angle" &mdash; is answered by looking at the view while dragging. A mount that
     * needed a rebuild to take effect would make that a guess-and-check loop several seconds
     * long, and one that wrote the file on every drag would commit every angle anyone tried.
     * Saving is {@link #saveCameraMount()}, and is a separate thing a person asks for.</p>
     *
     * <p>Session state, like the alliance and the field arrangement: it survives the rebuild that
     * INIT and STOP do.</p>
     *
     * @throws IllegalStateException if this session's robot has no scene-backed camera to aim
     */
    void setCameraMount(double forwardMetres, double leftMetres, double heightMetres,
                        double yawDegrees, double pitchDegrees, double rollDegrees);

    /**
     * Writes the session's mount into the robot's configuration file and returns the file.
     *
     * @throws IllegalStateException if the robot's numbers did not come from a file, since there
     *     is then nowhere to put them
     */
    Path saveCameraMount();

    /** Drops the session's mount and goes back to the one the configuration file describes. */
    void revertCameraMount();

    /** Fires whenever the mount changes, saved or not. */
    void subscribeCameraMount(Consumer<CameraMountPayload> listener);

    /**
     * Fires on every control cycle in which a body on the field moved, and on none of the others.
     *
     * <p>No accompanying getter, unlike {@link #scene()}: there is nothing for a newly connected
     * browser to ask for. The scene it is greeted with already carries every element's current
     * position, so this stream only has to say what changes after that.</p>
     */
    void subscribeBodies(Consumer<BodiesPayload> listener);

    /**
     * What the field on the table is worth, or null when this session has no scene to score.
     *
     * <p>Null for the same reason {@link #scene()} is: a robot whose camera renders no simulated
     * field has no CELLs to put balls in, and a zero would claim it had two empty ones.</p>
     */
    ScorePayload score();

    /**
     * Fires when the score changes, and in the greeting &mdash; unlike {@link #subscribeBodies}.
     *
     * <p>A getter as well as a stream, because nothing else on this socket lets a browser derive
     * the score for itself: see {@link ScorePayload}.</p>
     */
    void subscribeScore(Consumer<ScorePayload> listener);

    /**
     * The field arrangements available and the one in force, or null when this session has no
     * scene to arrange.
     *
     * <p>Null for the same reason {@link #scene()} is, and it is what tells the browser not to
     * offer a picker at all: a robot whose camera renders no simulated field cannot be put into an
     * arrangement, so a list of them would be a control that does nothing.</p>
     */
    ScenariosPayload scenarios();

    /**
     * Puts the field into a named arrangement, or back to the robot's own with null.
     *
     * <p>By name rather than by a parsed scene, so that reading the file, deciding what is
     * available and remembering what is in force all stay in one place. A browser that had to send
     * an arrangement would need the season's CAD to build one.</p>
     *
     * <p>Allowed mid-run, and deliberately not refused: moving the balls under a driving robot is
     * a thing an operator can ask for, and the alternative &mdash; a control that greys out while
     * an OpMode is up &mdash; would make it impossible to set up the situation a bug needs
     * without stopping first.</p>
     *
     * @throws IllegalArgumentException if no scenario has that name, which is a typo rather than a
     *     reason to quietly show the official field
     * @throws IllegalStateException if this session has no scene-backed camera to arrange
     */
    void loadScenario(String name);

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
