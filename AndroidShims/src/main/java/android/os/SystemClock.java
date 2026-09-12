package android.os;

/**
 * A working {@code android.os.SystemClock} for plain-JVM unit tests.
 *
 * <p>Companion to the {@code android.util.Log} shim: the stub {@code android.jar} throws from every
 * method, which is what makes {@code Gamepad.refreshTimestamp()} fatal in a unit test. The clocks
 * here are monotonic from JVM start, which is the guarantee callers actually rely on.</p>
 *
 * <p>This is real time, not the framework's simulated time: nothing here is affected by
 * {@code FakeHardwareMap.advance(...)}.</p>
 */
public final class SystemClock {

    private static final long ORIGIN_NANOS = System.nanoTime();

    private SystemClock() {
    }

    /** Milliseconds since JVM start. Android excludes deep sleep; a JVM never sleeps. */
    public static long uptimeMillis() {
        return (System.nanoTime() - ORIGIN_NANOS) / 1_000_000L;
    }

    public static long elapsedRealtime() {
        return uptimeMillis();
    }

    public static long elapsedRealtimeNanos() {
        return System.nanoTime() - ORIGIN_NANOS;
    }

    public static long currentThreadTimeMillis() {
        return uptimeMillis();
    }

    public static boolean setCurrentTimeMillis(long millis) {
        return false;
    }

    public static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
