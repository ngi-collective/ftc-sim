package org.ngicollective.testframework.dashboard;

import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * The {@link LocalDashboardBackend.TickSource} a test drives by hand: no thread, one control cycle
 * per {@link #pump()}, and a clock that only moves when a cycle does.
 *
 * <p>Everything the backend does on a cycle happens on the thread that called {@code pump()} and
 * has finished by the time it returns, so "five cycles later" is a statement a test can make
 * exactly instead of approximating with a sleep. The clock advances by one
 * {@link LocalDashboardBackend#TICK_MILLIS} per cycle, which keeps frame timestamps moving forward
 * the way a reader of the wire would expect without tying them to how long the test took.</p>
 */
final class ManualTicks implements LocalDashboardBackend.TickSource {

    /** Simulated seconds one {@link #pump()} hands to the hardware at a multiplier of 1. */
    static final double SECONDS_PER_PUMP = LocalDashboardBackend.TICK_MILLIS / 1000.0;

    /**
     * Cycles {@link #pumpUntil} will run before giving up.
     *
     * <p>A bound on work rather than on time, deliberately: it is here for the conditions that a
     * {@code LinearOpMode}'s own thread has to satisfy &mdash; it spins on its own and no amount of
     * pumping makes it run &mdash; and this many cycles with a yield between them is far more than
     * a thread handoff needs. A wall-clock deadline would put the flakiness this seam exists to
     * remove straight back in.</p>
     */
    private static final int PUMP_LIMIT = 2000;

    /** Any plausible epoch millisecond; frames are read for order and freshness, not for value. */
    private static final long EPOCH_MILLIS = 1_700_000_000_000L;

    private Runnable cycle;
    private volatile long nowMillis = EPOCH_MILLIS;
    private boolean stopped;

    @Override
    public void start(Runnable cycle) {
        if (this.cycle != null) {
            throw new IllegalStateException("this tick source already drives a session");
        }
        this.cycle = cycle;
    }

    @Override
    public long nowMillis() {
        return nowMillis;
    }

    @Override
    public int telemetryIntervalMillis() {
        return 0;
    }

    @Override
    public void stop() {
        stopped = true;
    }

    /** Runs exactly one control cycle, on this thread, and returns when it is done. */
    void pump() {
        if (cycle == null) {
            throw new IllegalStateException("no session is running on this tick source");
        }
        if (stopped) {
            throw new IllegalStateException(
                    "the session has been closed; a closed session does not tick");
        }
        nowMillis += LocalDashboardBackend.TICK_MILLIS;
        cycle.run();
    }

    /** Runs {@code cycles} control cycles. */
    void pump(int cycles) {
        for (int i = 0; i < cycles; i++) {
            pump();
        }
    }

    /**
     * Pumps until {@code condition} holds, failing the test with {@code message} if it never does.
     *
     * <p>For anything a {@code LinearOpMode} has to do on its own thread &mdash; read a gamepad,
     * compose a telemetry frame, return from {@code runOpMode()}. A cycle the backend runs is
     * visible the moment {@link #pump()} returns and needs no waiting at all.</p>
     */
    void pumpUntil(BooleanSupplier condition, String message) {
        for (int i = 0; i < PUMP_LIMIT; i++) {
            if (condition.getAsBoolean()) {
                return;
            }
            pump();
            Thread.yield();
        }
        if (!condition.getAsBoolean()) {
            fail(message + " (after " + PUMP_LIMIT + " control cycles)");
        }
    }
}
