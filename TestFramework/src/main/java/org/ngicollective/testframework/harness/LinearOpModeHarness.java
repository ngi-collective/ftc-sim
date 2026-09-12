package org.ngicollective.testframework.harness;

import com.qualcomm.robotcore.eventloop.opmode.LinearOpMode;

import org.ngicollective.testframework.hardware.FakeHardwareMap;

/**
 * Runs a {@link LinearOpMode}'s {@code runOpMode()} on a background thread while the test thread
 * plays driver station: press start, advance simulated time, inspect hardware, press stop.
 *
 * <pre>
 * LinearOpModeHarness harness = OpModeHarness.forLinear(new MyTeleOp(), hardware);
 * harness.launch();          // runOpMode() begins and blocks in waitForStart()
 * harness.pressStart();
 * harness.gamepad1().left_stick_y = -1.0f;
 * harness.advance(0.5);      // half a simulated second of motor travel
 * harness.pressStop();
 * harness.awaitCompletion(2000);
 * </pre>
 */
public class LinearOpModeHarness extends OpModeHarness {

    private final LinearOpMode linearOpMode;
    private Thread thread;
    private volatile Throwable failure;

    LinearOpModeHarness(LinearOpMode opMode, FakeHardwareMap hardware) {
        super(opMode, hardware);
        this.linearOpMode = opMode;
    }

    /** Starts the OpMode thread. It runs the init phase and then blocks until {@link #pressStart()}. */
    public void launch() {
        if (thread != null) {
            throw new IllegalStateException("already launched");
        }
        thread = new Thread(() -> {
            try {
                linearOpMode.runOpMode();
            } catch (Throwable t) {
                failure = t;
            }
        }, "opmode-" + linearOpMode.getClass().getSimpleName());
        thread.setDaemon(true);
        thread.start();
    }

    /** Whether {@code runOpMode()} has returned. */
    public boolean isFinished() {
        return thread != null && !thread.isAlive();
    }

    /**
     * Waits for {@code runOpMode()} to return, then rethrows anything it threw.
     *
     * @param millis real milliseconds to wait; simulated time does not apply to a thread join
     * @throws IllegalStateException if the OpMode is still running when the wait expires
     */
    public void awaitCompletion(long millis) {
        if (thread == null) {
            throw new IllegalStateException("not launched");
        }
        try {
            thread.join(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while waiting for the OpMode to finish", e);
        }
        if (thread.isAlive()) {
            throw new IllegalStateException(
                    linearOpMode.getClass().getSimpleName() + " was still running after " + millis
                            + " ms. Did the test press stop, and does the OpMode's loop check "
                            + "opModeIsActive()?");
        }
        rethrowFailure();
    }

    /** Anything {@code runOpMode()} threw, or null. */
    public Throwable failure() {
        return failure;
    }

    @Override
    protected void onStarted() {
        notifyRunningNotifier();
    }

    @Override
    protected void onStopRequested() {
        notifyRunningNotifier();
        // waitForStart() only returns on start or interrupt, so the SDK interrupts the OpMode thread
        // when the driver station stops an OpMode. Match that, or a stop during init would hang.
        if (thread != null) {
            thread.interrupt();
        }
    }

    private void notifyRunningNotifier() {
        Object notifier = getInternalField(LinearOpMode.class, "runningNotifier");
        synchronized (notifier) {
            notifier.notifyAll();
        }
    }

    private void rethrowFailure() {
        Throwable t = failure;
        if (t == null) {
            return;
        }
        if (t instanceof RuntimeException) {
            throw (RuntimeException) t;
        }
        if (t instanceof Error) {
            throw (Error) t;
        }
        throw new IllegalStateException(linearOpMode.getClass().getSimpleName() + " threw", t);
    }
}
