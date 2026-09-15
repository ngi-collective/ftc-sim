package org.ngicollective.testframework.dashboard;

import com.qualcomm.robotcore.eventloop.opmode.LinearOpMode;
import com.qualcomm.robotcore.eventloop.opmode.OpMode;
import com.qualcomm.robotcore.hardware.Gamepad;

import org.ngicollective.testframework.camera.FieldTag;
import org.ngicollective.testframework.camera.FrameSource;
import org.ngicollective.testframework.camera.GameElement;
import org.ngicollective.testframework.camera.SceneFrameSource;
import org.ngicollective.testframework.camera.SimulatedScene;
import org.ngicollective.testframework.camera.Tag36h11;
import org.ngicollective.testframework.camera.TagCluster;
import org.ngicollective.testframework.camera.Vec3;
import org.ngicollective.testframework.dashboard.protocol.Alliance;
import org.ngicollective.testframework.dashboard.protocol.BehaviorSpec;
import org.ngicollective.testframework.dashboard.protocol.DeviceState;
import org.ngicollective.testframework.dashboard.protocol.GamepadState;
import org.ngicollective.testframework.dashboard.protocol.OpModeInfo;
import org.ngicollective.testframework.dashboard.protocol.OpModeStatus;
import org.ngicollective.testframework.dashboard.protocol.ScenePayload;
import org.ngicollective.testframework.dashboard.protocol.SimConfigPayload;
import org.ngicollective.testframework.dashboard.protocol.SimPose;
import org.ngicollective.testframework.dashboard.protocol.SimStatus;
import org.ngicollective.testframework.dashboard.protocol.TelemetryFrame;
import org.ngicollective.testframework.hardware.FakeDcMotorEx;
import org.ngicollective.testframework.hardware.FakeDevice;
import org.ngicollective.testframework.hardware.FakeHardwareMap;
import org.ngicollective.testframework.hardware.FakeImu;
import org.ngicollective.testframework.hardware.FakeServo;
import org.ngicollective.testframework.hardware.FakeWebcam;
import org.ngicollective.testframework.hardware.SimulatedRobot;
import org.ngicollective.testframework.harness.IterativeOpModeHarness;
import org.ngicollective.testframework.harness.LinearOpModeHarness;
import org.ngicollective.testframework.harness.OpModeHarness;
import org.ngicollective.testframework.sim.ChassisConfig;
import org.ngicollective.testframework.sim.ChassisVelocity;
import org.ngicollective.testframework.sim.DriveModel;
import org.ngicollective.testframework.sim.DrivetrainConfig;
import org.ngicollective.testframework.sim.FieldConfig;
import org.ngicollective.testframework.sim.Pose2d;
import org.ngicollective.testframework.sim.RobotConfig;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Runs the OpMode in this JVM against simulated hardware.
 *
 * <p>This is the MVP backend: no robot, no emulator. A ticker thread stands in for the Robot
 * Controller's event loop &mdash; it advances simulated time and, for an iterative OpMode, calls
 * {@code init_loop()}/{@code loop()}. A linear OpMode runs on its own thread exactly as it does
 * under a unit test.</p>
 *
 * <p>Simulated time is not nailed to the wall clock. The ticker fires on a fixed wall-clock period,
 * because that is what keeps the socket's traffic bounded, but how much simulated time each firing
 * hands to the hardware is a session setting: scaled by a multiplier, withheld entirely while
 * paused, and released one cycle at a time by a step. Slowing the robot down by sleeping longer
 * between ticks would have starved every other message instead.</p>
 *
 * <p>Both the cycle and the clock it stamps frames with come from a {@link TickSource}, which
 * production takes from the machine and a test supplies itself. {@link TickSource} says why that
 * seam is here.</p>
 */
public class LocalDashboardBackend implements DashboardBackend {

    /**
     * Control-cycle period. Matches the ~50 Hz the real Robot Controller event loop runs at.
     *
     * <p>Package-private because it is the unit a deterministic test counts in: how far the robot
     * got is a function of how many of these have run.</p>
     */
    static final long TICK_MILLIS = 20;

    /** Device snapshots are for human eyes; every tick would be needless traffic. */
    private static final int TICKS_PER_DEVICE_BROADCAST = 5;

    /**
     * How often a session's OpMode may transmit telemetry, for a session running on real time.
     *
     * <p>A {@code LinearOpMode} calls {@code telemetry.update()} once per loop, and its loop is
     * bounded only by how fast the thread spins &mdash; against zero-latency simulated hardware
     * that is tens of thousands of times a second. Left unthrottled (the harness default, which
     * unit tests want so they see every frame) the session composes and broadcasts frames at that
     * rate, which no browser can drain: the socket backs up and every other message, device state
     * included, arrives minutes late. The Driver Station samples at 250 ms for the same reason;
     * this is the same idea at the device-snapshot rate.</p>
     */
    private static final int TELEMETRY_INTERVAL_MILLIS =
            (int) (TICK_MILLIS * TICKS_PER_DEVICE_BROADCAST);

    /**
     * Bounds on time dilation. Below the floor a person waits seconds between visible changes and
     * concludes the dashboard has hung; above the ceiling one control cycle covers enough simulated
     * time that the discrete drive model stops standing in for a continuous robot.
     */
    private static final double MIN_MULTIPLIER = 0.1;
    private static final double MAX_MULTIPLIER = 8.0;

    /**
     * Faults already reported, so a permanent one is said once instead of fifty times a second.
     *
     * <p>Static because {@link RealTime} is: the net under the scheduler has no session to hang a
     * field on, and a process runs one dashboard.</p>
     */
    private static final Set<String> REPORTED_FAULTS = ConcurrentHashMap.newKeySet();

    private final Map<String, OpModeEntry> opModes = new LinkedHashMap<>();
    private final SimulatedRobot robot;
    private final TickSource time;

    private final List<Consumer<OpModeStatus>> statusListeners = new CopyOnWriteArrayList<>();
    private final List<Consumer<TelemetryFrame>> telemetryListeners = new CopyOnWriteArrayList<>();
    private final List<Consumer<List<DeviceState>>> deviceListeners = new CopyOnWriteArrayList<>();
    private final List<Consumer<SimPose>> simPoseListeners = new CopyOnWriteArrayList<>();
    private final List<Consumer<SimConfigPayload>> simConfigListeners =
            new CopyOnWriteArrayList<>();
    private final List<Consumer<ScenePayload>> sceneListeners = new CopyOnWriteArrayList<>();
    private final List<Consumer<SimStatus>> simStatusListeners = new CopyOnWriteArrayList<>();

    private final Object lock = new Object();

    /**
     * The robot itself, which outlives any OpMode run on it.
     *
     * <p>Never null while the session is open. A real Robot Controller has hardware from the
     * moment it boots &mdash; an OpMode is a thing that runs <em>on</em> a robot, not the thing
     * that brings one into existence &mdash; and the Dashboard Camera View depends on that being
     * true here too: it renders what the camera sees whether or not anything is running.</p>
     */
    private FakeHardwareMap hardware;
    private OpModeHarness harness;
    private OpModeEntry running;
    private OpModeStatus status = OpModeStatus.stopped();
    private int tickCount;

    /** Simulated seconds handed out per wall-clock second. */
    private double multiplier = 1.0;
    private boolean paused;

    /**
     * Control cycles a paused session still owes, from {@code sim/step}. Drained one per tick
     * rather than all at once, so a ten-cycle step animates at the rate the robot really runs at
     * instead of jumping to its end state.
     */
    private int pendingSteps;

    /** Whose driver station this session is played from; decides where heading zero points. */
    private Alliance alliance = Alliance.RED;

    /**
     * The arrangement of the field this session was asked for, or null to render whatever the
     * robot builds for itself.
     *
     * <p>Kept by the session rather than handed to the robot once, because {@link #restLocked()}
     * builds a fresh robot &mdash; and therefore a fresh scene &mdash; on every init and every
     * stop. A scene applied only at startup would revert to the robot's default the first time a
     * driver pressed INIT, which is the moment they would stop believing the view.</p>
     */
    private SimulatedScene sessionScene;

    /**
     * The newest telemetry frame not yet broadcast. Only the latest matters &mdash; the UI shows one
     * composition &mdash; and draining it on the tick keeps outbound traffic bounded by the tick
     * rate no matter what the OpMode does with its transmission interval.
     */
    private final AtomicReference<TelemetryFrame> pendingTelemetry = new AtomicReference<>();

    public LocalDashboardBackend(SimulatedRobot robot, List<OpModeEntry> opModes) {
        this(robot, opModes, new RealTime());
    }

    /**
     * The same session with its time handed to it rather than taken from the machine; see
     * {@link TickSource}.
     */
    LocalDashboardBackend(SimulatedRobot robot, List<OpModeEntry> opModes, TickSource time) {
        this.robot = robot;
        this.time = time;
        for (OpModeEntry entry : opModes) {
            this.opModes.put(entry.info.className, entry);
        }
        synchronized (lock) {
            restLocked();
        }
        time.start(this::tick);
    }

    /**
     * Where a session's control cycles and its wall clock come from.
     *
     * <p>Production has one answer, {@link RealTime}: the daemon ticker this class has always run
     * on. This interface exists for the tests, and on purpose. What this backend does is
     * arithmetic on a cycle count &mdash; n cycles at multiplier m hand the hardware
     * n &times; {@link #TICK_MILLIS} &times; m milliseconds of simulated time, and every pose,
     * pause and step follows from that &mdash; and a test of arithmetic should assert the
     * arithmetic. Without this seam the only way to observe a cycle was to sleep and hope the
     * ticker thread had got far enough, which turned the tests of a deterministic simulator into
     * timing experiments: they measured throughput, they passed on an idle laptop, and they were
     * one loaded machine away from failing for a reason that had nothing to do with the
     * code.</p>
     *
     * <p>A source that starts no thread and instead runs {@code cycle} on the calling thread lets
     * a test state exactly how many cycles happened with nothing else moving in between. For that
     * to hold, everything time-shaped on the cycle path has to arrive through here, which is what
     * the other two methods are for: {@link #nowMillis()} is the only clock outbound frames are
     * stamped from, and {@link #telemetryIntervalMillis()} is the throttle a running OpMode's own
     * telemetry is held back by.</p>
     */
    interface TickSource {

        /** Begins running {@code cycle} once per control cycle. Called once, from the constructor. */
        void start(Runnable cycle);

        /**
         * The wall clock outbound frames are stamped with.
         *
         * <p>Read from a running OpMode's own thread as well as from the cycle, so an
         * implementation has to be safe to call from any thread.</p>
         */
        long nowMillis();

        /**
         * How long a running OpMode's telemetry may be held between transmissions.
         *
         * <p>Enforced by the SDK against {@code System.nanoTime()}, inside {@code TelemetryImpl},
         * where nothing here can reach it: a session that pumps its cycles by hand advances no
         * real time, so leaving this above zero would hold every frame back and make a test of
         * telemetry a test of how long the test took. Such a session turns it off and asserts this
         * backend's own coalescing instead &mdash; at most one frame published per control cycle,
         * which is the guarantee the browser depends on and the one this class is responsible
         * for.</p>
         */
        int telemetryIntervalMillis();

        /** Stops running cycles; the session is closing and will not tick again. */
        void stop();
    }

    /** Production's source: the daemon ticker, the machine clock, the SDK's throttle left alone. */
    private static final class RealTime implements TickSource {

        private final ScheduledExecutorService ticker =
                Executors.newSingleThreadScheduledExecutor(runnable -> {
                    Thread thread = new Thread(runnable, "dashboard-tick");
                    thread.setDaemon(true);
                    return thread;
                });

        /**
         * {@inheritDoc}
         *
         * <p>The cycle is wrapped because {@code scheduleAtFixedRate} answers a task that throws by
         * cancelling it &mdash; not this run, every run, with no log line and no way to ask whether
         * it is still scheduled. The session then keeps its socket open and answers every request
         * while the simulation behind it never advances again: no pose, so the robot never moves,
         * and no device state, so the 3D view never even learns the robot has wheels. {@link #tick()}
         * already contains its own faults; this is the net under it, because the cost of one escaping
         * is the whole session rather than one cycle.</p>
         */
        @Override
        public void start(Runnable cycle) {
            Runnable guarded = () -> {
                try {
                    cycle.run();
                } catch (Throwable e) {
                    reportFault("control cycle", e);
                }
            };
            ticker.scheduleAtFixedRate(guarded, TICK_MILLIS, TICK_MILLIS, TimeUnit.MILLISECONDS);
        }

        @Override
        public long nowMillis() {
            return System.currentTimeMillis();
        }

        @Override
        public int telemetryIntervalMillis() {
            return TELEMETRY_INTERVAL_MILLIS;
        }

        @Override
        public void stop() {
            ticker.shutdownNow();
        }
    }

    /**
     * Builds the robot an idle session sits on: hardware, no OpMode.
     *
     * <p>Also what a run ends into, so a stalled motor or a spun IMU from the last session cannot
     * carry into the next one, and so the camera view and the field view keep working after STOP
     * instead of going blank.</p>
     */
    private void restLocked() {
        hardware = robot.create();
        applyAllianceLocked();
        applySceneLocked();
    }

    /**
     * Puts a chosen arrangement of the field in front of the camera: a HIVE tipped the other way,
     * or the balls left where they broke autonomous last weekend.
     *
     * <p>The scene is conformed to the field the drive model is driving on, for the same reason
     * the robot does it: a season's tag geometry is fixed by the game manual and knows nothing
     * about which perimeter is in the room, and skipping this is how the camera comes to render a
     * competition field beside a field view of a half one.</p>
     *
     * @throws IllegalStateException when this session's camera renders no scene, because an
     *     arrangement then means nothing and accepting it quietly would leave whoever asked
     *     believing there were balls on the field
     */
    public void loadScene(SimulatedScene scene) {
        synchronized (lock) {
            if (!(cameraFramesLocked() instanceof SceneFrameSource)) {
                throw new IllegalStateException("robot \"" + robot.name() + "\" has no"
                        + " scene-backed camera, so it has no field arrangement to change; a"
                        + " scenario needs a webcam built on a SceneFrameSource");
            }
            sessionScene = scene;
            applySceneLocked();
            publishSceneLocked();
        }
    }

    /** Hands {@link #sessionScene}, when there is one, to whatever camera the session now has. */
    private void applySceneLocked() {
        if (sessionScene == null) {
            return;
        }
        FrameSource frames = cameraFramesLocked();
        if (!(frames instanceof SceneFrameSource)) {
            return;
        }
        DriveModel drive = driveLocked();
        ((SceneFrameSource) frames)
                .setScene(drive == null ? sessionScene : sessionScene.on(drive.field()));
    }

    /** The simulated robot configuration this session is driving. */
    public SimulatedRobot robot() {
        return robot;
    }

    @Override
    public List<OpModeInfo> listOpModes() {
        List<OpModeInfo> infos = new ArrayList<>();
        for (OpModeEntry entry : opModes.values()) {
            infos.add(entry.info);
        }
        return infos;
    }

    @Override
    public void initOpMode(String className) {
        OpModeEntry entry = opModes.get(className);
        if (entry == null) {
            throw new IllegalArgumentException("no OpMode named " + className);
        }
        synchronized (lock) {
            stopLocked();
            // Steps were queued against the last run's frozen robot; they are not cycles of this
            // one.
            pendingSteps = 0;

            // A fresh robot every run, and its alliance applied before init runs: an init that
            // calls resetYaw() has to be looking at the same reference heading the driver behind
            // that alliance wall is.
            restLocked();
            OpMode opMode = entry.factory.get();
            if (opMode instanceof LinearOpMode) {
                LinearOpModeHarness linear =
                        OpModeHarness.forLinear((LinearOpMode) opMode, hardware);
                harness = linear;
                wireTelemetry(linear);
                linear.launch();
            } else {
                IterativeOpModeHarness iterative = OpModeHarness.forIterative(opMode, hardware);
                harness = iterative;
                wireTelemetry(iterative);
                iterative.init();
            }
            running = entry;
            setStatusLocked(new OpModeStatus(entry.info.name, OpModeStatus.State.INIT, null));
            // Re-init re-reads the configuration files, so the browser's geometry may be stale even
            // though the robot has not changed identity. The same rebuild gives the camera a new
            // scene, which can have moved a HIVE or removed a ball.
            publishSimConfigLocked();
            publishSceneLocked();
        }
    }

    @Override
    public void start() {
        synchronized (lock) {
            if (harness == null) {
                throw new IllegalStateException("initialize an OpMode before starting it");
            }
            harness.pressStart();
            setStatusLocked(new OpModeStatus(running.info.name, OpModeStatus.State.RUNNING, null));
        }
    }

    @Override
    public void stop() {
        synchronized (lock) {
            stopLocked();
        }
    }

    @Override
    public OpModeStatus status() {
        synchronized (lock) {
            return status;
        }
    }

    @Override
    public void subscribeStatus(Consumer<OpModeStatus> listener) {
        statusListeners.add(listener);
    }

    @Override
    public void subscribeTelemetry(Consumer<TelemetryFrame> listener) {
        telemetryListeners.add(listener);
    }

    @Override
    public void subscribeDeviceState(Consumer<List<DeviceState>> listener) {
        deviceListeners.add(listener);
    }

    @Override
    public void subscribeSimPose(Consumer<SimPose> listener) {
        simPoseListeners.add(listener);
    }

    @Override
    public SimConfigPayload simConfig() {
        synchronized (lock) {
            return simConfigLocked();
        }
    }

    @Override
    public void subscribeSimConfig(Consumer<SimConfigPayload> listener) {
        simConfigListeners.add(listener);
    }

    /**
     * The resting or running robot's camera, whichever the session currently has.
     *
     * <p>Resolved on every call rather than cached, because {@code initOpMode} builds a whole new
     * robot: a cached frame source would keep rendering from the pose of the robot the last run
     * was driving, which is the most misleading thing a view like this could do.</p>
     */
    @Override
    public FrameSource cameraFrames() {
        synchronized (lock) {
            return cameraFramesLocked();
        }
    }

    private FrameSource cameraFramesLocked() {
        for (FakeDevice<?> device : hardware.devices().values()) {
            if (device instanceof FakeWebcam) {
                return ((FakeWebcam) device).frameSource();
            }
        }
        return null;
    }

    @Override
    public ScenePayload scene() {
        synchronized (lock) {
            return sceneLocked();
        }
    }

    @Override
    public void subscribeScene(Consumer<ScenePayload> listener) {
        sceneListeners.add(listener);
    }

    @Override
    public SimStatus simStatus() {
        synchronized (lock) {
            return new SimStatus(multiplier, paused, alliance);
        }
    }

    @Override
    public void subscribeSimStatus(Consumer<SimStatus> listener) {
        simStatusListeners.add(listener);
    }

    @Override
    public void setSimTime(double requestedMultiplier, boolean requestedPause) {
        if (!Double.isFinite(requestedMultiplier)) {
            throw new IllegalArgumentException(
                    "the time multiplier must be a finite number, not " + requestedMultiplier);
        }
        synchronized (lock) {
            double clamped =
                    Math.max(MIN_MULTIPLIER, Math.min(MAX_MULTIPLIER, requestedMultiplier));
            if (clamped == multiplier && requestedPause == paused) {
                return;
            }
            multiplier = clamped;
            paused = requestedPause;
            if (!paused) {
                // Resuming discards queued steps: they were asked for to inspect a frozen robot,
                // and letting them fire into a running one would look like a glitch.
                pendingSteps = 0;
            }
            publishSimStatusLocked();
        }
    }

    @Override
    public void stepSim(int ticks) {
        if (ticks < 1) {
            throw new IllegalArgumentException(
                    "a step is at least one control cycle, not " + ticks);
        }
        synchronized (lock) {
            if (!paused) {
                throw new IllegalStateException(
                        "stepping only means something while paused: a running session is already "
                                + "advancing every cycle");
            }
            pendingSteps += ticks;
        }
    }

    @Override
    public void placeRobot(double xMetres, double yMetres, double headingDegrees) {
        synchronized (lock) {
            requireDriveLocked("place the robot")
                    .setPose(new Pose2d(xMetres, yMetres, Math.toRadians(headingDegrees)));
        }
    }

    @Override
    public void setAlliance(Alliance requested) {
        synchronized (lock) {
            if (requested == alliance) {
                return;
            }
            alliance = requested;
            // Allowed mid-session on purpose: switching sides is how you check that a
            // field-centric drive behaves from the other wall without re-initializing.
            applyAllianceLocked();
            publishSimStatusLocked();
        }
    }

    @Override
    public void injectGamepadState(int gamepadNumber, GamepadState state) {
        synchronized (lock) {
            if (harness == null) {
                return;
            }
            apply(state, gamepadNumber == 2 ? harness.gamepad2() : harness.gamepad1());
        }
    }

    @Override
    public void overrideDeviceBehavior(String device, BehaviorSpec spec) {
        synchronized (lock) {
            FakeDevice<?> target = requireDeviceLocked(device);
            if (target instanceof FakeDcMotorEx) {
                ((FakeDcMotorEx) target).setBehavior(spec.type, spec.asMotorBehavior());
            } else if (target instanceof FakeServo) {
                ((FakeServo) target).setBehavior(spec.type, spec.asServoBehavior());
            } else if (target instanceof FakeImu) {
                ((FakeImu) target).setBehavior(spec.type, spec.asImuBehavior());
            } else {
                throw new IllegalArgumentException(
                        "device \"" + device + "\" has no overridable behavior");
            }
        }
    }

    @Override
    public void resetDeviceBehavior(String device) {
        synchronized (lock) {
            requireDeviceLocked(device).resetBehavior();
        }
    }

    @Override
    public void close() {
        synchronized (lock) {
            stopLocked();
        }
        time.stop();
    }

    private void wireTelemetry(OpModeHarness target) {
        target.setTelemetryTransmissionInterval(time.telemetryIntervalMillis());
        // Runs on the OpMode's own thread: keep the newest frame and let the ticker publish it.
        pendingTelemetry.set(null);
        target.addTelemetryListener(
                lines -> pendingTelemetry.set(new TelemetryFrame(time.nowMillis(), lines)));
    }

    private FakeDevice<?> requireDeviceLocked(String device) {
        FakeDevice<?> target = hardware.devices().get(device);
        if (target == null) {
            throw new IllegalArgumentException("no simulated device named \"" + device + "\"");
        }
        return target;
    }

    private void stopLocked() {
        if (harness == null) {
            return;
        }
        String failure = null;
        if (harness instanceof LinearOpModeHarness) {
            LinearOpModeHarness linear = (LinearOpModeHarness) harness;
            linear.pressStop();
            try {
                linear.awaitCompletion(2000);
            } catch (RuntimeException | Error e) {
                failure = describe(e);
            }
        } else {
            ((IterativeOpModeHarness) harness).stop();
        }
        harness = null;
        running = null;
        restLocked();
        setStatusLocked(new OpModeStatus(null, OpModeStatus.State.STOPPED, failure));
    }

    private void setStatusLocked(OpModeStatus next) {
        status = next;
        for (Consumer<OpModeStatus> listener : statusListeners) {
            listener.accept(next);
        }
    }

    private void publishSimStatusLocked() {
        SimStatus next = new SimStatus(multiplier, paused, alliance);
        for (Consumer<SimStatus> listener : simStatusListeners) {
            listener.accept(next);
        }
    }

    private void publishSimConfigLocked() {
        SimConfigPayload config = simConfigLocked();
        if (config == null) {
            return;
        }
        for (Consumer<SimConfigPayload> listener : simConfigListeners) {
            listener.accept(config);
        }
    }

    private void publishSceneLocked() {
        ScenePayload scene = sceneLocked();
        if (scene == null) {
            return;
        }
        for (Consumer<ScenePayload> listener : sceneListeners) {
            listener.accept(scene);
        }
    }

    /**
     * Puts the IMU's zero where this alliance's driver expects it: pointing away from their own
     * wall, which is what {@code resetYaw()} on a robot set down for a match would have captured.
     */
    private void applyAllianceLocked() {
        DriveModel drive = driveLocked();
        if (drive == null) {
            return;
        }
        hardware.imu(drive.robot().imuName()).state().setYawOffset(alliance.yawOffsetDegrees);
    }

    /**
     * The session's drive model, or null for a robot whose configuration declares no drivetrain
     * &mdash; there is nothing to have a pose.
     */
    private DriveModel driveLocked() {
        return hardware.drive();
    }

    private DriveModel requireDriveLocked(String action) {
        DriveModel drive = driveLocked();
        if (drive == null) {
            throw new IllegalStateException(
                    "cannot " + action + ": " + robot.name() + " declares no drivetrain");
        }
        return drive;
    }

    /** The wire form of the configuration the current session's drive model was built with. */
    private SimConfigPayload simConfigLocked() {
        DriveModel drive = driveLocked();
        if (drive == null) {
            return null;
        }
        RobotConfig config = drive.robot();
        FieldConfig field = drive.field();
        ChassisConfig chassis = config.chassis();
        DrivetrainConfig drivetrain = config.drivetrain();
        return new SimConfigPayload(
                new SimConfigPayload.Robot(
                        config.name(),
                        new SimConfigPayload.Chassis(
                                chassis.widthMetres(), chassis.lengthMetres(),
                                chassis.heightMetres(), chassis.deckHeightMetres()),
                        new SimConfigPayload.Drivetrain(
                                drivetrain.type(), drivetrain.wheelRadiusMetres(),
                                drivetrain.gearRatio(), drivetrain.trackWidthMetres(),
                                drivetrain.wheelBaseMetres(), drivetrain.strafeEfficiency())),
                new SimConfigPayload.Field(
                        field.sizeMetres(), field.wallHeightMetres(), field.tileMetres()));
    }

    /**
     * The wire form of whatever the session's camera is looking at, or null when there is nothing
     * to look at.
     *
     * <p>The scene belongs to the camera rather than to the robot: it is the renderer's input, and
     * a session gets one by configuring a webcam that draws from a {@link SceneFrameSource}. A
     * robot with no camera, or one whose camera paints test patterns instead, genuinely has no
     * field contents to report, and an empty scene would claim the field is bare.</p>
     */
    private ScenePayload sceneLocked() {
        FrameSource frames = cameraFramesLocked();
        if (!(frames instanceof SceneFrameSource)) {
            return null;
        }
        SimulatedScene scene = ((SceneFrameSource) frames).scene();

        List<ScenePayload.Tag> tags = new ArrayList<>();
        for (TagCluster cluster : scene.clusters()) {
            for (FieldTag tag : cluster.tags()) {
                tags.add(tagPayload(tag, cluster.name()));
            }
        }

        List<ScenePayload.Element> elements = new ArrayList<>(scene.elements().size());
        for (GameElement element : scene.elements()) {
            Vec3 centre = element.centre();
            elements.add(new ScenePayload.Element(element.name(),
                    centre.x(), centre.y(), centre.z(), element.radiusMetres(),
                    element.red(), element.green(), element.blue()));
        }
        return new ScenePayload(tags, elements);
    }

    /**
     * One tag, with its corners put into the order the browser's texture mapping needs.
     *
     * <p>{@link FieldTag} is explicit that its +X points to the <em>left</em> of whoever is
     * looking at the printed face and its +Y points up, so the viewer's own axes are
     * {@code left = +tagX} and {@code up = +tagY}. Adding and subtracting half an edge along those
     * two gives the four corners the payload promises directly, with no index table in between.
     * That is deliberately derived here rather than taken from {@link FieldTag#corners()}, which
     * is ordered for the detector: its indices run top-right, top-left, bottom-left, bottom-right,
     * and quietly reusing them would put the browser's texture on mirrored.</p>
     */
    private static ScenePayload.Tag tagPayload(FieldTag tag, String cluster) {
        double half = tag.sizeMetres() / 2.0;
        Vec3 towardsViewersLeft = tag.tagX().scaled(half);
        Vec3 towardsViewersUp = tag.tagY().scaled(half);
        Vec3 centre = tag.pose().position();

        List<ScenePayload.Corner> corners = new ArrayList<>(4);
        corners.add(corner(centre.plus(towardsViewersLeft).plus(towardsViewersUp)));
        corners.add(corner(centre.minus(towardsViewersLeft).plus(towardsViewersUp)));
        corners.add(corner(centre.minus(towardsViewersLeft).minus(towardsViewersUp)));
        corners.add(corner(centre.plus(towardsViewersLeft).minus(towardsViewersUp)));

        return new ScenePayload.Tag(tag.id(), cluster, tag.sizeMetres(), corners,
                cellsOf(tag.id()));
    }

    private static ScenePayload.Corner corner(Vec3 point) {
        return new ScenePayload.Corner(point.x(), point.y(), point.z());
    }

    /**
     * The tag's bit pattern as rows of {@code 'B'} and {@code 'W'}.
     *
     * <p>{@link Tag36h11#isWhite} already addresses cells the way a viewer facing the tag reads
     * them &mdash; row 0 the top, column 0 the left &mdash; which is the orientation the rasteriser
     * samples in and the orientation {@code Tag36h11Test} pins against apriltag's own rendering. So
     * this copies rather than transposes or flips; a conversion here would be a second convention
     * for the same picture.</p>
     */
    private static List<String> cellsOf(int id) {
        List<String> rows = new ArrayList<>(Tag36h11.CELLS_ACROSS);
        for (int row = 0; row < Tag36h11.CELLS_ACROSS; row++) {
            StringBuilder cells = new StringBuilder(Tag36h11.CELLS_ACROSS);
            for (int column = 0; column < Tag36h11.CELLS_ACROSS; column++) {
                cells.append(Tag36h11.isWhite(id, row, column) ? 'W' : 'B');
            }
            rows.add(cells.toString());
        }
        return rows;
    }

    /**
     * One control cycle: advance simulated time, do the Robot Controller event loop's per-iteration
     * housekeeping, run an iterative OpMode's loop, notice a linear OpMode that has ended on its
     * own, and publish whatever the browser needs to see.
     *
     * <p>Nothing is allowed out of here. A cycle is scheduled at a fixed rate, and the scheduler's
     * answer to a task that throws is to cancel every future run of it silently, so one bad cycle
     * used to end the simulation for the life of the process while the socket stayed up and
     * answered normally &mdash; a dashboard that lists OpModes, accepts a start and reads a gamepad,
     * with a robot that never moves and a 3D view that never learns it has wheels. A cycle that
     * fails is worth losing; the session is not.</p>
     */
    private void tick() {
        try {
            Publication published = tickSession();

            // Outside the lock, and once per tick at most: this is the only place telemetry reaches
            // the browser, which is what keeps a fast OpMode loop from flooding the socket.
            TelemetryFrame frame = pendingTelemetry.getAndSet(null);
            if (frame != null) {
                publish("telemetry", telemetryListeners, frame);
            }
            if (published.pose != null) {
                publish("pose", simPoseListeners, published.pose);
            }
            if (published.devices != null) {
                publish("device state", deviceListeners, published.devices);
            }
        } catch (RuntimeException | Error e) {
            reportFault("control cycle", e);
        }
    }

    /**
     * Hands one frame to every listener, keeping them independent of each other.
     *
     * <p>A listener is a browser, and the send at the end of it can fail for reasons that have
     * nothing to do with the simulation or with the other browsers watching it &mdash; a connection
     * closing mid-broadcast is the ordinary one. Without this, the first listener to throw would
     * take the rest of that frame's recipients with it and then the cycle itself.</p>
     */
    private static <T> void publish(String what, List<Consumer<T>> listeners, T frame) {
        for (Consumer<T> listener : listeners) {
            try {
                listener.accept(frame);
            } catch (RuntimeException | Error e) {
                reportFault(what + " listener", e);
            }
        }
    }

    /**
     * Says a fault happened, once per distinct fault.
     *
     * <p>Once, because the cycle runs 50 times a second and a fault that repeats is usually
     * permanent: printing every occurrence would bury the session's own output within seconds. Said
     * at all, because the bug this exists for was invisible &mdash; the simulation stopped and
     * nothing anywhere recorded that it had.</p>
     */
    private static void reportFault(String where, Throwable e) {
        String description = where + ": " + e;
        if (REPORTED_FAULTS.add(description)) {
            System.err.println("[dashboard] " + description
                    + " (contained; the session keeps ticking. This is said once per distinct"
                    + " fault.)");
            e.printStackTrace();
        }
    }

    /** The locked half of a control cycle, and everything it leaves for the browser. */
    private Publication tickSession() {
        synchronized (lock) {
            // A pause withholds simulated time instead of stopping the ticker, so the session stays
            // answerable: pose and device state keep flowing, an override still lands, and a queued
            // step lets exactly one cycle through.
            boolean advancing = !paused;
            if (paused && pendingSteps > 0) {
                pendingSteps--;
                advancing = true;
            }
            double elapsed = TICK_MILLIS / 1000.0 * multiplier;

            if (harness == null) {
                // An idle robot is still a robot: time passes, so a teleport settles and a motor
                // driven by an override actually moves the chassis. Nothing calls into an OpMode,
                // because there is none; the event loop's housekeeping has nothing to house.
                if (advancing) {
                    hardware.advance(elapsed);
                }
                return publicationLocked();
            }

            try {
                if (advancing) {
                    harness.advance(elapsed);
                    harness.eventLoopIteration();
                }
                if (harness instanceof IterativeOpModeHarness) {
                    if (advancing) {
                        IterativeOpModeHarness iterative = (IterativeOpModeHarness) harness;
                        if (status.state == OpModeStatus.State.RUNNING) {
                            iterative.loop();
                        } else {
                            iterative.initLoop();
                        }
                    }
                } else {
                    // A linear OpMode owns its own thread, so a pause cannot stop it spinning; what
                    // it does stop is the hardware underneath changing, which is what "paused"
                    // means to anyone watching. Its death still has to be noticed every tick.
                    LinearOpModeHarness linear = (LinearOpModeHarness) harness;
                    if (linear.isFinished()) {
                        Throwable failure = linear.failure();
                        endRunLocked(failure == null ? null : describe(failure));
                        return publicationLocked();
                    }
                }
            } catch (RuntimeException | Error e) {
                // An OpMode that throws mid-loop ends the session rather than the ticker thread.
                endRunLocked(describe(e));
                return publicationLocked();
            }

            return publicationLocked();
        }
    }

    /**
     * Puts the session back on a resting robot after a run ended by itself &mdash; finished, or
     * thrown out of.
     */
    private void endRunLocked(String failure) {
        harness = null;
        running = null;
        restLocked();
        setStatusLocked(new OpModeStatus(null, OpModeStatus.State.STOPPED, failure));
    }

    /** Everything this tick leaves for the browser. */
    private Publication publicationLocked() {
        return new Publication(
                poseLocked(),
                ++tickCount % TICKS_PER_DEVICE_BROADCAST == 0 ? snapshotLocked() : null);
    }

    /** What one tick has to say: a pose on every tick, a device snapshot on every fifth. */
    private static final class Publication {

        final SimPose pose;
        final List<DeviceState> devices;

        Publication(SimPose pose, List<DeviceState> devices) {
            this.pose = pose;
            this.devices = devices;
        }
    }

    private SimPose poseLocked() {
        DriveModel drive = driveLocked();
        if (drive == null) {
            return null;
        }
        Pose2d pose = drive.pose();
        ChassisVelocity velocity = drive.velocity();
        return new SimPose(
                time.nowMillis(),
                hardware.elapsedSeconds(),
                pose.x(),
                pose.y(),
                pose.headingDegrees(),
                velocity.forwardMetresPerSecond(),
                velocity.lateralMetresPerSecond(),
                Math.toDegrees(velocity.yawRadiansPerSecond()),
                drive.inWallContact());
    }

    private List<DeviceState> snapshotLocked() {
        List<DeviceState> states = new ArrayList<>();
        for (FakeDevice<?> device : hardware.devices().values()) {
            states.add(snapshot(device));
        }
        return states;
    }

    private static DeviceState snapshot(FakeDevice<?> device) {
        if (device instanceof FakeDcMotorEx) {
            FakeDcMotorEx motor = (FakeDcMotorEx) device;
            DeviceState state =
                    new DeviceState(motor.configuredName(), "motor", motor.behaviorLabel());
            state.commandedPower = motor.getPower();
            state.physicalPower = motor.state().getPhysicalPower();
            state.velocityTicksPerSecond = motor.getVelocity();
            state.position = motor.getCurrentPosition();
            state.mode = motor.getMode().name();
            state.clippedCommandCount = motor.state().getClippedCommandCount();
            state.lastClippedCommand = motor.state().getLastClippedCommand();
            return state;
        }
        if (device instanceof FakeServo) {
            FakeServo servo = (FakeServo) device;
            DeviceState state =
                    new DeviceState(servo.configuredName(), "servo", servo.behaviorLabel());
            state.commandedPosition = servo.getPosition();
            state.hornPosition = servo.state().getPosition();
            return state;
        }
        if (device instanceof FakeImu) {
            FakeImu imu = (FakeImu) device;
            DeviceState state = new DeviceState(imu.configuredName(), "imu", imu.behaviorLabel());
            state.yawDegrees = imu.state().reportedYaw();
            state.yawRateDegreesPerSecond = imu.state().getYawRateDegreesPerSecond();
            return state;
        }
        return new DeviceState(device.configuredName(), "unknown", device.behaviorLabel());
    }

    private static void apply(GamepadState from, Gamepad to) {
        to.left_stick_x = from.left_stick_x;
        to.left_stick_y = from.left_stick_y;
        to.right_stick_x = from.right_stick_x;
        to.right_stick_y = from.right_stick_y;
        to.left_trigger = from.left_trigger;
        to.right_trigger = from.right_trigger;
        to.dpad_up = from.dpad_up;
        to.dpad_down = from.dpad_down;
        to.dpad_left = from.dpad_left;
        to.dpad_right = from.dpad_right;
        to.left_bumper = from.left_bumper;
        to.right_bumper = from.right_bumper;
        to.left_stick_button = from.left_stick_button;
        to.right_stick_button = from.right_stick_button;
        to.a = from.a;
        to.b = from.b;
        to.x = from.x;
        to.y = from.y;
        to.options = from.options;
    }

    private static String describe(Throwable failure) {
        Throwable root = failure;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        return root.getClass().getSimpleName() + ": " + root.getMessage();
    }
}
