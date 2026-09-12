package org.ngicollective.testframework.hardware;

import com.qualcomm.robotcore.hardware.IMU;

import org.firstinspires.ftc.robotcore.external.navigation.AngleUnit;
import org.firstinspires.ftc.robotcore.external.navigation.AngularVelocity;
import org.firstinspires.ftc.robotcore.external.navigation.AxesOrder;
import org.firstinspires.ftc.robotcore.external.navigation.AxesReference;
import org.firstinspires.ftc.robotcore.external.navigation.Orientation;
import org.firstinspires.ftc.robotcore.external.navigation.Quaternion;
import org.firstinspires.ftc.robotcore.external.navigation.UnnormalizedAngleUnit;
import org.firstinspires.ftc.robotcore.external.navigation.YawPitchRollAngles;
import org.ngicollective.testframework.behavior.Behavior;
import org.ngicollective.testframework.behavior.ImuState;

/**
 * A simulated {@link IMU}.
 *
 * <p>Orientation conversions delegate to the SDK's own {@link Orientation} and {@link Quaternion}
 * maths, so a test sees exactly the numbers the real driver would produce for a given attitude.</p>
 */
public class FakeImu extends FakeDevice<ImuState> implements IMU {

    private Parameters parameters;
    private boolean initialized;

    FakeImu(String configuredName, Behavior<ImuState> behavior) {
        super(configuredName, new ImuState(), behavior);
    }

    @Override
    public String getDeviceName() {
        return "Simulated IMU";
    }

    @Override
    public void resetDeviceConfigurationForOpMode() {
        parameters = null;
        initialized = false;
        state().setYawOffset(0.0);
    }

    /** Whether the OpMode called {@link #initialize(Parameters)}; assert on this to catch omissions. */
    public boolean isInitialized() {
        return initialized;
    }

    /** The parameters the OpMode initialized with, or null if it never did. */
    public Parameters parameters() {
        return parameters;
    }

    @Override
    public boolean initialize(Parameters parameters) {
        this.parameters = parameters;
        this.initialized = true;
        return true;
    }

    @Override
    public void resetYaw() {
        state().setYawOffset(state().getYaw());
    }

    @Override
    public YawPitchRollAngles getRobotYawPitchRollAngles() {
        return new YawPitchRollAngles(
                AngleUnit.DEGREES,
                state().reportedYaw(),
                state().getPitch(),
                state().getRoll(),
                System.nanoTime());
    }

    @Override
    public Orientation getRobotOrientation(AxesReference reference, AxesOrder order, AngleUnit angleUnit) {
        return canonicalOrientation()
                .toAxesReference(reference)
                .toAxesOrder(order)
                .toAngleUnit(angleUnit);
    }

    @Override
    public Quaternion getRobotOrientationAsQuaternion() {
        return Quaternion.fromMatrix(canonicalOrientation().getRotationMatrix(), System.nanoTime());
    }

    @Override
    public AngularVelocity getRobotAngularVelocity(AngleUnit angleUnit) {
        double degreesPerSecond = state().getYawRateDegreesPerSecond();
        double rate = angleUnit == AngleUnit.RADIANS
                ? Math.toRadians(degreesPerSecond)
                : degreesPerSecond;
        UnnormalizedAngleUnit unit = angleUnit.getUnnormalized();
        // Only yaw rate is simulated; pitch and roll rates stay zero.
        return new AngularVelocity(unit, 0.0f, 0.0f, (float) rate, System.nanoTime());
    }

    private Orientation canonicalOrientation() {
        return new Orientation(
                AxesReference.INTRINSIC,
                AxesOrder.ZYX,
                AngleUnit.DEGREES,
                (float) state().reportedYaw(),
                (float) state().getPitch(),
                (float) state().getRoll(),
                System.nanoTime());
    }
}
