package org.ngicollective.testframework.dashboard;

import com.qualcomm.robotcore.eventloop.opmode.LinearOpMode;
import com.qualcomm.robotcore.eventloop.opmode.OpMode;
import com.qualcomm.robotcore.hardware.Gamepad;

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
import org.ngicollective.testframework.hardware.FakeDcMotorEx;
import org.ngicollective.testframework.hardware.FakeDevice;
import org.ngicollective.testframework.hardware.FakeHardwareMap;
import org.ngicollective.testframework.hardware.FakeImu;
import org.ngicollective.testframework.hardware.FakeServo;
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
 */
public class LocalDashboardBackend implements DashboardBackend {

    /** Control-cycle period. Matches the ~50 Hz the real Robot Controller event loop runs at. */
    private static final long TICK_MILLIS = 20;

    /** Device snapshots are for human eyes; every tick would be needless traffic. */
    private static final int TICKS_PER_DEVICE_BROADCAST = 5;

    /**
     * How often a session's OpMode may transmit telemetry.
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

    private final Map<String, OpModeEntry> opModes = new LinkedHashMap<>();
    private final SimulatedRobot robot;
    private final ScheduledExecutorService ticker =
            Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "dashboard-tick");
                thread.setDaemon(true);
                return thread;
            });

    private final List<Consumer<OpModeStatus>> statusListeners = new CopyOnWriteArrayList<>();
    private final List<Consumer<TelemetryFrame>> telemetryListeners = new CopyOnWriteArrayList<>();
    private final List<Consumer<List<DeviceState>>> deviceListeners = new CopyOnWriteArrayList<>();
    private final List<Consumer<SimPose>> simPoseListeners = new CopyOnWriteArrayList<>();
    private final List<Consumer<SimConfigPayload>> simConfigListeners =
            new CopyOnWriteArrayList<>();
    private final List<Consumer<SimStatus>> simStatusListeners = new CopyOnWriteArrayList<>();

    private final Object lock = new Object();
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
     * The newest telemetry frame not yet broadcast. Only the latest matters &mdash; the UI shows one
     * composition &mdash; and draining it on the tick keeps outbound traffic bounded by the tick
     * rate no matter what the OpMode does with its transmission interval.
     */
    private final AtomicReference<TelemetryFrame> pendingTelemetry = new AtomicReference<>();

    public LocalDashboardBackend(SimulatedRobot robot, List<OpModeEntry> opModes) {
        this.robot = robot;
        for (OpModeEntry entry : opModes) {
            this.opModes.put(entry.info.className, entry);
        }
        ticker.scheduleAtFixedRate(this::tick, TICK_MILLIS, TICK_MILLIS, TimeUnit.MILLISECONDS);
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

            // A fresh robot every run: a stalled motor or a spun IMU from the last session must not
            // silently carry over into this one.
            hardware = robot.create();
            // Before init runs, because an init that calls resetYaw() has to be looking at the same
            // reference heading the driver behind that alliance wall is.
            applyAllianceLocked();
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
            // though the robot has not changed identity.
            publishSimConfigLocked();
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
        ticker.shutdownNow();
    }

    private void wireTelemetry(OpModeHarness target) {
        target.setTelemetryTransmissionInterval(TELEMETRY_INTERVAL_MILLIS);
        // Runs on the OpMode's own thread: keep the newest frame and let the ticker publish it.
        pendingTelemetry.set(null);
        target.addTelemetryListener(
                lines -> pendingTelemetry.set(new TelemetryFrame(System.currentTimeMillis(), lines)));
    }

    private FakeDevice<?> requireDeviceLocked(String device) {
        FakeDevice<?> target = hardware == null ? null : hardware.devices().get(device);
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
        hardware = null;
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
     * The running session's drive model, or null &mdash; either nothing is initialized, or this
     * robot's configuration declares no drivetrain, in which case there is nothing to have a pose.
     */
    private DriveModel driveLocked() {
        return hardware == null ? null : hardware.drive();
    }

    private DriveModel requireDriveLocked(String action) {
        DriveModel drive = driveLocked();
        if (drive == null) {
            throw new IllegalStateException("cannot " + action + ": "
                    + (hardware == null
                            ? "no OpMode is initialized"
                            : robot.name() + " declares no drivetrain"));
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
     * One control cycle: advance simulated time, do the Robot Controller event loop's per-iteration
     * housekeeping, run an iterative OpMode's loop, notice a linear OpMode that has ended on its
     * own, and publish whatever the browser needs to see.
     */
    private void tick() {
        Publication published = tickSession();

        // Outside the lock, and once per tick at most: this is the only place telemetry reaches the
        // browser, which is what keeps a fast OpMode loop from flooding the socket.
        TelemetryFrame frame = pendingTelemetry.getAndSet(null);
        if (frame != null) {
            for (Consumer<TelemetryFrame> listener : telemetryListeners) {
                listener.accept(frame);
            }
        }

        if (published.pose != null) {
            for (Consumer<SimPose> listener : simPoseListeners) {
                listener.accept(published.pose);
            }
        }

        if (published.devices != null) {
            for (Consumer<List<DeviceState>> listener : deviceListeners) {
                listener.accept(published.devices);
            }
        }
    }

    /** The locked half of a control cycle, and everything it leaves for the browser. */
    private Publication tickSession() {
        synchronized (lock) {
            if (harness == null) {
                return Publication.NOTHING;
            }

            // A pause withholds simulated time instead of stopping the ticker, so the session stays
            // answerable: pose and device state keep flowing, an override still lands, and a queued
            // step lets exactly one cycle through.
            boolean advancing = !paused;
            if (paused && pendingSteps > 0) {
                pendingSteps--;
                advancing = true;
            }

            try {
                if (advancing) {
                    harness.advance(TICK_MILLIS / 1000.0 * multiplier);
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
                        harness = null;
                        running = null;
                        hardware = null;
                        setStatusLocked(new OpModeStatus(
                                null, OpModeStatus.State.STOPPED,
                                failure == null ? null : describe(failure)));
                        return Publication.NOTHING;
                    }
                }
            } catch (RuntimeException | Error e) {
                // An OpMode that throws mid-loop ends the session rather than the ticker thread.
                harness = null;
                running = null;
                hardware = null;
                setStatusLocked(new OpModeStatus(null, OpModeStatus.State.STOPPED, describe(e)));
                return Publication.NOTHING;
            }

            return new Publication(
                    poseLocked(),
                    ++tickCount % TICKS_PER_DEVICE_BROADCAST == 0 ? snapshotLocked() : null);
        }
    }

    /** What one tick has to say: a pose on every tick, a device snapshot on every fifth. */
    private static final class Publication {

        static final Publication NOTHING = new Publication(null, null);

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
                System.currentTimeMillis(),
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
        if (hardware == null) {
            return states;
        }
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
