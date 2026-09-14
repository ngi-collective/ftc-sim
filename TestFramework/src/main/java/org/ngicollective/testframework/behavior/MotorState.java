package org.ngicollective.testframework.behavior;

import com.qualcomm.robotcore.hardware.DcMotor;
import com.qualcomm.robotcore.hardware.DcMotorSimple;
import com.qualcomm.robotcore.util.Range;

/**
 * Simulated state of a single motor.
 *
 * <p>Two frames of reference are deliberately kept apart:</p>
 * <ul>
 *   <li><b>Commanded frame</b> &mdash; what the OpMode set ({@link #getCommandedPower()}), before
 *       {@link DcMotorSimple.Direction} is applied. This is what the SDK getters echo back.</li>
 *   <li><b>Physical frame</b> &mdash; {@link #getPhysicalPower()}, {@link #getPosition()} and
 *       {@link #getVelocity()}, with direction applied. This is the simulated shaft, and it is what
 *       a test should assert on when it cares which way the wheel actually turns.</li>
 * </ul>
 */
public class MotorState {

    /** Free speed of a REV HD Hex 40:1 with the SDK's 28-tick encoder, rounded; override per motor. */
    public static final double DEFAULT_MAX_TICKS_PER_SECOND = 2800.0;

    // Volatile throughout: a LinearOpMode mutates this from its own thread while the test thread
    // reads it, and a spinning reader has no other guarantee of ever seeing the write.

    private volatile double maxTicksPerSecond = DEFAULT_MAX_TICKS_PER_SECOND;
    private volatile double ticksPerRevolution;
    private volatile double commandedPower;
    private volatile DcMotorSimple.Direction direction = DcMotorSimple.Direction.FORWARD;
    private volatile DcMotor.RunMode mode = DcMotor.RunMode.RUN_WITHOUT_ENCODER;
    private volatile DcMotor.ZeroPowerBehavior zeroPowerBehavior = DcMotor.ZeroPowerBehavior.BRAKE;
    private volatile int targetPosition;
    private volatile int targetPositionTolerance = 5;
    private volatile boolean enabled = true;
    private volatile double position;
    private volatile double velocity;
    private volatile double currentAmps;
    private volatile double currentAlertAmps = Double.MAX_VALUE;
    private volatile int clippedCommandCount;
    private volatile double lastClippedCommand;

    /** Ticks per second the simulated shaft turns at full power. */
    public double getMaxTicksPerSecond() {
        return maxTicksPerSecond;
    }

    public void setMaxTicksPerSecond(double maxTicksPerSecond) {
        this.maxTicksPerSecond = maxTicksPerSecond;
    }

    /**
     * Encoder ticks per shaft revolution. Zero means "not specified": the angular-velocity overloads
     * of {@code DcMotorEx} cannot be served without it and will say so rather than guess.
     */
    public double getTicksPerRevolution() {
        return ticksPerRevolution;
    }

    public void setTicksPerRevolution(double ticksPerRevolution) {
        this.ticksPerRevolution = ticksPerRevolution;
    }

    /** Convenience for motors specified as free RPM at a known encoder resolution. */
    public void setMaxSpeed(double revolutionsPerMinute, double ticksPerRevolution) {
        setMaxTicksPerSecond(revolutionsPerMinute * ticksPerRevolution / 60.0);
        setTicksPerRevolution(ticksPerRevolution);
    }

    /** Power as the OpMode set it, before direction is applied; always within [-1, 1]. */
    public double getCommandedPower() {
        return commandedPower;
    }

    public void setCommandedPower(double commandedPower) {
        // Recorded before the clip, because after it the evidence is gone. The SDK clips silently
        // and so does this, so a joystick mixer that hands out 1.4 drives exactly like one that
        // hands out 1.0 while quietly losing the difference between its wheels -- a robot that
        // curves under hard turns and looks correct in every telemetry line. NaN counts as
        // out of range too: every comparison against it is false, so Range.clip passes it straight
        // through to the drive model.
        if (!(commandedPower >= -1.0 && commandedPower <= 1.0)) {
            clippedCommandCount++;
            lastClippedCommand = commandedPower;
        }
        this.commandedPower = Range.clip(commandedPower, -1.0, 1.0);
    }

    /** How many powers outside [-1, 1] this motor has been given since the last reset. */
    public int getClippedCommandCount() {
        return clippedCommandCount;
    }

    /** The most recent such power, unclipped, so a dashboard can show how far out of range it was. */
    public double getLastClippedCommand() {
        return lastClippedCommand;
    }

    /** Forgets the clipping seen so far, for a test or a dashboard run that starts counting fresh. */
    public void resetClipStats() {
        clippedCommandCount = 0;
        lastClippedCommand = 0.0;
    }

    /** Power actually applied to the simulated shaft, with direction applied. */
    public double getPhysicalPower() {
        return commandedPower * directionSign();
    }

    /** +1 when the motor is FORWARD, -1 when REVERSE. */
    public int directionSign() {
        return direction == DcMotorSimple.Direction.REVERSE ? -1 : 1;
    }

    public DcMotorSimple.Direction getDirection() {
        return direction;
    }

    public void setDirection(DcMotorSimple.Direction direction) {
        this.direction = direction;
    }

    public DcMotor.RunMode getMode() {
        return mode;
    }

    public void setMode(DcMotor.RunMode mode) {
        this.mode = mode;
    }

    public DcMotor.ZeroPowerBehavior getZeroPowerBehavior() {
        return zeroPowerBehavior;
    }

    public void setZeroPowerBehavior(DcMotor.ZeroPowerBehavior zeroPowerBehavior) {
        this.zeroPowerBehavior = zeroPowerBehavior;
    }

    /** Target encoder position in the commanded frame, as {@code DcMotor.setTargetPosition} takes it. */
    public int getTargetPosition() {
        return targetPosition;
    }

    public void setTargetPosition(int targetPosition) {
        this.targetPosition = targetPosition;
    }

    public int getTargetPositionTolerance() {
        return targetPositionTolerance;
    }

    public void setTargetPositionTolerance(int targetPositionTolerance) {
        this.targetPositionTolerance = targetPositionTolerance;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /** Simulated shaft position in ticks, physical frame. Fractional so slow speeds accumulate. */
    public double getPosition() {
        return position;
    }

    public void setPosition(double position) {
        this.position = position;
    }

    /** Simulated shaft speed in ticks/second, physical frame. */
    public double getVelocity() {
        return velocity;
    }

    public void setVelocity(double velocity) {
        this.velocity = velocity;
    }

    public double getCurrentAmps() {
        return currentAmps;
    }

    public void setCurrentAmps(double currentAmps) {
        this.currentAmps = currentAmps;
    }

    public double getCurrentAlertAmps() {
        return currentAlertAmps;
    }

    public void setCurrentAlertAmps(double currentAlertAmps) {
        this.currentAlertAmps = currentAlertAmps;
    }

    /** Target position expressed in the physical frame, for behaviors to steer toward. */
    public double physicalTargetPosition() {
        return targetPosition * directionSign();
    }

    /**
     * Shaft speed the motor is being asked for right now, in ticks/second, physical frame. Behaviors
     * decide how fast the actual {@link #getVelocity()} converges on this.
     */
    public double demandedVelocity() {
        if (!enabled) {
            return 0.0;
        }
        if (mode == DcMotor.RunMode.STOP_AND_RESET_ENCODER) {
            return 0.0;
        }
        if (mode == DcMotor.RunMode.RUN_TO_POSITION) {
            double error = physicalTargetPosition() - position;
            if (Math.abs(error) <= targetPositionTolerance) {
                return 0.0;
            }
            // RUN_TO_POSITION ignores the sign of the commanded power; the controller picks direction.
            return Math.signum(error) * Math.abs(commandedPower) * maxTicksPerSecond;
        }
        return getPhysicalPower() * maxTicksPerSecond;
    }

    /** True while a RUN_TO_POSITION move is still outside its tolerance band. */
    public boolean isBusy() {
        return mode == DcMotor.RunMode.RUN_TO_POSITION
                && Math.abs(physicalTargetPosition() - position) > targetPositionTolerance;
    }
}
