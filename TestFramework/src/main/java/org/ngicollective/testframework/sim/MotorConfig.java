package org.ngicollective.testframework.sim;

/**
 * One motor as the configuration file describes it: what it does on the robot, which way round it
 * is bolted to it, and what its shaft is capable of.
 *
 * <p>There is deliberately no {@code direction} here, and {@link #mirrored()} is not a rename of
 * one. See its javadoc: the two live in different layers, and the simulator exists partly to catch
 * them disagreeing.</p>
 */
public final class MotorConfig {

    private final String role;
    private final boolean mirrored;
    private final double rpm;
    private final double ticksPerRevolution;

    MotorConfig(String role, boolean mirrored, double rpm, double ticksPerRevolution) {
        this.role = role;
        this.mirrored = mirrored;
        this.rpm = rpm;
        this.ticksPerRevolution = ticksPerRevolution;
    }

    static MotorConfig from(ConfigJson json) {
        return new MotorConfig(
                json.string("role"),
                json.bool("mirrored"),
                json.positive("rpm"),
                json.positive("ticksPerRevolution"));
    }

    /**
     * What this motor is for. {@link DriveModel} recognises {@code frontLeft}, {@code frontRight},
     * {@code backLeft} and {@code backRight}; anything else is a mechanism the drive model leaves
     * alone.
     */
    public String role() {
        return role;
    }

    /**
     * Whether this motor is bolted to the chassis facing the other way, so a positive shaft
     * rotation drives its wheel backwards. True for the left side of a normally-built mecanum
     * chassis, whose motors are mirror images of the right side's.
     *
     * <p>This is <em>not</em> {@link com.qualcomm.robotcore.hardware.DcMotorSimple.Direction} under
     * another name, and merging the two would be a mistake:</p>
     * <ul>
     *   <li>{@code mirrored} is a fact about the physical robot. It is true because of how the
     *       gearbox is mounted, and it stays true no matter what software does.</li>
     *   <li>{@code Direction} is the compensation an OpMode applies for that fact at runtime
     *       &mdash; {@code DriveHardware} setting FL and BL to {@code REVERSE}.</li>
     * </ul>
     *
     * <p>They are two layers, and a robot only drives straight when they agree. Collapsing them
     * into one field would make that agreement automatic and unfalsifiable, which is exactly
     * backwards: a {@code REVERSE} that someone deleted is a real bug, and the point of simulating
     * the mounting separately is that the robot then visibly spins instead of driving.</p>
     */
    public boolean mirrored() {
        return mirrored;
    }

    /** Free speed of the output shaft in revolutions/minute. */
    public double rpm() {
        return rpm;
    }

    /** Encoder ticks per output-shaft revolution, at the gearbox this motor actually has. */
    public double ticksPerRevolution() {
        return ticksPerRevolution;
    }
}
