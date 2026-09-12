package org.ngicollective.testframework.behavior;

import com.qualcomm.robotcore.hardware.Servo;
import com.qualcomm.robotcore.util.Range;

/**
 * Simulated state of a servo.
 *
 * <p>A real servo has no position feedback: {@code Servo.getPosition()} echoes the last commanded
 * value. The simulated horn position lives here in {@link #getPosition()} instead, so a test can
 * assert on where the servo actually got to.</p>
 */
public class ServoState {

    private volatile double commandedPosition;
    private volatile double position;
    private volatile Servo.Direction direction = Servo.Direction.FORWARD;
    private volatile double scaleMin = 0.0;
    private volatile double scaleMax = 1.0;

    /** Position as the OpMode set it, before direction and scaling. */
    public double getCommandedPosition() {
        return commandedPosition;
    }

    public void setCommandedPosition(double commandedPosition) {
        this.commandedPosition = Range.clip(commandedPosition, 0.0, 1.0);
    }

    /** Simulated horn position in the servo's full 0..1 travel, with direction and scaling applied. */
    public double getPosition() {
        return position;
    }

    public void setPosition(double position) {
        this.position = Range.clip(position, 0.0, 1.0);
    }

    public Servo.Direction getDirection() {
        return direction;
    }

    public void setDirection(Servo.Direction direction) {
        this.direction = direction;
    }

    public double getScaleMin() {
        return scaleMin;
    }

    public double getScaleMax() {
        return scaleMax;
    }

    public void setScaleRange(double min, double max) {
        this.scaleMin = Range.clip(min, 0.0, 1.0);
        this.scaleMax = Range.clip(max, 0.0, 1.0);
    }

    /** Where the horn is being asked to go, in the full 0..1 travel, direction and scaling applied. */
    public double demandedPosition() {
        double scaled = scaleMin + commandedPosition * (scaleMax - scaleMin);
        return direction == Servo.Direction.REVERSE ? 1.0 - scaled : scaled;
    }
}
