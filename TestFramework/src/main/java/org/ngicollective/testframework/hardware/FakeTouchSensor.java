package org.ngicollective.testframework.hardware;

import com.qualcomm.robotcore.hardware.TouchSensor;

import org.ngicollective.testframework.behavior.Behavior;
import org.ngicollective.testframework.behavior.TouchState;

/** A simulated {@link TouchSensor}, pressed when the simulated field puts something in its volume. */
public class FakeTouchSensor extends FakeDevice<TouchState> implements TouchSensor {

    FakeTouchSensor(String configuredName, Behavior<TouchState> behavior) {
        super(configuredName, new TouchState(), behavior);
    }

    @Override
    public String getDeviceName() {
        return "Simulated Touch Sensor";
    }

    @Override
    public void resetDeviceConfigurationForOpMode() {
        // A switch has nothing to configure; whether it is pressed is the world's business.
    }

    /**
     * The SDK allows an analogue force reading, but the switches on a competition robot are
     * digital, so this is the 0-or-1 that a REV touch sensor reports.
     */
    @Override
    public double getValue() {
        return isPressed() ? 1.0 : 0.0;
    }

    @Override
    public boolean isPressed() {
        return state().isPressed();
    }
}
