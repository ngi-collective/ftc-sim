package org.ngicollective.testframework.behavior;

import com.qualcomm.robotcore.hardware.DcMotorSimple;
import com.qualcomm.robotcore.util.Range;

/**
 * Simulated state of a continuous-rotation servo.
 *
 * <p>Like a motor, a CR servo has no feedback: {@code CRServo.getPower()} echoes the last commanded
 * value. The two frames are kept apart on purpose &mdash; {@link #demandedPower()} is what the
 * shaft is being asked to do and {@link #getPhysicalPower()} is what it is really doing. A jammed
 * intake is exactly the case where those disagree, and an OpMode has no way to tell.</p>
 */
public class CRServoState {

    private volatile double commandedPower;
    private volatile DcMotorSimple.Direction direction = DcMotorSimple.Direction.FORWARD;
    private volatile double physicalPower;

    /** Power as the OpMode set it, before direction is applied; always within [-1, 1]. */
    public double getCommandedPower() {
        return commandedPower;
    }

    public void setCommandedPower(double commandedPower) {
        this.commandedPower = Range.clip(commandedPower, -1.0, 1.0);
    }

    public DcMotorSimple.Direction getDirection() {
        return direction;
    }

    public void setDirection(DcMotorSimple.Direction direction) {
        this.direction = direction;
    }

    /** What the shaft is being asked to do: the commanded power with direction applied. */
    public double demandedPower() {
        return direction == DcMotorSimple.Direction.REVERSE ? -commandedPower : commandedPower;
    }

    /**
     * Power the simulated shaft is actually turning at, in the same [-1, 1] scale.
     *
     * <p>Only a behavior writes this. The intake model reads it rather than the commanded power, so
     * a fault injected here stops balls moving without the OpMode noticing.</p>
     */
    public double getPhysicalPower() {
        return physicalPower;
    }

    public void setPhysicalPower(double physicalPower) {
        this.physicalPower = Range.clip(physicalPower, -1.0, 1.0);
    }
}
