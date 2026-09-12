package android.util;

import java.io.PrintWriter;
import java.io.StringWriter;

/**
 * A working {@code android.util.Log} for plain-JVM unit tests, writing to the console.
 *
 * <p>Exists for the same reason as the {@code android.opengl.Matrix} shim: the stub
 * {@code android.jar} on a unit-test classpath throws {@code "Method ... not mocked"} from every
 * method. That takes down anything that logs &mdash; the SDK's own {@code RobotLog}, and the SLF4J
 * binding the FTC SDK ships, which the WebSocket library used by the dashboard logs through.</p>
 *
 * <p>Reaches unit-test classpaths only; on a robot the real framework class is used.</p>
 *
 * <p>Verbosity defaults to {@code DEBUG}, which keeps an OpMode's own {@code RobotLog.d} visible
 * while silencing library trace chatter. Override with {@code -Dftc.log.level=VERBOSE} (or
 * {@code INFO}, {@code WARN}, {@code ERROR}, {@code NONE}).</p>
 */
public final class Log {

    public static final int VERBOSE = 2;
    public static final int DEBUG = 3;
    public static final int INFO = 4;
    public static final int WARN = 5;
    public static final int ERROR = 6;
    public static final int ASSERT = 7;

    private static final int THRESHOLD = threshold();

    private Log() {
    }

    public static boolean isLoggable(String tag, int level) {
        return level >= THRESHOLD;
    }

    public static int v(String tag, String message) {
        return println(VERBOSE, tag, message);
    }

    public static int v(String tag, String message, Throwable throwable) {
        return println(VERBOSE, tag, withStackTrace(message, throwable));
    }

    public static int d(String tag, String message) {
        return println(DEBUG, tag, message);
    }

    public static int d(String tag, String message, Throwable throwable) {
        return println(DEBUG, tag, withStackTrace(message, throwable));
    }

    public static int i(String tag, String message) {
        return println(INFO, tag, message);
    }

    public static int i(String tag, String message, Throwable throwable) {
        return println(INFO, tag, withStackTrace(message, throwable));
    }

    public static int w(String tag, String message) {
        return println(WARN, tag, message);
    }

    public static int w(String tag, String message, Throwable throwable) {
        return println(WARN, tag, withStackTrace(message, throwable));
    }

    public static int w(String tag, Throwable throwable) {
        return println(WARN, tag, getStackTraceString(throwable));
    }

    public static int e(String tag, String message) {
        return println(ERROR, tag, message);
    }

    public static int e(String tag, String message, Throwable throwable) {
        return println(ERROR, tag, withStackTrace(message, throwable));
    }

    public static int wtf(String tag, String message) {
        return println(ASSERT, tag, message);
    }

    public static int wtf(String tag, String message, Throwable throwable) {
        return println(ASSERT, tag, withStackTrace(message, throwable));
    }

    public static int wtf(String tag, Throwable throwable) {
        return println(ASSERT, tag, getStackTraceString(throwable));
    }

    public static String getStackTraceString(Throwable throwable) {
        if (throwable == null) {
            return "";
        }
        StringWriter writer = new StringWriter();
        throwable.printStackTrace(new PrintWriter(writer));
        return writer.toString();
    }

    public static int println(int priority, String tag, String message) {
        if (priority < THRESHOLD) {
            return 0;
        }
        String line = letterFor(priority) + "/" + tag + ": " + message;
        if (priority >= WARN) {
            System.err.println(line);
        } else {
            System.out.println(line);
        }
        return line.length();
    }

    private static String withStackTrace(String message, Throwable throwable) {
        return message + "\n" + getStackTraceString(throwable);
    }

    private static char letterFor(int priority) {
        switch (priority) {
            case VERBOSE: return 'V';
            case DEBUG: return 'D';
            case INFO: return 'I';
            case WARN: return 'W';
            case ERROR: return 'E';
            default: return 'A';
        }
    }

    private static int threshold() {
        String configured = System.getProperty("ftc.log.level", "DEBUG");
        switch (configured.toUpperCase(java.util.Locale.ROOT)) {
            case "VERBOSE": return VERBOSE;
            case "INFO": return INFO;
            case "WARN": return WARN;
            case "ERROR": return ERROR;
            case "NONE": return Integer.MAX_VALUE;
            default: return DEBUG;
        }
    }
}
