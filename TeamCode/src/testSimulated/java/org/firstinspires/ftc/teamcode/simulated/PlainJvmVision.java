package org.firstinspires.ftc.teamcode.simulated;

import android.app.Activity;
import android.os.Looper;
import android.widget.LinearLayout;

import com.qualcomm.robotcore.eventloop.opmode.OpModeManagerImpl;

import org.firstinspires.ftc.robotcore.internal.system.AppUtil;
import org.mockito.Mockito;
import org.ngicollective.testframework.vision.VisionNatives;
import org.openftc.easyopencv.SyntheticCameras;
import org.robolectric.Robolectric;
import org.robolectric.Shadows;

import java.lang.reflect.Field;
import java.util.Map;

/**
 * What the plain JVM execution target needs before a vision OpMode will run on it.
 *
 * <p>The counterpart of {@code RobotUnderTest}, which does the same job on the emulator by
 * performing a real robot start. There is no robot start here &mdash; a harness runs the OpMode
 * &mdash; so the four things a started robot would have published have to be arranged directly.
 * Everything else the SDK reaches for on the way to a frame (an application, a package manager,
 * the resource table the built-in camera calibrations live in, a Wi-Fi service, an activity
 * manager) comes from Robolectric, which is why these tests run under it.</p>
 *
 * <p>Call {@link #start()} first, then {@link #pump()} once per control cycle. Both belong on the
 * test's own thread: Robolectric's main looper is paused, and nothing posted to it runs unless
 * something drains it.</p>
 */
final class PlainJvmVision {

    private PlainJvmVision() {
    }

    /** Arranges everything a vision OpMode expects to find, and installs the simulated camera. */
    static void start() throws Exception {
        VisionNatives.ensureLoaded();
        provideAnActivity();
        registerAnOpModeManager();
        SyntheticCameras.install();
    }

    /**
     * Runs whatever the camera has posted to the Android main thread.
     *
     * <p>On a robot the UI thread drains that queue by itself. Under Robolectric the looper is
     * paused, so a camera that opens asynchronously never finishes opening unless the test does
     * this between control cycles.</p>
     */
    static void pump() {
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    /**
     * An Activity carrying the view EasyOpenCV's LiveView attaches its viewport to.
     *
     * <p>The view has to exist and has to be a {@code LinearLayout}, which is what EasyOpenCV
     * casts it to; the Robot Controller's own layout supplies both on a robot. The Activity is
     * fitted into AppUtil's fields directly because {@code AppUtil.initialize} does a great deal
     * more than this and wants a Looper of its own.</p>
     */
    private static void provideAnActivity() throws Exception {
        Activity activity = Robolectric.buildActivity(Activity.class).create().start().resume()
                .get();

        int container = activity.getResources().getIdentifier(
                "cameraMonitorViewId", "id", activity.getPackageName());
        LinearLayout monitor = new LinearLayout(activity);
        monitor.setId(container);
        activity.setContentView(monitor);

        AppUtil appUtil = AppUtil.getInstance();
        for (String name : new String[] {"rootActivity", "currentActivity"}) {
            Field field = AppUtil.class.getDeclaredField(name);
            field.setAccessible(true);
            field.set(appUtil, activity);
        }
    }

    /**
     * The OpMode manager EasyOpenCV and {@code VisionPortalImpl} both register a stopped-listener
     * with.
     *
     * <p>Only its identity is used here: nothing in a harness-run OpMode calls it back, and the
     * portal's own listener is what releases the LiveView container id. Without one the portal
     * throws <em>after</em> claiming that id, and the next portal in the process fails with a
     * complaint about multiple vision portals instead.</p>
     */
    private static void registerAnOpModeManager() throws Exception {
        OpModeManagerImpl manager = Mockito.mock(OpModeManagerImpl.class);
        Field registry = OpModeManagerImpl.class.getDeclaredField("mapActivityToOpModeManager");
        registry.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<Activity, OpModeManagerImpl> byActivity =
                (Map<Activity, OpModeManagerImpl>) registry.get(null);
        byActivity.put(AppUtil.getInstance().getActivity(), manager);
    }
}
