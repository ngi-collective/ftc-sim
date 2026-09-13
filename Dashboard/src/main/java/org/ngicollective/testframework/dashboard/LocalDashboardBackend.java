package org.ngicollective.testframework.dashboard;

import com.qualcomm.robotcore.eventloop.opmode.LinearOpMode;
import com.qualcomm.robotcore.eventloop.opmode.OpMode;
import com.qualcomm.robotcore.hardware.Gamepad;

import org.ngicollective.testframework.dashboard.protocol.BehaviorSpec;
import org.ngicollective.testframework.dashboard.protocol.DeviceState;
import org.ngicollective.testframework.dashboard.protocol.GamepadState;
import org.ngicollective.testframework.dashboard.protocol.OpModeInfo;
import org.ngicollective.testframework.dashboard.protocol.OpModeStatus;
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
 * Controller's event loop &mdash; it advances simulated time in step with the wall clock and, for an
 * iterative OpMode, calls {@code init_loop()}/{@code loop()}. A linear OpMode runs on its own thread
 * exactly as it does under a unit test.</p>
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

    private final Object lock = new Object();
    private FakeHardwareMap hardware;
    private OpModeHarness harness;
    private OpModeEntry running;
    private OpModeStatus status = OpModeStatus.stopped();
    private int tickCount;

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

            // A fresh robot every run: a stalled motor or a spun IMU from the last session must not
            // silently carry over into this one.
            hardware = robot.create();
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

    /**
     * One control cycle: advance simulated time, do the Robot Controller event loop's per-iteration
     * housekeeping, run an iterative OpMode's loop, notice a linear OpMode that has ended on its
     * own, and publish whatever the browser needs to see.
     */
    private void tick() {
        List<DeviceState> snapshot = tickSession();

        // Outside the lock, and once per tick at most: this is the only place telemetry reaches the
        // browser, which is what keeps a fast OpMode loop from flooding the socket.
        TelemetryFrame frame = pendingTelemetry.getAndSet(null);
        if (frame != null) {
            for (Consumer<TelemetryFrame> listener : telemetryListeners) {
                listener.accept(frame);
            }
        }

        if (snapshot != null) {
            for (Consumer<List<DeviceState>> listener : deviceListeners) {
                listener.accept(snapshot);
            }
        }
    }

    /** The locked half of a control cycle. Returns a device snapshot on the ticks that publish one. */
    private List<DeviceState> tickSession() {
        synchronized (lock) {
            if (harness == null) {
                return null;
            }
            try {
                harness.advance(TICK_MILLIS / 1000.0);
                harness.eventLoopIteration();
                if (harness instanceof IterativeOpModeHarness) {
                    IterativeOpModeHarness iterative = (IterativeOpModeHarness) harness;
                    if (status.state == OpModeStatus.State.RUNNING) {
                        iterative.loop();
                    } else {
                        iterative.initLoop();
                    }
                } else {
                    LinearOpModeHarness linear = (LinearOpModeHarness) harness;
                    if (linear.isFinished()) {
                        Throwable failure = linear.failure();
                        harness = null;
                        running = null;
                        hardware = null;
                        setStatusLocked(new OpModeStatus(
                                null, OpModeStatus.State.STOPPED,
                                failure == null ? null : describe(failure)));
                        return null;
                    }
                }
            } catch (RuntimeException | Error e) {
                // An OpMode that throws mid-loop ends the session rather than the ticker thread.
                harness = null;
                running = null;
                hardware = null;
                setStatusLocked(new OpModeStatus(null, OpModeStatus.State.STOPPED, describe(e)));
                return null;
            }

            return ++tickCount % TICKS_PER_DEVICE_BROADCAST == 0 ? snapshotLocked() : null;
        }
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
