package org.firstinspires.ftc.teamcode.simulated;

import org.junit.runner.JUnitCore;
import org.junit.runner.Result;
import org.junit.runner.notification.Failure;

/**
 * Starts the Driver Hub Dashboard. The entry point {@code mise run dashboard} reaches.
 *
 * <p>All it does is run {@link DashboardHost} through JUnit, because that is how a Robolectric
 * sandbox is obtained and the sandbox is what makes vision OpModes work off-device. Arguments
 * are handed over in a system property, a JUnit runner having nowhere to put them.</p>
 *
 * <p>The session ends when the process does. A failure here is a session that never started, so
 * it is printed and turned into a non-zero exit rather than a green JUnit summary.</p>
 */
public final class DashboardLauncher {

    private DashboardLauncher() {
    }

    public static void main(String[] args) {
        System.setProperty(DashboardHost.ARGUMENTS_PROPERTY, String.join(" ", args));

        Result result = JUnitCore.runClasses(DashboardHost.class);
        for (Failure failure : result.getFailures()) {
            System.err.println("[dashboard] " + failure.getMessage());
            if (failure.getException() != null) {
                failure.getException().printStackTrace();
            }
        }
        System.exit(result.wasSuccessful() ? 0 : 1);
    }
}
