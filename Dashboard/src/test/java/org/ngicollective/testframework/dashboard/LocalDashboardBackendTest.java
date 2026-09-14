package org.ngicollective.testframework.dashboard;

import com.qualcomm.robotcore.eventloop.opmode.LinearOpMode;
import com.qualcomm.robotcore.eventloop.opmode.OpMode;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.ngicollective.testframework.dashboard.protocol.BehaviorSpec;
import org.ngicollective.testframework.dashboard.protocol.DeviceState;
import org.ngicollective.testframework.dashboard.protocol.GamepadState;
import org.ngicollective.testframework.dashboard.protocol.OpModeInfo;
import org.ngicollective.testframework.dashboard.protocol.OpModeStatus;
import org.ngicollective.testframework.dashboard.protocol.TelemetryFrame;
import org.ngicollective.testframework.hardware.FakeHardwareMap;
import org.ngicollective.testframework.hardware.SimulatedRobot;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The backend contract the wire protocol rests on, exercised without a socket: everything here is
 * what a {@code DashboardServer} would call in response to a browser.
 *
 * <p>The session runs on {@link ManualTicks}, so a control cycle happens when this test says so
 * and has finished by the time {@code pump()} returns. Anything a {@code LinearOpMode} has to do
 * on its own thread still has to be waited for, and {@code pumpUntil} is that wait &mdash; bounded
 * by cycles rather than by a clock.</p>
 */
class LocalDashboardBackendTest {

    private static final String CLASS_NAME = TickingTeleOp.class.getName();

    /** What a broken OpMode says on its way out, so the assertion can find it in a status. */
    private static final String FAILURE_REASON = "encoder cable unplugged";

    private final List<TelemetryFrame> telemetry = new CopyOnWriteArrayList<>();
    private final List<List<DeviceState>> deviceSnapshots = new CopyOnWriteArrayList<>();
    private final List<OpModeStatus> statuses = new CopyOnWriteArrayList<>();

    private ManualTicks ticks;
    private LocalDashboardBackend backend;

    private static final SimulatedRobot ROBOT = new SimulatedRobot() {
        @Override
        public String name() {
            return "TestBot";
        }

        @Override
        public FakeHardwareMap create() {
            FakeHardwareMap hardware =
                    FakeHardwareMap.builder().addMotor("drive").addImu("imu").build();
            hardware.motor("drive").state().setMaxTicksPerSecond(1000.0);
            return hardware;
        }
    };

    @BeforeEach
    void setUp() {
        openSession(ROBOT, entry("Ticking TeleOp", TickingTeleOp.class, TickingTeleOp::new));
    }

    @AfterEach
    void tearDown() {
        backend.close();
    }

    @Test
    void listsTheOpModesItWasGiven() {
        List<OpModeInfo> opModes = backend.listOpModes();

        assertEquals(1, opModes.size());
        assertEquals("Ticking TeleOp", opModes.get(0).name);
        assertEquals(CLASS_NAME, opModes.get(0).className);
    }

    @Test
    void selectingAnUnknownOpModeIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> backend.initOpMode("com.example.Nope"));
    }

    @Test
    void startingBeforeInitIsRejectedRatherThanSilentlyIgnored() {
        assertThrows(IllegalStateException.class, backend::start);
    }

    @Test
    void runsTheFullLifecycleAndPublishesEachTransition() {
        backend.initOpMode(CLASS_NAME);
        assertEquals(OpModeStatus.State.INIT, backend.status().state);
        assertEquals("Ticking TeleOp", backend.status().opMode);

        backend.start();
        assertEquals(OpModeStatus.State.RUNNING, backend.status().state);

        backend.stop();
        assertEquals(OpModeStatus.State.STOPPED, backend.status().state);
        assertNull(backend.status().failure);

        // In order, and only these: a browser drives its buttons off this stream, so a STOPPED
        // arriving before the RUNNING it belongs after would leave a running robot looking stopped.
        assertEquals(
                Arrays.asList(
                        OpModeStatus.State.INIT,
                        OpModeStatus.State.RUNNING,
                        OpModeStatus.State.STOPPED),
                states(),
                "subscribers saw " + states());
    }

    @Test
    void streamsTelemetryAndDeviceStateWhileRunning() {
        backend.initOpMode(CLASS_NAME);

        ticks.pumpUntil(() -> !telemetry.isEmpty(), "no telemetry arrived");
        assertTrue(telemetry.get(0).lines.contains("Status : Initialized"),
                "first frame was " + telemetry.get(0).lines);

        ticks.pumpUntil(() -> !deviceSnapshots.isEmpty(), "no device snapshot arrived");
        List<DeviceState> snapshot = deviceSnapshots.get(deviceSnapshots.size() - 1);
        assertEquals(2, snapshot.size());
        assertNotNull(find(snapshot, "drive"));
        assertEquals("imu", find(snapshot, "imu").kind);
    }

    /**
     * A {@code LinearOpMode} loop calls {@code telemetry.update()} as fast as its thread spins;
     * against simulated hardware that is tens of thousands of times a second. What reaches a
     * subscriber &mdash; and from there a socket and a browser &mdash; has to be a display rate
     * instead, or the browser drowns and every other message queues up behind the backlog.
     *
     * <p>Counted in control cycles rather than measured in frames per second. The backend's
     * promise is one frame per cycle at most, whatever the OpMode does in between, and a cycle is
     * something this test performs rather than something it waits for.</p>
     */
    @Test
    void publishesTelemetryAtADisplayRateRatherThanTheOpModeLoopRate() {
        backend.initOpMode(CLASS_NAME);
        backend.start();
        ticks.pumpUntil(() -> !telemetry.isEmpty(), "no telemetry arrived");

        int cycles = 25;
        int before = telemetry.size();
        ticks.pump(cycles);
        int published = telemetry.size() - before;

        assertTrue(published <= cycles, "the socket saw " + published + " frames from " + cycles
                + " control cycles; the OpMode's loop composed thousands in that time and the "
                + "backend publishes at most one per cycle");
        int after = telemetry.size();
        ticks.pumpUntil(() -> telemetry.size() > after,
                "telemetry stopped flowing entirely after " + after + " frames");
    }

    @Test
    void gamepadInputReachesTheRunningOpMode() {
        backend.initOpMode(CLASS_NAME);
        backend.start();

        GamepadState input = new GamepadState();
        input.left_stick_y = 1.0f;
        backend.injectGamepadState(1, input);

        ticks.pumpUntil(
                () -> latestDevice("drive") != null && latestDevice("drive").commandedPower == 1.0,
                "the OpMode never picked up the gamepad input");
        ticks.pumpUntil(() -> latestDevice("drive").position > 0,
                "the simulated motor never turned");
    }

    @Test
    void stallingAMotorStopsItWhileTheOpModeKeepsCommandingIt() {
        backend.initOpMode(CLASS_NAME);
        backend.start();
        GamepadState input = new GamepadState();
        input.left_stick_y = 1.0f;
        backend.injectGamepadState(1, input);
        ticks.pumpUntil(() -> latestDevice("drive") != null && latestDevice("drive").position > 0,
                "the motor never started");

        backend.overrideDeviceBehavior("drive", new BehaviorSpec("stalled", 0.0));

        ticks.pumpUntil(() -> hasBehavior("drive", "stalled"), "the override never applied");
        int stalledAt = latestDevice("drive").position;
        ticks.pump(10);
        assertEquals(stalledAt, latestDevice("drive").position, "a stalled motor must not turn");
        assertEquals(1.0, latestDevice("drive").commandedPower, 1e-9,
                "the OpMode is still commanding full power; only the hardware has failed");

        backend.resetDeviceBehavior("drive");

        ticks.pumpUntil(() -> latestDevice("drive").position > stalledAt,
                "reset did not restore the motor");
        assertEquals("default", latestDevice("drive").behavior);
    }

    @Test
    void overridingWithABehaviorTheFrameworkDoesNotHaveIsRejected() {
        backend.initOpMode(CLASS_NAME);

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> backend.overrideDeviceBehavior("drive", new BehaviorSpec("teleport", 1.0)));
        assertTrue(thrown.getMessage().contains("teleport"), thrown.getMessage());
    }

    @Test
    void overridingAnUnknownDeviceIsRejected() {
        backend.initOpMode(CLASS_NAME);

        assertThrows(IllegalArgumentException.class,
                () -> backend.resetDeviceBehavior("nonexistent"));
    }

    @Test
    void eachRunGetsAFreshRobotSoFaultsDoNotLeakBetweenSessions() {
        backend.initOpMode(CLASS_NAME);
        backend.overrideDeviceBehavior("drive", new BehaviorSpec("stalled", 0.0));
        ticks.pumpUntil(() -> hasBehavior("drive", "stalled"), "the override never applied");

        deviceSnapshots.clear();
        backend.initOpMode(CLASS_NAME);

        ticks.pumpUntil(() -> latestDevice("drive") != null, "no snapshot from the new session");
        assertEquals("default", latestDevice("drive").behavior);
        assertEquals(0, latestDevice("drive").position);
    }

    /**
     * An iterative OpMode has two loops and the driver station decides which one is running. Get it
     * wrong and either the robot drives during init &mdash; illegal, and dangerous on a real field
     * &mdash; or PLAY does nothing at all.
     */
    @Test
    void runsTheInitLoopWhileInitialisedAndTheMainLoopOnceStarted() {
        openSession(new DrivingRobot(),
                entry("Mecanum Iterative TeleOp", MecanumIterativeTeleOp.class,
                        MecanumIterativeTeleOp::new));

        backend.initOpMode(MecanumIterativeTeleOp.class.getName());
        ticks.pump(3);

        assertTrue(lastTelemetry().contains("Phase : init_loop"),
                "an initialised session published " + lastTelemetry());
        for (TelemetryFrame frame : telemetry) {
            assertFalse(frame.lines.contains("Phase : loop"),
                    "loop() ran before PLAY: " + frame.lines);
        }

        backend.start();
        ticks.pump();

        assertTrue(lastTelemetry().contains("Phase : loop"),
                "a started session published " + lastTelemetry());
    }

    /**
     * An autonomous that has driven its path returns from {@code runOpMode()} with nobody pressing
     * anything. The browser only learns about that from the status stream, so the tick has to
     * notice.
     */
    @Test
    void aLinearOpModeThatFinishesByItselfEndsTheSessionWithNoFailure() {
        openSession(ROBOT, entry("Self Finishing", SelfFinishingOpMode.class,
                SelfFinishingOpMode::new));

        backend.initOpMode(SelfFinishingOpMode.class.getName());
        backend.start();
        ticks.pumpUntil(() -> backend.status().state == OpModeStatus.State.STOPPED,
                "the session never noticed the OpMode had returned");

        assertNull(backend.status().failure, "finishing normally is not a failure");
        assertNull(backend.status().opMode, "a stopped session is running nothing");
        assertEquals(
                Arrays.asList(
                        OpModeStatus.State.INIT,
                        OpModeStatus.State.RUNNING,
                        OpModeStatus.State.STOPPED),
                states(),
                "subscribers saw " + states());
    }

    /**
     * The reason a run died is the whole message. Without it the browser shows a stopped run and no
     * explanation, and the person watching goes looking through a console they may not have.
     */
    @Test
    void aLinearOpModeThatThrowsReportsWhatItDiedOf() {
        openSession(ROBOT, entry("Throwing TeleOp", ThrowingOpMode.class, ThrowingOpMode::new));

        backend.initOpMode(ThrowingOpMode.class.getName());
        backend.start();
        ticks.pumpUntil(() -> backend.status().state == OpModeStatus.State.STOPPED,
                "the session never noticed the OpMode had thrown");

        String failure = backend.status().failure;
        assertNotNull(failure, "a run that threw reported no reason at all");
        assertTrue(failure.contains(FAILURE_REASON), "the reason reported was \"" + failure + "\"");
        assertTrue(failure.contains("IllegalStateException"),
                "the reason reported was \"" + failure + "\"");
    }

    /**
     * An iterative OpMode throws on the ticker's own thread, so a swallowed exception here takes
     * the ticker with it and the whole dashboard goes silent rather than one run.
     */
    @Test
    void anIterativeOpModeThatThrowsEndsTheRunAndLeavesTheSessionTicking() {
        openSession(ROBOT, entry("Throwing Iterative", ThrowingIterativeOpMode.class,
                ThrowingIterativeOpMode::new));

        backend.initOpMode(ThrowingIterativeOpMode.class.getName());
        backend.start();
        ticks.pump();

        assertEquals(OpModeStatus.State.STOPPED, backend.status().state);
        assertTrue(backend.status().failure.contains(FAILURE_REASON),
                "the reason reported was \"" + backend.status().failure + "\"");

        // The session survived the run: it still has a robot, and it is still publishing it.
        deviceSnapshots.clear();
        ticks.pumpUntil(() -> !deviceSnapshots.isEmpty(),
                "the session stopped publishing after an OpMode threw");
    }

    @Test
    void aListenerThatThrowsCostsItsOwnFrameAndNothingElse() {
        // The regression this defends shipped and was found by hand: a browser's send threw once,
        // the exception left the control cycle, and scheduleAtFixedRate answered by cancelling
        // every future cycle without a word. The socket stayed up and kept listing OpModes,
        // accepting a start and reading the gamepad, while the robot never moved again and the 3D
        // view never learned it had wheels, because pose and device state had both stopped.
        List<List<DeviceState>> behindTheFault = new CopyOnWriteArrayList<>();
        backend.subscribeDeviceState(devices -> {
            throw new IllegalStateException("send to a closing connection");
        });
        backend.subscribeDeviceState(behindTheFault::add);

        int snapshotsBefore = deviceSnapshots.size();
        ticks.pump(15);

        assertTrue(behindTheFault.size() >= 2,
                "a listener behind the broken one saw " + behindTheFault.size() + " snapshots");
        assertTrue(deviceSnapshots.size() > snapshotsBefore,
                "the session stopped publishing after one listener threw");
    }

    @Test
    void aFaultOnThePublishPathLeavesTheSessionDrivable() {
        backend.subscribeDeviceState(devices -> {
            throw new IllegalStateException("send to a closing connection");
        });
        backend.initOpMode(CLASS_NAME);
        backend.start();
        GamepadState input = new GamepadState();
        input.left_stick_y = 1.0f;
        backend.injectGamepadState(1, input);
        ticks.pumpUntil(() -> {
            DeviceState drive = latestDevice("drive");
            return drive != null && drive.velocityTicksPerSecond != 0.0;
        }, "the drive motor never turned, so the session stopped ticking after the fault");

        assertEquals(OpModeStatus.State.RUNNING, backend.status().state,
                "a broken listener is not the OpMode's fault and must not end its run");
    }

    /** Runs to its own end the way an autonomous does, without anybody pressing stop. */
    static class SelfFinishingOpMode extends LinearOpMode {

        @Override
        public void runOpMode() {
            waitForStart();
            telemetry.addData("Status", "Path complete");
            telemetry.update();
        }
    }

    /** Fails where real OpModes fail: after PLAY, in the middle of a run. */
    static class ThrowingOpMode extends LinearOpMode {

        @Override
        public void runOpMode() {
            waitForStart();
            throw new IllegalStateException(FAILURE_REASON);
        }
    }

    /** The same failure on the iterative path, which happens on the ticker's thread. */
    static class ThrowingIterativeOpMode extends OpMode {

        @Override
        public void init() {
        }

        @Override
        public void loop() {
            throw new IllegalStateException(FAILURE_REASON);
        }
    }

    /**
     * Replaces the session under test with one running {@code entry} on {@code robot}, on a fresh
     * tick source and with the streams a browser would be reading emptied.
     */
    private void openSession(SimulatedRobot robot, OpModeEntry entry) {
        if (backend != null) {
            backend.close();
        }
        telemetry.clear();
        deviceSnapshots.clear();
        statuses.clear();

        ticks = new ManualTicks();
        backend = new LocalDashboardBackend(robot, Collections.singletonList(entry), ticks);
        backend.subscribeTelemetry(telemetry::add);
        backend.subscribeDeviceState(deviceSnapshots::add);
        backend.subscribeStatus(statuses::add);
    }

    private static OpModeEntry entry(String name, Class<? extends OpMode> type,
                                     Supplier<OpMode> factory) {
        return new OpModeEntry(new OpModeInfo(name, "", "TeleOp", type.getName()), factory);
    }

    private List<OpModeStatus.State> states() {
        List<OpModeStatus.State> seen = new ArrayList<>();
        for (OpModeStatus status : statuses) {
            seen.add(status.state);
        }
        return seen;
    }

    private List<String> lastTelemetry() {
        assertFalse(telemetry.isEmpty(), "no telemetry has been published at all");
        return telemetry.get(telemetry.size() - 1).lines;
    }

    /** Null-safe: the ticker may not have published a snapshot yet when a predicate first runs. */
    private boolean hasBehavior(String device, String behavior) {
        DeviceState state = latestDevice(device);
        return state != null && behavior.equals(state.behavior);
    }

    private DeviceState latestDevice(String name) {
        if (deviceSnapshots.isEmpty()) {
            return null;
        }
        return find(deviceSnapshots.get(deviceSnapshots.size() - 1), name);
    }

    private static DeviceState find(List<DeviceState> snapshot, String name) {
        for (DeviceState state : snapshot) {
            if (state.name.equals(name)) {
                return state;
            }
        }
        return null;
    }
}
