package org.ngicollective.testframework.hardware;

import com.qualcomm.robotcore.hardware.DcMotorController;
import com.qualcomm.robotcore.hardware.DcMotorEx;
import com.qualcomm.robotcore.hardware.PIDCoefficients;
import com.qualcomm.robotcore.hardware.PIDFCoefficients;
import com.qualcomm.robotcore.hardware.configuration.typecontainers.MotorConfigurationType;

import org.firstinspires.ftc.robotcore.external.navigation.AngleUnit;
import org.firstinspires.ftc.robotcore.external.navigation.CurrentUnit;
import org.ngicollective.testframework.behavior.Behavior;
import org.ngicollective.testframework.behavior.MotorState;

import java.util.EnumMap;
import java.util.Map;

/**
 * A simulated {@link DcMotorEx}. Everything the SDK interface exposes is served from
 * {@link MotorState}; nothing talks to a Lynx module.
 */
public class FakeDcMotorEx extends FakeDevice<MotorState> implements DcMotorEx {

    private final int portNumber;
    private final Map<RunMode, PIDFCoefficients> coefficients = new EnumMap<>(RunMode.class);

    FakeDcMotorEx(String configuredName, int portNumber, Behavior<MotorState> behavior) {
        super(configuredName, new MotorState(), behavior);
        this.portNumber = portNumber;
    }

    @Override
    public String getDeviceName() {
        return "Simulated DcMotorEx";
    }

    @Override
    public void resetDeviceConfigurationForOpMode() {
        state().setDirection(Direction.FORWARD);
        state().setMode(RunMode.RUN_WITHOUT_ENCODER);
        state().setZeroPowerBehavior(ZeroPowerBehavior.BRAKE);
        state().setCommandedPower(0.0);
        state().setEnabled(true);
        coefficients.clear();
    }

    //--------------------------------------------------------------------------------------------
    // DcMotorSimple
    //--------------------------------------------------------------------------------------------

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

    @Override
    public double getPower() {
        return state().getCommandedPower();
    }

    //--------------------------------------------------------------------------------------------
    // DcMotor
    //--------------------------------------------------------------------------------------------

    @Override
    public MotorConfigurationType getMotorType() {
        throw new UnsupportedOperationException(
                "A simulated motor has no MotorConfigurationType (building one needs the SDK's "
                        + "annotation-scanning ConfigurationTypeManager, which requires an Android "
                        + "runtime). Set speed with MotorState.setMaxTicksPerSecond/setMaxSpeed and "
                        + "read it from FakeDcMotorEx.state() instead.");
    }

    @Override
    public void setMotorType(MotorConfigurationType motorType) {
        throw new UnsupportedOperationException(
                "A simulated motor has no MotorConfigurationType; use "
                        + "FakeDcMotorEx.state().setMaxSpeed(rpm, ticksPerRevolution) instead.");
    }

    @Override
    public DcMotorController getController() {
        throw new UnsupportedOperationException(
                "A simulated motor is not attached to a DcMotorController.");
    }

    @Override
    public int getPortNumber() {
        return portNumber;
    }

    @Override
    public void setZeroPowerBehavior(ZeroPowerBehavior zeroPowerBehavior) {
        state().setZeroPowerBehavior(zeroPowerBehavior);
    }

    @Override
    public ZeroPowerBehavior getZeroPowerBehavior() {
        return state().getZeroPowerBehavior();
    }

    @Override
    public boolean getPowerFloat() {
        return state().getZeroPowerBehavior() == ZeroPowerBehavior.FLOAT
                && state().getCommandedPower() == 0.0;
    }

    @Override
    public void setTargetPosition(int position) {
        state().setTargetPosition(position);
    }

    /** As the SDK documents it: latch the zero-power behavior to FLOAT, then cut power. */
    @Override
    @Deprecated
    public void setPowerFloat() {
        state().setZeroPowerBehavior(ZeroPowerBehavior.FLOAT);
        state().setCommandedPower(0.0);
    }

    @Override
    public int getTargetPosition() {
        return state().getTargetPosition();
    }

    @Override
    public boolean isBusy() {
        return state().isBusy();
    }

    @Override
    public int getCurrentPosition() {
        return (int) Math.round(state().getPosition() * state().directionSign());
    }

    @Override
    public void setMode(RunMode mode) {
        state().setMode(mode);
        if (mode == RunMode.STOP_AND_RESET_ENCODER) {
            state().setPosition(0.0);
            state().setVelocity(0.0);
        }
    }

    @Override
    public RunMode getMode() {
        return state().getMode();
    }

    //--------------------------------------------------------------------------------------------
    // DcMotorEx
    //--------------------------------------------------------------------------------------------

    @Override
    public void setMotorEnable() {
        state().setEnabled(true);
    }

    @Override
    public void setMotorDisable() {
        state().setEnabled(false);
    }

    @Override
    public boolean isMotorEnabled() {
        return state().isEnabled();
    }

    @Override
    public void setVelocity(double ticksPerSecond) {
        state().setCommandedPower(ticksPerSecond / state().getMaxTicksPerSecond());
    }

    @Override
    public void setVelocity(double angularRate, AngleUnit unit) {
        setVelocity(toTicksPerSecond(angularRate, unit));
    }

    @Override
    public double getVelocity() {
        return state().getVelocity() * state().directionSign();
    }

    @Override
    public double getVelocity(AngleUnit unit) {
        double revolutionsPerSecond = getVelocity() / requireTicksPerRevolution();
        double degreesPerSecond = revolutionsPerSecond * 360.0;
        return unit == AngleUnit.RADIANS ? Math.toRadians(degreesPerSecond) : degreesPerSecond;
    }

    @Override
    public void setPIDCoefficients(RunMode mode, PIDCoefficients pidCoefficients) {
        coefficients.put(mode, new PIDFCoefficients(pidCoefficients));
    }

    @Override
    public void setVelocityPIDFCoefficients(double p, double i, double d, double f) {
        coefficients.put(RunMode.RUN_USING_ENCODER, new PIDFCoefficients(p, i, d, f));
    }

    @Override
    public void setPositionPIDFCoefficients(double p) {
        coefficients.put(RunMode.RUN_TO_POSITION, new PIDFCoefficients(p, 0.0, 0.0, 0.0));
    }

    @Override
    public void setPIDFCoefficients(RunMode mode, PIDFCoefficients pidfCoefficients) {
        coefficients.put(mode, new PIDFCoefficients(pidfCoefficients));
    }

    @Override
    public PIDCoefficients getPIDCoefficients(RunMode mode) {
        PIDFCoefficients stored = getPIDFCoefficients(mode);
        return new PIDCoefficients(stored.p, stored.i, stored.d);
    }

    @Override
    public PIDFCoefficients getPIDFCoefficients(RunMode mode) {
        PIDFCoefficients stored = coefficients.get(mode);
        // A real motor reports the firmware defaults for its motor type; a simulated one has no
        // motor type, so un-set coefficients read back as zero rather than as an invented default.
        return stored == null ? new PIDFCoefficients() : new PIDFCoefficients(stored);
    }

    @Override
    public void setTargetPositionTolerance(int tolerance) {
        state().setTargetPositionTolerance(tolerance);
    }

    @Override
    public int getTargetPositionTolerance() {
        return state().getTargetPositionTolerance();
    }

    @Override
    public double getCurrent(CurrentUnit unit) {
        return unit.convert(state().getCurrentAmps(), CurrentUnit.AMPS);
    }

    @Override
    public double getCurrentAlert(CurrentUnit unit) {
        return unit.convert(state().getCurrentAlertAmps(), CurrentUnit.AMPS);
    }

    @Override
    public void setCurrentAlert(double current, CurrentUnit unit) {
        state().setCurrentAlertAmps(CurrentUnit.AMPS.convert(current, unit));
    }

    @Override
    public boolean isOverCurrent() {
        return state().getCurrentAmps() >= state().getCurrentAlertAmps();
    }

    private double toTicksPerSecond(double angularRate, AngleUnit unit) {
        double degreesPerSecond =
                unit == AngleUnit.RADIANS ? Math.toDegrees(angularRate) : angularRate;
        return degreesPerSecond / 360.0 * requireTicksPerRevolution();
    }

    private double requireTicksPerRevolution() {
        double ticksPerRevolution = state().getTicksPerRevolution();
        if (ticksPerRevolution <= 0.0) {
            throw new IllegalStateException(
                    "Angular velocity on \"" + configuredName() + "\" needs the encoder resolution. "
                            + "Call state().setTicksPerRevolution(...) (or setMaxSpeed(rpm, "
                            + "ticksPerRevolution)) on this fake motor first.");
        }
        return ticksPerRevolution;
    }
}
