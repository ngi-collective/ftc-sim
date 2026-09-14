package org.firstinspires.ftc.teamcode.simulated;

import android.content.Context;

import androidx.annotation.NonNull;

import com.qualcomm.hardware.HardwareFactory;
import com.qualcomm.robotcore.eventloop.SyncdDevice;
import com.qualcomm.robotcore.eventloop.opmode.OpModeManagerNotifier;
import com.qualcomm.robotcore.hardware.HardwareMap;
import com.qualcomm.robotcore.util.RobotLog;

import org.ngicollective.testframework.hardware.FakeHardwareMap;
import org.ngicollective.testframework.hardware.SimulatedRobot;

/**
 * The one swap that turns the real Robot Controller app into a simulator: the event loop gets a
 * {@link HardwareMap} full of fake devices instead of one built by scanning USB.
 *
 * <p>{@code HardwareFactory.createHardwareMap} is public and non-final and the app constructs the
 * factory itself, so nothing here edits or reflects on vendor code &mdash; the whole substitution is
 * this override.</p>
 */
public class SimulatedHardwareFactory extends HardwareFactory {

    public static final String TAG = "SimulatedHardwareFactory";

    private final SimulatedRobot robot;
    private final SimulatedClock clock = new SimulatedClock();

    public SimulatedHardwareFactory(Context context, SimulatedRobot robot) {
        super(context);
        this.robot = robot;
    }

    /** The clock driving the most recently built robot, for anything that needs to pause it. */
    public SimulatedClock clock() {
        return clock;
    }

    /**
     * Builds a fresh simulated robot, ignoring the configuration XML and the USB bus entirely.
     *
     * <p>The {@code manager} is a scanner for real USB devices and there are none, so it goes
     * unused; {@code opModeNotifier} is only needed by the real map to reset per-OpMode retrieval
     * bookkeeping, which a fake map does not keep.</p>
     */
    @Override
    public @NonNull HardwareMap createHardwareMap(SyncdDevice.Manager manager,
                                                  OpModeManagerNotifier opModeNotifier) {
        RobotLog.ii(TAG, "building simulated robot \"%s\" - no USB scan", robot.name());
        FakeHardwareMap hardware = robot.create();
        // Nothing else in the app advances simulated time, so the robot would otherwise sit at
        // the origin with its wheels turning and its encoders frozen.
        clock.follow(hardware);
        return hardware;
    }
}
