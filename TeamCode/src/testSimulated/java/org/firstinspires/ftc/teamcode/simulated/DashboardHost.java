package org.firstinspires.ftc.teamcode.simulated;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.ngicollective.testframework.dashboard.DashboardMain;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.concurrent.TimeUnit;

/**
 * The Dashboard Server, running inside a Robolectric sandbox so that vision OpModes work.
 *
 * <h2>Why a test class runs a server</h2>
 *
 * <p>A vision OpMode needs the Android framework, not just the native libraries: without it the
 * session dies on {@code android.os.Environment}, and behind that sits the compiled resource the
 * SDK keeps its camera calibrations in. Robolectric is the only desktop implementation of that
 * framework, and it hands one out per test class, through a JUnit runner. So the session is a
 * test method &mdash; the ugliest thing in this repo, and the price of a Dashboard that can run
 * every OpMode it lists rather than most of them.</p>
 *
 * <p>Started by {@link DashboardLauncher}, never by the test task: {@code @Test} methods are
 * found by name, and this one is filtered out of {@code mise run test} by the
 * {@code DashboardHost} exclusion in TeamCode's build. A session that a CI run started and then
 * waited on forever would be a memorable way to find that out.</p>
 *
 * <h2>The pump</h2>
 *
 * <p>Robolectric's main looper is paused. On a robot the UI thread drains it; here nothing does,
 * so a camera that opens asynchronously never finishes opening. This method is the drain, and it
 * is why the session owns this thread rather than blocking in {@code DashboardMain.main}.</p>
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class DashboardHost {

    /** Passed by {@link DashboardLauncher}, because a JUnit runner takes no arguments. */
    static final String ARGUMENTS_PROPERTY = "dashboard.args";

    /**
     * How often the Android main looper is drained.
     *
     * <p>Five milliseconds is a quarter of the simulation's own 20 ms control cycle, so a frame
     * the camera posts is picked up within the tick that produced it, and an idle session costs
     * nothing measurable.</p>
     */
    private static final long PUMP_MILLIS = 5;

    @Test
    public void serveUntilStopped() throws Exception {
        PlainJvmVision.start();

        String arguments = System.getProperty(ARGUMENTS_PROPERTY, "");
        DashboardMain.start(arguments.isEmpty() ? new String[0] : arguments.split("\\s+"));

        while (!Thread.currentThread().isInterrupted()) {
            PlainJvmVision.pump();
            TimeUnit.MILLISECONDS.sleep(PUMP_MILLIS);
        }
    }
}
