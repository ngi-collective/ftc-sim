package org.ngicollective.testframework.hardware;

import com.qualcomm.robotcore.hardware.CRServo;
import com.qualcomm.robotcore.hardware.DcMotorSimple;
import com.qualcomm.robotcore.hardware.ServoController;

import org.ngicollective.testframework.behavior.Behavior;
import org.ngicollective.testframework.behavior.CRServoState;

/**
 * A simulated {@link CRServo}, the motor an intake or sweeper runs on.
 *
 * <p>Direction lives in {@link CRServoState#demandedPower()} rather than here, because
 * {@code CRServo} inherits it from {@link DcMotorSimple} and every consumer of the simulated shaft
 * &mdash; the ball sweep model included &mdash; needs the signed power, not the raw command.</p>
 */
public class FakeCRServo extends FakeDevice<CRServoState> implements CRServo {

    private final int portNumber;

    FakeCRServo(String configuredName, int portNumber, Behavior<CRServoState> behavior) {
        super(configuredName, new CRServoState(), behavior);
        this.portNumber = portNumber;
    }

    @Override
    public String getDeviceName() {
        return "Simulated CR Servo";
    }

    @Override
    public void resetDeviceConfigurationForOpMode() {
        state().setDirection(DcMotorSimple.Direction.FORWARD);
        state().setCommandedPower(0.0);
    }

    @Override
    public ServoController getController() {
        throw new UnsupportedOperationException(
                "A simulated CR servo is not attached to a ServoController.");
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
    public void setPower(double power) {
        state().setCommandedPower(power);
    }

    /** As on real hardware, this echoes the last commanded power, not what the shaft is doing. */
    @Override
    public double getPower() {
        return state().getCommandedPower();
    }
}
