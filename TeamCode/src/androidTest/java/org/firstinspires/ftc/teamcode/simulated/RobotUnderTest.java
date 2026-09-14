package org.firstinspires.ftc.teamcode.simulated;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.util.Log;
import android.widget.TextView;

import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;

import com.qualcomm.ftccommon.UpdateUI;
import com.qualcomm.robotcore.eventloop.opmode.EventLoopManagerClient;
import com.qualcomm.robotcore.eventloop.opmode.OpModeManagerImpl;
import com.qualcomm.robotcore.robot.RobotState;
import com.qualcomm.robotcore.util.Dimmer;
import com.qualcomm.robotcore.util.WebServer;

import org.firstinspires.ftc.robotcore.internal.opmode.OnBotJavaHelper;
import org.firstinspires.ftc.robotcore.internal.system.AppUtil;
import org.openftc.easyopencv.SyntheticCameras;

/**
 * A running simulated robot for an instrumented test to drive.
 *
 * <p>Started through the production {@link SimulatedRobotStart}, so what the tests exercise is the
 * bring-up the app uses rather than a copy of it. The app's <em>own</em> robot cannot be used:
 * the test runner stops any activity it did not start itself, and the Robot Controller shuts a
 * robot down when its activity stops.</p>
 */
final class RobotUnderTest {

    private static final String TAG = "RobotUnderTest";

    private static SimulatedRobotStart robotStart;

    private RobotUnderTest() {
    }

    /** Brings up a robot whose event loop is running, and leaves it running. */
    static void start() throws Exception {
        grantPermissions();
        SyntheticCameras.install();

        // An activity, only because the SDK builds its event loop and OpModeManagerImpl against
        // one. Which activity it is does not matter here.
        ActivityScenario.launch(SimulatedRobotControllerActivity.class);
        final Activity activity = awaitAnActivity();

        final Exception[] failure = new Exception[1];
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            try {
                startOn(activity);
            } catch (Exception e) {
                failure[0] = e;
            }
        });
        if (failure[0] != null) {
            throw failure[0];
        }
        awaitRunning();
    }

    static void stop() {
        if (robotStart != null) {
            robotStart.shutdown();
            robotStart = null;
        }
    }

    /** The SDK's OpMode manager for the running robot. */
    static OpModeManagerImpl manager() {
        Activity activity = AppUtil.getInstance().getActivity();
        return activity == null ? null : OpModeManagerImpl.getOpModeManagerOfActivity(activity);
    }

    private static void startOn(Activity activity) throws Exception {
        UpdateUI updateUI = new UpdateUI(activity, new Dimmer(activity));
        // Real TextViews, never shown: UpdateUI.Callback's constructor writes a device name into
        // them immediately, and nulls are an NPE inside the constructor.
        updateUI.setTextViews(new TextView(activity), new TextView(activity),
                new TextView[] {new TextView(activity), new TextView(activity)},
                new TextView(activity), new TextView(activity), new TextView(activity));
        UpdateUI.Callback callback = updateUI.new Callback();

        robotStart = new SimulatedRobotStart(activity, new EventLoopManagerClient() {
            @Override
            public WebServer getWebServer() {
                // Only dereferenced by a Driver Station command handler, and there is none here.
                return null;
            }

            @Override
            public OnBotJavaHelper getOnBotJavaHelper() {
                return null;
            }
        });
        robotStart.start(new SimulatedHardwareFactory(activity, new VerityRobot()),
                manager -> { }, callback);
    }

    private static Activity awaitAnActivity() throws InterruptedException {
        for (int attempt = 0; attempt < 200; attempt++) {
            Activity activity = AppUtil.getInstance().getActivity();
            if (activity != null) {
                return activity;
            }
            Thread.sleep(25);
        }
        throw new IllegalStateException("no activity to host an event loop");
    }

    private static void awaitRunning() throws InterruptedException {
        for (int attempt = 0; attempt < 150; attempt++) {
            OpModeManagerImpl manager = manager();
            if (manager != null && manager.getRobotState() == RobotState.RUNNING) {
                Log.i(TAG, "robot running after " + (attempt * 100) + " ms");
                return;
            }
            Thread.sleep(100);
        }
        throw new IllegalStateException("the robot never started");
    }

    /**
     * Grants every runtime permission the Robot Controller declares.
     *
     * <p>Gradle's own install does not grant them, and the permission validator refuses to set a
     * robot up without them. The symptom is not a permission error but a silent hang.</p>
     */
    private static void grantPermissions() throws Exception {
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        Context target = instrumentation.getTargetContext();
        PackageInfo info = target.getPackageManager()
                .getPackageInfo(target.getPackageName(), PackageManager.GET_PERMISSIONS);
        if (info.requestedPermissions == null) {
            return;
        }
        for (String permission : info.requestedPermissions) {
            // Normal permissions cannot be granted this way and do not need to be; let the shell
            // refuse them rather than maintaining a list that drifts from the manifest.
            instrumentation.getUiAutomation().executeShellCommand(
                    "pm grant " + target.getPackageName() + " " + permission).close();
        }
        // The grants are asynchronous; the app reads them during its own startup.
        Thread.sleep(500);
    }
}
