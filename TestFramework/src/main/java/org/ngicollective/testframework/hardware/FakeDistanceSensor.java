package org.ngicollective.testframework.hardware;

import com.qualcomm.robotcore.hardware.DistanceSensor;

import org.firstinspires.ftc.robotcore.external.navigation.DistanceUnit;
import org.ngicollective.testframework.behavior.Behavior;
import org.ngicollective.testframework.behavior.DistanceState;

/** A simulated {@link DistanceSensor}, ranging along a ray cast through the simulated field. */
public class FakeDistanceSensor extends FakeDevice<DistanceState> implements DistanceSensor {

    FakeDistanceSensor(String configuredName, Behavior<DistanceState> behavior) {
        super(configuredName, new DistanceState(), behavior);
    }

    @Override
    public String getDeviceName() {
        return "Simulated Distance Sensor";
    }

    @Override
    public void resetDeviceConfigurationForOpMode() {
        // Nothing an OpMode can configure: range and mounting come from the robot config.
    }

    /**
     * Converts through the SDK's own {@link DistanceUnit} arithmetic so a test sees the numbers the
     * real driver would produce.
     *
     * <p>{@code NaN} is returned untouched. An out-of-range reading has to stay recognisable as
     * one: an OpMode that guards with {@code Double.isNaN} would otherwise read a converted zero as
     * a wall pressed against the sensor and stop dead.</p>
     */
    @Override
    public double getDistance(DistanceUnit unit) {
        double metres = state().reportedMetres();
        return Double.isNaN(metres) ? Double.NaN : unit.fromMeters(metres);
    }
}
