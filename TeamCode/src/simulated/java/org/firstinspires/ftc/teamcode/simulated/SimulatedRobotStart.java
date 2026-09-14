package org.firstinspires.ftc.teamcode.simulated;

import android.app.Activity;

import com.qualcomm.ftccommon.FtcEventLoop;
import com.qualcomm.ftccommon.FtcEventLoopIdle;
import com.qualcomm.ftccommon.UpdateUI;
import com.qualcomm.robotcore.eventloop.opmode.OpModeRegister;
import com.qualcomm.robotcore.eventloop.EventLoopManager;
import com.qualcomm.robotcore.eventloop.opmode.EventLoopManagerClient;
import com.qualcomm.robotcore.exception.RobotCoreException;
import com.qualcomm.robotcore.robot.Robot;
import com.qualcomm.robotcore.util.RobotLog;

/**
 * Robot start, without waiting for a network.
 *
 * <p>The SDK's own robot start waits for Wi-Fi Direct, because on a real robot the point of
 * starting is to be driven from a Driver Station. An emulator has no Wi-Fi Direct, so that wait
 * never ends: the event loop never runs, no hardware map is ever built, the OpMode registry stays
 * empty, and selecting any OpMode falls through to {@code $Stop$Robot$}. A simulated robot has no
 * Driver Station to wait for, so it does the rest of the sequence and skips that step.</p>
 *
 * <p>"The rest of the sequence" is small, and deliberately mirrors the SDK's own
 * {@code RobotSetupRunnable}, which does five things:</p>
 *
 * <pre>
 *   shutdownRobot()                 kept, as {@link #shutdown}
 *   awaitUSB()                      dropped: a simulated robot has no USB devices to enumerate
 *   initializeEventLoopAndRobot()   kept
 *   waitForNetwork()                dropped: this is the step that cannot finish here
 *   startRobot()                    kept
 * </pre>
 *
 * <p>Everything below this line is then the genuine article: the SDK's event loop calls the OpMode,
 * its {@code OpModeManagerImpl} runs the lifecycle, its registry answers OpMode names, and
 * LiveView draws to a real view. Measured on an emulator, an iterative OpMode's {@code loop()}
 * runs at around 600 Hz and a vision OpMode streams at its configured frame rate.</p>
 *
 * <h2>What is given up</h2>
 *
 * <p>All of it Driver Station facing: no Robocol connection, so no Driver Station, no telemetry off
 * the device, and no camera stream to a Driver Station. The web server is not started either, so
 * OnBotJava and the configuration pages are absent. What a simulated robot is for &mdash; running
 * OpModes against fake hardware and a rendered camera &mdash; needs none of them.</p>
 *
 * <p>One consequence worth knowing: the app's "Restart Robot" menu goes through the SDK's own path
 * and will therefore hang on the network wait. Restart the app instead.</p>
 */
final class SimulatedRobotStart {

    private static final String TAG = "SimulatedRobotStart";

    private final Activity activity;
    private final EventLoopManagerClient client;

    private EventLoopManager eventLoopManager;
    private Robot robot;
    private FtcEventLoop eventLoop;

    /**
     * @param client the SDK's own client for the event loop manager; the Robot Controller service
     *               implements it, and passing the real one keeps the web server reachable to
     *               anything that asks for it
     */
    SimulatedRobotStart(Activity activity, EventLoopManagerClient client) {
        this.activity = activity;
        this.client = client;
    }

    /**
     * Builds the event loops and starts a robot on them, replacing any robot already running.
     *
     * <p>The loops are assembled here rather than by the caller so that there is exactly one
     * description of what a simulated robot's bring-up consists of, and a test can exercise it
     * instead of a copy of it.</p>
     */
    void start(SimulatedHardwareFactory hardwareFactory, OpModeRegister register,
               UpdateUI.Callback callback) throws RobotCoreException {
        shutdown();

        eventLoop = new FtcEventLoop(hardwareFactory, register, callback, activity);
        FtcEventLoopIdle idleLoop =
                new FtcEventLoopIdle(hardwareFactory, register, callback, activity);

        eventLoopManager = new EventLoopManager(activity, client, idleLoop);
        robot = new Robot(eventLoopManager);
        robot.start(eventLoop);

        RobotLog.ii(TAG, "simulated robot started without a network; state %s",
                eventLoopManager.state);
    }

    /** The loop running OpModes, for the SDK's own annotated event-loop hooks. */
    FtcEventLoop eventLoop() {
        return eventLoop;
    }

    /** Stops the robot and its event loop, if one is running. */
    void shutdown() {
        if (robot != null) {
            robot.shutdown();
            robot = null;
            eventLoopManager = null;
            eventLoop = null;
        }
    }
}
