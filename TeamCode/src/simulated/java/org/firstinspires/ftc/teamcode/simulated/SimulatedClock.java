package org.firstinspires.ftc.teamcode.simulated;

import com.qualcomm.robotcore.util.RobotLog;

import org.ngicollective.testframework.hardware.FakeHardwareMap;

/**
 * Drives simulated time forward inside the app.
 *
 * <p>Without this the simulated robot in the app never moves. Nothing in the Robot Controller
 * calls {@code FakeHardwareMap.advance}: on real hardware time passes by itself, so the app has no
 * reason to tick anything, and a simulated robot whose clock nobody turns sits at the field origin
 * with its wheels commanded and its encoders frozen. That is a confusing failure, because
 * telemetry and the Driver Station look entirely healthy.</p>
 *
 * <p>Ticks at a fixed rate off the wall clock, advancing by however much real time actually
 * elapsed. Sim time therefore tracks real time, which is what an OpMode's own
 * {@code ElapsedTime} and {@code sleep()} are measuring against &mdash; a fixed step per tick
 * would let the two drift apart under load, and a heading-holding routine tuned against a drifting
 * clock would be tuned against nothing.</p>
 *
 * <p>One tick thread, one map at a time: replacing the map (which happens whenever the app builds
 * a new robot) just redirects the ticks.</p>
 *
 * <p><b>Where this actually runs.</b> It is started by {@code SimulatedHardwareFactory}, which the
 * event loop calls during robot start &mdash; so on a Control Hub or a phone, but <em>not</em> on
 * an emulator, where robot start waits for a Wi-Fi Direct network that is not there and stops with
 * an internal error. A test that needs simulated time on an emulator advances the map itself; see
 * the Simulation notes in CLAUDE.md.</p>
 */
public final class SimulatedClock {

    public static final String TAG = "SimulatedClock";

    /** 50 Hz, matching the Robot Controller's own loop rate. */
    private static final long PERIOD_MILLIS = 20L;

    /**
     * The largest step taken in one tick.
     *
     * <p>A thread that was descheduled for a second must not hand the drive model a one-second
     * step: the robot would teleport, and a collision would be missed entirely. Losing simulated
     * time is the better failure, and saying so out loud is better than either.</p>
     */
    private static final double MAX_STEP_SECONDS = 0.1;

    private final Object lock = new Object();

    private FakeHardwareMap hardware;
    private Thread thread;
    private volatile boolean running;

    /** Points the clock at a hardware map, starting it if this is the first one. */
    public void follow(FakeHardwareMap hardware) {
        synchronized (lock) {
            this.hardware = hardware;
            if (thread != null) {
                return;
            }
            running = true;
            thread = new Thread(new Runnable() {
                @Override
                public void run() {
                    tickUntilStopped();
                }
            }, "SimulatedClock");
            // A daemon: the simulated clock has no business keeping the app alive.
            thread.setDaemon(true);
            thread.start();
            RobotLog.ii(TAG, "simulated clock running at %d Hz", 1000L / PERIOD_MILLIS);
        }
    }

    /** Stops ticking. */
    public void stop() {
        synchronized (lock) {
            running = false;
            thread = null;
        }
    }

    private void tickUntilStopped() {
        long previous = System.nanoTime();
        while (running) {
            try {
                Thread.sleep(PERIOD_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }

            long now = System.nanoTime();
            double elapsed = (now - previous) / 1_000_000_000.0;
            previous = now;

            FakeHardwareMap current;
            synchronized (lock) {
                current = hardware;
            }
            if (current == null) {
                continue;
            }

            try {
                current.advance(Math.min(elapsed, MAX_STEP_SECONDS));
            } catch (RuntimeException e) {
                // A throw here would kill the clock silently and freeze the robot; the OpMode
                // would keep running against a robot that had stopped existing.
                RobotLog.ee(TAG, e, "simulated tick failed");
            }
        }
    }
}
