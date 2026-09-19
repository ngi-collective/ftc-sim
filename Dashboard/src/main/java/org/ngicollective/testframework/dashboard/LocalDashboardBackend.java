package org.ngicollective.testframework.dashboard;

import com.qualcomm.robotcore.eventloop.opmode.LinearOpMode;
import com.qualcomm.robotcore.eventloop.opmode.OpMode;
import com.qualcomm.robotcore.hardware.Gamepad;

import org.ngicollective.testframework.camera.CameraIntrinsics;
import org.ngicollective.testframework.camera.FieldTag;
import org.ngicollective.testframework.camera.FrameSource;
import org.ngicollective.testframework.camera.GameElement;
import org.ngicollective.testframework.camera.Pivot;
import org.ngicollective.testframework.camera.Pose3d;
import org.ngicollective.testframework.camera.SceneFrameSource;
import org.ngicollective.testframework.camera.ScoringVolume;
import org.ngicollective.testframework.camera.SimulatedCamera;
import org.ngicollective.testframework.camera.SimulatedScene;
import org.ngicollective.testframework.camera.Solid;
import org.ngicollective.testframework.camera.Structure;
import org.ngicollective.testframework.camera.Tag36h11;
import org.ngicollective.testframework.camera.TagCluster;
import org.ngicollective.testframework.camera.Vec3;
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
import org.ngicollective.testframework.physics.BodyState;
import org.ngicollective.testframework.physics.FieldPhysics;
import org.ngicollective.testframework.physics.PivotState;
import org.ngicollective.testframework.season.BioBuzzScore;
import org.ngicollective.testframework.sim.CameraMount;
import org.ngicollective.testframework.sim.ChassisConfig;
import org.ngicollective.testframework.sim.ChassisVelocity;
import org.ngicollective.testframework.sim.DriveModel;
import org.ngicollective.testframework.sim.DrivetrainConfig;
import org.ngicollective.testframework.sim.FieldConfig;
import org.ngicollective.testframework.sim.Pose2d;
import org.ngicollective.testframework.sim.RobotConfig;
import org.ngicollective.testframework.sim.RobotConfigFile;
import org.ngicollective.testframework.sim.SimConfigFiles;

import java.nio.file.Path;
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
    private final List<Consumer<BodiesPayload>> bodyListeners = new CopyOnWriteArrayList<>();
    private final List<Consumer<ScorePayload>> scoreListeners = new CopyOnWriteArrayList<>();
    private final List<Consumer<SimStatus>> simStatusListeners = new CopyOnWriteArrayList<>();
    private final List<Consumer<CameraMountPayload>> cameraMountListeners =
            new CopyOnWriteArrayList<>();

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
     * What the robot built for itself the last time this session made one, or null for a robot
     * with no scene-backed camera.
     *
     * <p>The thing {@link #sessionScene} is laid over, kept so that it can be lifted off again.
     * Loading a scenario overwrites the camera's scene, so without this the robot's own
     * arrangement was gone for the life of the process and "no scenario" was a state a session
     * could leave but never return to.</p>
     */
    private SimulatedScene robotScene;

    /**
     * The name {@link #sessionScene} was loaded under, or null when the field is the robot's own.
     *
     * <p>Only a label &mdash; nothing reads it back to rebuild anything &mdash; but it is the
     * label the browser shows as selected, and the one thing a {@link SimulatedScene} cannot be
     * asked for. Cleared by {@link #loadScene}, because a scene handed over directly has no name
     * and claiming the last one would be a lie about what is on the table.</p>
     */
    private String activeScenario;

    /**
     * Where the robot's configuration put the camera when the session last built one, or null for
     * a robot with no scene-backed camera.
     *
     * <p>Kept so that {@link #cameraMount()} can say whether the session is holding something the
     * file does not have. Read afresh on every rebuild, because the file is read afresh on every
     * rebuild: someone who edits {@code verity.json} and presses INIT has changed what "saved"
     * means.</p>
     */
    private CameraMount fileMount;

    /**
     * The mount this session is aiming the camera with instead, or null to follow the file.
     *
     * <p>Session state, for the reason {@link #sessionScene} is: it is a choice made by whoever is
     * sitting in front of the dashboard, and {@link #restLocked()} builds a fresh robot on every
     * init and every stop. An override applied only when it arrived would spring back to the
     * file's angle the first time a driver pressed INIT, which is the moment they would stop
     * believing the view they are aiming by.</p>
     */
    private CameraMount sessionMount;

    /**
     * The physics world the balls on the field live in, or null when this session's robot has no
     * camera rendering a scene and therefore no field contents to simulate.
     *
     * <p>Held beside {@link #sessionScene} and for the same reason: the world references the drive
     * model whose pose the robot's collision box follows, and {@link #restLocked()} replaces that
     * on every init and every stop.</p>
     */
    private FieldPhysics physics;

    /**
     * The score as the browser last heard it, so an unchanged one is not said again.
     *
     * <p>A score changes when a ball crosses a CELL's mouth, which happens a handful of times in
     * a match; the alternative is fifty identical messages a second for two and a half minutes.
     * Null before the first publication, which is also what a session with no field to score
     * stays at.</p>
     */
    private ScorePayload publishedScore;

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
     *
     * <p>The configuration files are read again here, because {@link SimulatedRobot#create()}
     * reads them: the numbers a robot is built from are data, and the whole point of data in a
     * file is that editing it changes what happens next. Four things are deliberately carried
     * across the rebuild instead of being rebuilt with it &mdash; the alliance, the arrangement of
     * the field, where the robot is standing, and an unsaved camera mount. All four are choices
     * made by whoever is sitting in front of the dashboard rather than properties of the robot,
     * and someone aiming a camera drags the chassis somewhere worth looking at and presses INIT:
     * teleporting it back to the middle of the field on every INIT and every STOP, or springing
     * the camera back to the file's angle, would make that impossible to do twice.</p>
     *
     * <p>A configuration file that will not parse throws out of {@code create()} before anything
     * here is replaced, which leaves the session running on the last robot that did parse.</p>
     */
    private void restLocked() {
        Pose2d standing = hardware == null || hardware.drive() == null
                ? null
                : hardware.drive().pose();
        hardware = robot.create();
        if (standing != null && hardware.drive() != null) {
            hardware.drive().setPose(standing);
        }
        // Before any override is applied, while the camera is still aimed the way the robot's
        // configuration file just said to aim it: this is the reading "unsaved" is measured
        // against, and reading it afterwards would measure the override against itself.
        SimulatedCamera camera = sceneCameraLocked();
        fileMount = camera == null ? null : CameraMount.of(camera.mount());
        // And the arrangement the robot itself came with, before a scenario is laid over it.
        // Without this, "no scenario" could only be reached by restarting the process: a session
        // that had loaded one had overwritten the camera's scene, and the robot's own was gone.
        // Read here rather than remembered once, because create() reads its files afresh and the
        // official field is not the only thing a robot's scene can be.
        FrameSource ownFrames = cameraFramesLocked();
        robotScene = ownFrames instanceof SceneFrameSource
                ? ((SceneFrameSource) ownFrames).scene()
                : null;
        if (sessionMount != null) {
            aimLocked(sessionMount);
        }
        applyAllianceLocked();
        applySceneLocked();
        buildPhysicsLocked();
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
            // A scene handed straight over is nameless; see activeScenario.
            activeScenario = null;
            applyLoadedSceneLocked(scene);
        }
    }

    @Override
    public void loadScenario(String name) {
        // Read outside the lock: parsing a file is the slow part, and a bad name must fail without
        // having touched the session at all. A misspelling here leaves the field as it was.
        SimulatedScene loaded = name == null ? null : SimConfigFiles.scenario(name).scene();
        synchronized (lock) {
            activeScenario = name;
            applyLoadedSceneLocked(loaded);
        }
    }

    /**
     * What can be loaded and what is loaded, or null for a session with no field to arrange.
     *
     * <p>The directory is listed on every call rather than once at startup, so a scenario file
     * written while the dashboard is running is offered on the next connect. That is the same rule
     * the robot's own configuration follows &mdash; the point of keeping these in files is that
     * editing them changes what happens next.</p>
     */
    @Override
    public ScenariosPayload scenarios() {
        synchronized (lock) {
            if (!(cameraFramesLocked() instanceof SceneFrameSource)) {
                return null;
            }
            return new ScenariosPayload(SimConfigFiles.scenarios(),
                    SimConfigFiles.scenarioDirectory().toString(), activeScenario);
        }
    }

    /** The half both of those share: install an arrangement and tell everyone what changed. */
    private void applyLoadedSceneLocked(SimulatedScene scene) {
        if (!(cameraFramesLocked() instanceof SceneFrameSource)) {
            throw new IllegalStateException("robot \"" + robot.name() + "\" has no"
                    + " scene-backed camera, so it has no field arrangement to change; a"
                    + " scenario needs a webcam built on a SceneFrameSource");
        }
        sessionScene = scene;
        applySceneLocked();
        buildPhysicsLocked();
        publishSceneLocked();
        publishScoreLocked();
    }

    /**
     * Hands the arrangement in force to whatever camera the session now has.
     *
     * <p>{@link #robotScene} when no scenario is loaded, rather than nothing at all: this runs
     * after a rebuild, where leaving the camera alone is right because the fresh robot already
     * carries its own scene &mdash; and after a scenario is unloaded, where it is the only thing
     * that puts the robot's field back.</p>
     */
    private void applySceneLocked() {
        SimulatedScene wanted = sessionScene == null ? robotScene : sessionScene;
        if (wanted == null) {
            return;
        }
        FrameSource frames = cameraFramesLocked();
        if (!(frames instanceof SceneFrameSource)) {
            return;
        }
        DriveModel drive = driveLocked();
        ((SceneFrameSource) frames).setScene(drive == null ? wanted : wanted.on(drive.field()));
    }

    /**
     * Builds the physics world for whatever is now on the field, and attaches it to the hardware
     * map, which is what will step it.
     *
     * <p>Built from the camera's scene rather than from {@link #sessionScene}, so it covers both
     * cases with one path: a session that loaded a scenario, and one running the robot's own idea
     * of the field. A robot whose camera renders no scene has no field contents at all and gets no
     * world.</p>
     *
     * <p>Rebuilt on every init and every stop, because the world holds the drive model whose pose
     * the robot's collision box follows and {@link #restLocked()} replaces that. The visible
     * consequence is that INIT returns the balls to the arrangement, which is also what a driver
     * pressing INIT means by it.</p>
     */
    private void buildPhysicsLocked() {
        if (physics != null) {
            physics.destroy();
            physics = null;
        }
        FrameSource frames = cameraFramesLocked();
        if (!(frames instanceof SceneFrameSource)) {
            return;
        }
        DriveModel drive = driveLocked();
        SimulatedScene scene = ((SceneFrameSource) frames).scene();
        physics = FieldPhysics.of(scene.elements(), scene.structures(),
                drive == null ? FieldConfig.standard() : drive.field(),
                drive == null ? null : drive.robot());
        hardware.setPhysics(physics);
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
            // The rebuild in restLocked() read the configuration files again, so the browser's
            // geometry may be stale even though the robot has not changed identity: a camera moved
            // on the robot, a wider chassis. The same rebuild gives the camera a new scene, which
            // can have moved a HIVE or removed a ball, and either of those changes the score:
            // a tipped HIVE swaps which CELL is the one that counts.
            publishSimConfigLocked();
            publishSceneLocked();
            publishScoreLocked();
            publishCameraMountLocked();
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

    /**
     * The scene-backed camera this session renders through, or null when it has none.
     *
     * <p>Scene-backed specifically, because aiming a camera means replacing the one a
     * {@link SceneFrameSource} draws with. A webcam painting a test pattern has no mount to
     * change.</p>
     */
    private SimulatedCamera sceneCameraLocked() {
        FrameSource frames = cameraFramesLocked();
        return frames instanceof SceneFrameSource ? ((SceneFrameSource) frames).camera() : null;
    }

    @Override
    public CameraMountPayload cameraMount() {
        synchronized (lock) {
            return cameraMountLocked();
        }
    }

    private CameraMountPayload cameraMountLocked() {
        SimulatedCamera camera = sceneCameraLocked();
        if (camera == null || fileMount == null) {
            return null;
        }
        CameraMount showing = sessionMount == null ? fileMount : sessionMount;
        CameraIntrinsics lens = camera.intrinsics();
        return new CameraMountPayload(camera.name(),
                showing.forwardMetres(), showing.leftMetres(), showing.heightMetres(),
                showing.yawDegrees(), showing.pitchDegrees(), showing.rollDegrees(),
                lens.horizontalFieldOfViewDegrees(), lens.verticalFieldOfViewDegrees(),
                sessionMount != null && !sessionMount.equals(fileMount));
    }

    @Override
    public void setCameraMount(double forwardMetres, double leftMetres, double heightMetres,
                               double yawDegrees, double pitchDegrees, double rollDegrees) {
        synchronized (lock) {
            if (sceneCameraLocked() == null) {
                throw new IllegalStateException("robot \"" + robot.name() + "\" has no"
                        + " scene-backed camera, so it has no mount to aim; aiming a camera needs"
                        + " a webcam built on a SceneFrameSource");
            }
            sessionMount = new CameraMount(forwardMetres, leftMetres, heightMetres,
                    yawDegrees, pitchDegrees, rollDegrees);
            aimLocked(sessionMount);
            publishCameraMountLocked();
        }
    }

    @Override
    public Path saveCameraMount() {
        synchronized (lock) {
            Path file = robot.configurationFile();
            if (file == null) {
                throw new IllegalStateException("robot \"" + robot.name() + "\" did not read its"
                        + " numbers from a configuration file, so there is nowhere to save a"
                        + " camera mount to");
            }
            // Nothing held means the file already says what the session is aiming with, so the
            // save is answered with the path and the file is left alone: rewriting it would put
            // a fresh diff in front of someone who changed nothing.
            if (sessionMount != null) {
                RobotConfigFile.writeCameraMount(file, sessionMount);
                // The file now says what the session says, verified by the write itself, so this
                // is the saved mount rather than one more reading of the same numbers.
                fileMount = sessionMount;
                sessionMount = null;
            }
            publishCameraMountLocked();
            return file;
        }
    }

    @Override
    public void revertCameraMount() {
        synchronized (lock) {
            sessionMount = null;
            if (fileMount != null) {
                aimLocked(fileMount);
            }
            publishCameraMountLocked();
        }
    }

    @Override
    public void subscribeCameraMount(Consumer<CameraMountPayload> listener) {
        cameraMountListeners.add(listener);
    }

    /**
     * Points the session's camera at a mount.
     *
     * <p>A new {@link SimulatedCamera} rather than a mutated one, keeping the name the OpMode
     * looks it up under and the calibration the frames are drawn through: those belong to the
     * camera, and only where it is bolted is being changed. {@link SceneFrameSource} reads the
     * camera per frame, so the next frame rendered is taken through this one &mdash; which is the
     * whole point, since the view is what the angle is being judged by.</p>
     */
    private void aimLocked(CameraMount mount) {
        SimulatedCamera camera = sceneCameraLocked();
        if (camera == null) {
            return;
        }
        ((SceneFrameSource) cameraFramesLocked())
                .setCamera(new SimulatedCamera(camera.name(), camera.intrinsics(), mount.pose()));
    }

    private void publishCameraMountLocked() {
        CameraMountPayload mount = cameraMountLocked();
        if (mount == null) {
            return;
        }
        for (Consumer<CameraMountPayload> listener : cameraMountListeners) {
            listener.accept(mount);
        }
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
    public void subscribeBodies(Consumer<BodiesPayload> listener) {
        bodyListeners.add(listener);
    }

    @Override
    public ScorePayload score() {
        synchronized (lock) {
            return scoreLocked();
        }
    }

    @Override
    public void subscribeScore(Consumer<ScorePayload> listener) {
        scoreListeners.add(listener);
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
     * Publishes the score if it is not the one already sent.
     *
     * <p>Called from the rebuild paths as well as from the tick, and the change check is what
     * makes that harmless: INIT puts every ball back where the scenario staged it, which usually
     * means the same score as before a run that scored nothing.</p>
     */
    private void publishScoreLocked() {
        ScorePayload score = changedScoreLocked();
        if (score == null) {
            return;
        }
        for (Consumer<ScorePayload> listener : scoreListeners) {
            listener.accept(score);
        }
    }

    /**
     * The score if it differs from the one the browser already has, and null if it does not.
     *
     * <p>Marks it as sent on the way past, so the tick path and the rebuild paths cannot both
     * announce the same score. Null also covers there being no score at all: whether a session has
     * one is decided by the robot's camera and the field it renders, both settled before any of
     * this runs, so there is no transition from having one to not having one to report.</p>
     */
    private ScorePayload changedScoreLocked() {
        ScorePayload score = scoreLocked();
        if (score == null || same(publishedScore, score)) {
            return null;
        }
        publishedScore = score;
        return score;
    }

    /**
     * Whether two scores say the same thing.
     *
     * <p>Compared field by field rather than by {@code equals}, which a payload does not
     * implement: these are wire shapes, built fresh each time they are asked for, and giving one
     * value semantics purely so a de-duplication check could use it would put the reason for the
     * method a long way from the method. The CELLs are compared in order because the order is
     * fixed &mdash; red's raised CELL, then blue's.</p>
     */
    private static boolean same(ScorePayload published, ScorePayload next) {
        if (published == null) {
            return false;
        }
        if (published.redPoints != next.redPoints || published.bluePoints != next.bluePoints
                || published.cells.size() != next.cells.size()) {
            return false;
        }
        for (int cell = 0; cell < next.cells.size(); cell++) {
            ScorePayload.Cell was = published.cells.get(cell);
            ScorePayload.Cell is = next.cells.get(cell);
            if (!was.cell.equals(is.cell) || was.holding != is.holding) {
                return false;
            }
        }
        return true;
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
                tags.add(tagPayload(tag, cluster.name(), cluster.attachedTo()));
            }
        }

        // The id is the element's index, which is also the id the physics world gives that ball:
        // both lists come from the same arrangement in the same order, and sim/bodies is keyed on
        // it. See BodiesPayload.
        List<ScenePayload.Element> elements = new ArrayList<>(scene.elements().size());
        int id = 0;
        for (GameElement element : scene.elements()) {
            Vec3 centre = element.centre();
            elements.add(new ScenePayload.Element(id++, element.name(),
                    centre.x(), centre.y(), centre.z(), element.radiusMetres(),
                    element.red(), element.green(), element.blue()));
        }
        return new ScenePayload(tags, elements, structuresPayload(scene));
    }

    /**
     * The CELL score for whatever is on the field now, or null when nothing on it can score.
     *
     * <p>Balls come from the physics world rather than from the scene, so what is scored is where
     * the solver has the ball this instant &mdash; the scene's own copy is refreshed a step later
     * and would have the browser see a score for a position it had already been told was
     * stale.</p>
     *
     * <p>The volumes come from the scene, because a CELL swings with the HIVE it is cut in and the
     * scene is what carries that. Which of them score is {@code BioBuzzScore}'s business: it reads
     * the way each mouth faces, which is the only question with an answer while a HIVE is halfway
     * through a tip.</p>
     */
    private ScorePayload scoreLocked() {
        FrameSource frames = cameraFramesLocked();
        if (physics == null || !(frames instanceof SceneFrameSource)) {
            return null;
        }
        List<ScoringVolume> volumes = ((SceneFrameSource) frames).scene().scoringVolumes();
        if (volumes.isEmpty()) {
            // A field with no HIVE on it: a bare tag fixture, or a gym. Reporting 0-0 would put a
            // score on a field that has nowhere to put a ball.
            return null;
        }
        BioBuzzScore score = BioBuzzScore.of(volumes, physics.elements(),
                PivotState.swingsOf(physics.pivots()));
        List<ScorePayload.Cell> cells = new ArrayList<>(score.cells().size());
        for (BioBuzzScore.Scored cell : score.cells()) {
            cells.add(new ScorePayload.Cell(cell.cellName(),
                    cell.isRed() ? Alliance.RED : Alliance.BLUE,
                    cell.holding().size(), cell.points()));
        }
        return new ScorePayload(score.redPoints(), score.bluePoints(), cells,
                score.redTips(), score.blueTips());
    }

    /**
     * The field's furniture, as the primitives it is described by.
     *
     * <p>Only what each structure <em>draws</em>. Its collision geometry is deliberately a
     * different shape &mdash; a FLOWER's rim is one open ring drawn and a dozen little boxes
     * collided with &mdash; and publishing the colliders would have the browser drawing twelve
     * boxes for a tube it can draw as one cylinder.</p>
     */
    private static List<ScenePayload.Structure> structuresPayload(SimulatedScene scene) {
        List<ScenePayload.Structure> published = new ArrayList<>(scene.structures().size());
        for (Structure structure : scene.structures()) {
            List<ScenePayload.Solid> solids = new ArrayList<>(structure.drawn().size());
            for (Solid solid : structure.drawn()) {
                solids.add(solidPayload(solid));
            }
            published.add(new ScenePayload.Structure(structure.name(), solids,
                    pivotPayload(structure.pivot())));
        }
        return published;
    }

    /**
     * A structure's axis of rotation, or null for one bolted down.
     *
     * <p>The angle sent here is the angle the solids beside it are drawn at, which is what makes
     * {@code sim/bodies} interpretable: the browser turns a structure by the difference between
     * the two. Publishing the geometry without it would leave a HIVE staged tipped back looking
     * upright until the first time it moved.</p>
     */
    private static ScenePayload.Pivot pivotPayload(Pivot pivot) {
        if (pivot == null) {
            return null;
        }
        return new ScenePayload.Pivot(
                pivot.point().x(), pivot.point().y(), pivot.point().z(),
                pivot.axis().x(), pivot.axis().y(), pivot.axis().z(),
                pivot.angleRadians());
    }

    private static ScenePayload.Solid solidPayload(Solid solid) {
        Pose3d pose = solid.pose();
        Vec3 at = pose.position();
        return new ScenePayload.Solid(
                solid.shape() == Solid.Shape.BOX ? "box" : "cylinder",
                at.x(), at.y(), at.z(),
                Math.toDegrees(pose.yaw()), Math.toDegrees(pose.pitch()),
                Math.toDegrees(pose.roll()),
                solid.lengthX(), solid.lengthY(), solid.lengthZ(),
                solid.radiusMetres(), solid.lengthMetres(),
                solid.red(), solid.green(), solid.blue());
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
    private static ScenePayload.Tag tagPayload(FieldTag tag, String cluster, String attachedTo) {
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
                cellsOf(tag.id()), attachedTo);
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
            if (published.bodies != null) {
                publish("bodies", bodyListeners, published.bodies);
            }
            if (published.score != null) {
                publish("score", scoreListeners, published.score);
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
                ++tickCount % TICKS_PER_DEVICE_BROADCAST == 0 ? snapshotLocked() : null,
                bodiesLocked(),
                tickScoreLocked());
    }

    /**
     * The score, on the ticks where it changed, and null on the ones where it cannot have.
     *
     * <p>Gated on the same {@code moving()} the bodies are, and for a stronger reason than
     * traffic: a ball's two points are decided by which side of a CELL's mouth it is on, and
     * nothing can cross that boundary without moving. So a field at rest is not merely
     * uninteresting to republish, it is provably unchanged, and this skips a containment test
     * against every ball on the field fifty times a second to prove it.</p>
     */
    private ScorePayload tickScoreLocked() {
        if (physics == null || !physics.moving()) {
            return null;
        }
        return changedScoreLocked();
    }

    /**
     * Where the balls are, on the ticks where that has changed.
     *
     * <p>Null while the field is at rest, which is most of the time: a scenario nobody has driven
     * into publishes its arrangement once in {@code sim/scene} and then says nothing more, so an
     * idle session costs no traffic and a socket log shows a rolling ball instead of burying it.</p>
     */
    private BodiesPayload bodiesLocked() {
        if (physics == null || !physics.moving()) {
            return null;
        }
        List<BodyState> moving = physics.bodies();
        List<BodiesPayload.Body> bodies = new ArrayList<>(moving.size());
        for (BodyState body : moving) {
            bodies.add(new BodiesPayload.Body(body.id(),
                    body.x(), body.y(), body.z(),
                    body.quaternionX(), body.quaternionY(), body.quaternionZ(),
                    body.quaternionW()));
        }
        List<BodiesPayload.Tip> tips = new ArrayList<>(2);
        for (PivotState pivot : physics.pivots()) {
            tips.add(new BodiesPayload.Tip(pivot.structureName(), pivot.angleRadians()));
        }
        return new BodiesPayload(time.nowMillis(), hardware.elapsedSeconds(), bodies, tips);
    }

    /**
     * What one tick has to say: a pose on every tick, a device snapshot on every fifth, and the
     * bodies and the score on the ticks where those changed.
     */
    private static final class Publication {

        final SimPose pose;
        final List<DeviceState> devices;
        final BodiesPayload bodies;
        final ScorePayload score;

        Publication(SimPose pose, List<DeviceState> devices, BodiesPayload bodies,
                    ScorePayload score) {
            this.pose = pose;
            this.devices = devices;
            this.bodies = bodies;
            this.score = score;
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
