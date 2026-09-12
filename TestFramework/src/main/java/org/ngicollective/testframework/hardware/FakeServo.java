package org.ngicollective.testframework.hardware;

import com.qualcomm.robotcore.hardware.Servo;
import com.qualcomm.robotcore.hardware.ServoController;

import org.ngicollective.testframework.behavior.Behavior;
import org.ngicollective.testframework.behavior.ServoState;

/** A simulated {@link Servo}. */
public class FakeServo extends FakeDevice<ServoState> implements Servo {

    private final int portNumber;

    FakeServo(String configuredName, int portNumber, Behavior<ServoState> behavior) {
        super(configuredName, new ServoState(), behavior);
        this.portNumber = portNumber;
    }

    @Override
    public String getDeviceName() {
        return "Simulated Servo";
    }

    @Override
    public void resetDeviceConfigurationForOpMode() {
        state().setDirection(Servo.Direction.FORWARD);
        state().setScaleRange(0.0, 1.0);
    }

    @Override
    public ServoController getController() {
        throw new UnsupportedOperationException(
                "A simulated servo is not attached to a ServoController.");
    }

    @Override
    public int getPortNumber() {
        return portNumber;
    }

    @Override
    public void setDirection(Direction direction) {
        state().setDirection(direction);
    }

    @Override
    public Direction getDirection() {
        return state().getDirection();
    }

    @Override
    public void setPosition(double position) {
        state().setCommandedPosition(position);
    }

    /** As on real hardware, this echoes the last commanded position, not where the horn is. */
    @Override
    public double getPosition() {
        return state().getCommandedPosition();
    }

    @Override
    public void scaleRange(double min, double max) {
        if (min >= max) {
            throw new IllegalArgumentException("min must be less than max");
        }
        state().setScaleRange(min, max);
    }
}
