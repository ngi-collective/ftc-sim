package org.ngicollective.testframework.dashboard;

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
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The backend contract the wire protocol rests on, exercised without a socket: everything here is
 * what a {@code DashboardServer} would call in response to a browser.
 */
class LocalDashboardBackendTest {

    private static final String CLASS_NAME = TickingTeleOp.class.getName();

    private final List<TelemetryFrame> telemetry = new CopyOnWriteArrayList<>();
    private final List<List<DeviceState>> deviceSnapshots = new CopyOnWriteArrayList<>();
    private final List<OpModeStatus> statuses = new CopyOnWriteArrayList<>();

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
        Supplier<com.qualcomm.robotcore.eventloop.opmode.OpMode> factory = TickingTeleOp::new;
        OpModeInfo info = new OpModeInfo("Ticking TeleOp", "", "TeleOp", CLASS_NAME);
        backend = new LocalDashboardBackend(ROBOT, Collections.singletonList(
                new OpModeEntry(info, factory)));
        backend.subscribeTelemetry(telemetry::add);
        backend.subscribeDeviceState(deviceSnapshots::add);
        backend.subscribeStatus(statuses::add);
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
        assertEquals(null, backend.status().failure);

        List<OpModeStatus.State> seen = new ArrayList<>();
        for (OpModeStatus status : statuses) {
            seen.add(status.state);
        }
        assertTrue(seen.containsAll(java.util.Arrays.asList(
                        OpModeStatus.State.INIT,
                        OpModeStatus.State.RUNNING,
                        OpModeStatus.State.STOPPED)),
                "subscribers saw " + seen);
    }

    @Test
    void streamsTelemetryAndDeviceStateWhileRunning() {
        backend.initOpMode(CLASS_NAME);

        await(() -> !telemetry.isEmpty(), "no telemetry arrived");
        assertTrue(telemetry.get(0).lines.contains("Status : Initialized"),
                "first frame was " + telemetry.get(0).lines);

        await(() -> !deviceSnapshots.isEmpty(), "no device snapshot arrived");
        List<DeviceState> snapshot = deviceSnapshots.get(deviceSnapshots.size() - 1);
        assertEquals(2, snapshot.size());
        assertNotNull(find(snapshot, "drive"));
        assertEquals("imu", find(snapshot, "imu").kind);
    }

    @Test
    void gamepadInputReachesTheRunningOpMode() {
        backend.initOpMode(CLASS_NAME);
        backend.start();

        GamepadState input = new GamepadState();
        input.left_stick_y = 1.0f;
        backend.injectGamepadState(1, input);

        await(() -> latestDevice("drive") != null && latestDevice("drive").commandedPower == 1.0,
                "the OpMode never picked up the gamepad input");
        await(() -> latestDevice("drive").position > 0, "the simulated motor never turned");
    }

    @Test
    void stallingAMotorStopsItWhileTheOpModeKeepsCommandingIt() {
        backend.initOpMode(CLASS_NAME);
        backend.start();
        GamepadState input = new GamepadState();
        input.left_stick_y = 1.0f;
        backend.injectGamepadState(1, input);
        await(() -> latestDevice("drive") != null && latestDevice("drive").position > 0,
                "the motor never started");

        backend.overrideDeviceBehavior("drive", new BehaviorSpec("stalled", 0.0));

        await(() -> hasBehavior("drive", "stalled"), "the override never applied");
        int stalledAt = latestDevice("drive").position;
        settle();
        assertEquals(stalledAt, latestDevice("drive").position, "a stalled motor must not turn");
        assertEquals(1.0, latestDevice("drive").commandedPower, 1e-9,
                "the OpMode is still commanding full power; only the hardware has failed");

        backend.resetDeviceBehavior("drive");

        await(() -> latestDevice("drive").position > stalledAt, "reset did not restore the motor");
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
        await(() -> hasBehavior("drive", "stalled"), "the override never applied");

        deviceSnapshots.clear();
        backend.initOpMode(CLASS_NAME);

        await(() -> latestDevice("drive") != null, "no snapshot from the new session");
        assertEquals("default", latestDevice("drive").behavior);
        assertEquals(0, latestDevice("drive").position);
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

    /** Lets several backend ticks pass, so "did not change" assertions mean something. */
    private static void settle() {
        try {
            Thread.sleep(200);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void await(BooleanSupplier condition, String message) {
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.yield();
        }
        fail(message);
    }
}
