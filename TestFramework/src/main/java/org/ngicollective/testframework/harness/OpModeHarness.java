package org.ngicollective.testframework.harness;

import com.qualcomm.robotcore.eventloop.opmode.LinearOpMode;
import com.qualcomm.robotcore.eventloop.opmode.OpMode;
import com.qualcomm.robotcore.hardware.Gamepad;
import com.qualcomm.robotcore.robocol.TelemetryMessage;

import org.firstinspires.ftc.robotcore.internal.opmode.OpModeServices;
import org.ngicollective.testframework.hardware.FakeHardwareMap;

import java.lang.reflect.Field;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Drives a real, unmodified OpMode on a plain JVM: no robot, no Android emulator, no Robolectric.
 *
 * <p>The SDK gives an OpMode its {@code HardwareMap}, gamepads, start/stop flags and telemetry
 * plumbing from {@code OpModeManagerImpl} during robot boot. None of that exists in a unit test, and
 * the flags and the telemetry collaborator are package-private, so this harness sets them with
 * reflection. The OpMode itself is untouched.</p>
 *
 * <p>Two flavours, matching the SDK's two OpMode shapes:
 * {@link #forLinear(LinearOpMode, FakeHardwareMap)} and
 * {@link #forIterative(OpMode, FakeHardwareMap)}.</p>
 *
 * <h2>What this harness does not do</h2>
 * <p>Simulated time advances device behaviors only. {@code LinearOpMode.sleep()} is {@code final}
 * and calls {@code Thread.sleep}, and {@code ElapsedTime} reads {@code System.nanoTime()}, so an
 * OpMode that sleeps still burns that much real time here. Direct calls to {@code RobotLog} or
 * {@code Gamepad.refreshTimestamp()} reach unmocked {@code android.*} methods and will throw; run
 * those OpModes on the emulator target instead.</p>
 */
public abstract class OpModeHarness {

    /** Frames kept for inspection; an init loop that spins on telemetry.update() can produce many. */
    public static final int DEFAULT_TELEMETRY_HISTORY = 1000;

    protected final OpMode opMode;
    private final FakeHardwareMap hardware;
    private final Deque<List<String>> telemetryFrames = new ArrayDeque<>();
    private final List<TelemetryListener> telemetryListeners = new CopyOnWriteArrayList<>();
    private int telemetryHistory = DEFAULT_TELEMETRY_HISTORY;

    protected OpModeHarness(OpMode opMode, FakeHardwareMap hardware) {
        if (opMode == null || hardware == null) {
            throw new IllegalArgumentException("opMode and hardware are required");
        }
        this.opMode = opMode;
        this.hardware = hardware;

        opMode.hardwareMap = hardware;
        opMode.gamepad1 = new Gamepad();
        opMode.gamepad2 = new Gamepad();
        setInternalField(OpModeFields.opModeInternalClass(), "internalOpModeServices", services());
        // Transmit every update(), so a test sees each frame instead of the SDK's 250 ms sampling.
        opMode.telemetry.setMsTransmissionInterval(0);
    }

    /** Harness for a {@link LinearOpMode}: {@code runOpMode()} runs on its own thread. */
    public static LinearOpModeHarness forLinear(LinearOpMode opMode, FakeHardwareMap hardware) {
        return new LinearOpModeHarness(opMode, hardware);
    }

    /** Harness for an iterative {@code OpMode}: the test drives each lifecycle call itself. */
    public static IterativeOpModeHarness forIterative(OpMode opMode, FakeHardwareMap hardware) {
        return new IterativeOpModeHarness(opMode, hardware);
    }

    public OpMode opMode() {
        return opMode;
    }

    public FakeHardwareMap hardware() {
        return hardware;
    }

    /** The gamepad wired to {@code opMode.gamepad1}; write its fields to simulate driver input. */
    public Gamepad gamepad1() {
        return opMode.gamepad1;
    }

    /** The gamepad wired to {@code opMode.gamepad2}; write its fields to simulate driver input. */
    public Gamepad gamepad2() {
        return opMode.gamepad2;
    }

    /** Advances simulated time for every fake device. */
    public void advance(double seconds) {
        hardware.advance(seconds);
    }

    public boolean isStarted() {
        return getBooleanField("isStarted");
    }

    public boolean isStopRequested() {
        return getBooleanField("stopRequested");
    }

    /**
     * Simulates the driver station's PLAY button. Mirrors the SDK's {@code internalStart()}: clears
     * the stop flag, sets the start flag, then releases anything blocked in {@code waitForStart()}.
     */
    public void pressStart() {
        setInternalField(OpModeFields.opModeInternalClass(), "stopRequested", false);
        setInternalField(OpModeFields.opModeInternalClass(), "isStarted", true);
        onStarted();
    }

    /** Simulates the driver station's STOP button. */
    public void pressStop() {
        setInternalField(OpModeFields.opModeInternalClass(), "stopRequested", true);
        onStopRequested();
    }

    /** Every telemetry frame transmitted so far, oldest first, capped at the history limit. */
    public List<List<String>> telemetryFrames() {
        synchronized (telemetryFrames) {
            return new ArrayList<>(telemetryFrames);
        }
    }

    /** The most recently transmitted telemetry frame, or an empty list if there has been none. */
    public List<String> lastTelemetry() {
        synchronized (telemetryFrames) {
            return telemetryFrames.isEmpty()
                    ? Collections.<String>emptyList()
                    : new ArrayList<>(telemetryFrames.peekLast());
        }
    }

    /** Streams telemetry frames as they are transmitted; this is the dashboard's read side. */
    public void addTelemetryListener(TelemetryListener listener) {
        telemetryListeners.add(listener);
    }

    /** How many frames {@link #telemetryFrames()} retains. */
    public void setTelemetryHistory(int frames) {
        if (frames < 1) {
            throw new IllegalArgumentException("history must keep at least one frame");
        }
        synchronized (telemetryFrames) {
            telemetryHistory = frames;
            trimHistory();
        }
    }

    /** Called after the start flag is set, on the thread that called {@link #pressStart()}. */
    protected void onStarted() {
    }

    /** Called after the stop flag is set, on the thread that called {@link #pressStop()}. */
    protected void onStopRequested() {
    }

    protected void setInternalField(Class<?> declaringClass, String name, Object value) {
        try {
            Field field = declaringClass.getDeclaredField(name);
            field.setAccessible(true);
            field.set(opMode, value);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            throw new IllegalStateException(
                    "The SDK's " + declaringClass.getSimpleName() + "." + name
                            + " field is not where this harness expects it; the FTC SDK version has "
                            + "probably changed.", e);
        }
    }

    protected Object getInternalField(Class<?> declaringClass, String name) {
        try {
            Field field = declaringClass.getDeclaredField(name);
            field.setAccessible(true);
            return field.get(opMode);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            throw new IllegalStateException(
                    "The SDK's " + declaringClass.getSimpleName() + "." + name
                            + " field is not where this harness expects it; the FTC SDK version has "
                            + "probably changed.", e);
        }
    }

    private boolean getBooleanField(String name) {
        return (Boolean) getInternalField(OpModeFields.opModeInternalClass(), name);
    }

    private OpModeServices services() {
        return new OpModeServices() {
            @Override
            public void refreshUserTelemetry(TelemetryMessage telemetry, double sInterval) {
                record(new ArrayList<>(telemetry.getDataStrings().values()));
            }

            @Override
            public void requestOpModeStop(OpMode opModeToStopIfActive) {
                if (opModeToStopIfActive == opMode) {
                    pressStop();
                }
            }
        };
    }

    private void record(List<String> lines) {
        synchronized (telemetryFrames) {
            telemetryFrames.addLast(lines);
            trimHistory();
        }
        for (TelemetryListener listener : telemetryListeners) {
            listener.onTelemetry(lines);
        }
    }

    private void trimHistory() {
        while (telemetryFrames.size() > telemetryHistory) {
            telemetryFrames.removeFirst();
        }
    }

    /** Locates the SDK's package-private lifecycle fields, which live on {@code OpModeInternal}. */
    static final class OpModeFields {

        private static final String OP_MODE_INTERNAL =
                "com.qualcomm.robotcore.eventloop.opmode.OpModeInternal";

        private OpModeFields() {
        }

        static Class<?> opModeInternalClass() {
            try {
                return Class.forName(OP_MODE_INTERNAL);
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException(OP_MODE_INTERNAL + " is missing from the classpath", e);
            }
        }
    }
}
