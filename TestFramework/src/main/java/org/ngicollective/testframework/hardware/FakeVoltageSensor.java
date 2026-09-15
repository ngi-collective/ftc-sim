package org.ngicollective.testframework.hardware;

import com.qualcomm.robotcore.hardware.VoltageSensor;

import org.ngicollective.testframework.behavior.Behavior;
import org.ngicollective.testframework.behavior.VoltageState;

/** A simulated {@link VoltageSensor}, reading the pack voltage the simulation is modelling. */
public class FakeVoltageSensor extends FakeDevice<VoltageState> implements VoltageSensor {

    FakeVoltageSensor(String configuredName, Behavior<VoltageState> behavior) {
        super(configuredName, new VoltageState(), behavior);
    }

    @Override
    public String getDeviceName() {
        return "Simulated Voltage Sensor";
    }

    @Override
    public void resetDeviceConfigurationForOpMode() {
        // The battery does not care that an OpMode started.
    }

    @Override
    public double getVoltage() {
        return state().reportedVolts();
    }
}
