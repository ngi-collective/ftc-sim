package org.ngicollective.testframework.dashboard;

import org.ngicollective.testframework.dashboard.protocol.BehaviorSpec;
import org.ngicollective.testframework.dashboard.protocol.DeviceState;
import org.ngicollective.testframework.dashboard.protocol.GamepadState;
import org.ngicollective.testframework.dashboard.protocol.OpModeInfo;
import org.ngicollective.testframework.dashboard.protocol.OpModeStatus;
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
