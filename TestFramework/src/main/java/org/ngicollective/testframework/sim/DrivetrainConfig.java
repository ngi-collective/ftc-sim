package org.ngicollective.testframework.sim;

/**
 * The numbers that turn wheel speeds into chassis motion.
 *
 * <p>These are measurements of a physical robot, not tuning knobs: change one because the robot
 * changed, not because the simulation is not doing what you wanted.</p>
 */
public final class DrivetrainConfig {

    private final String type;
    private final double wheelRadius;
    private final double gearRatio;
    private final double trackWidth;
    private final double wheelBase;
    private final double strafeEfficiency;
    private final double gripCoefficient;

    DrivetrainConfig(String type, double wheelRadiusMetres, double gearRatio,
                     double trackWidthMetres, double wheelBaseMetres, double strafeEfficiency,
                     double gripCoefficient) {
        this.type = type;
        this.wheelRadius = wheelRadiusMetres;
        this.gearRatio = gearRatio;
        this.trackWidth = trackWidthMetres;
        this.wheelBase = wheelBaseMetres;
        this.strafeEfficiency = strafeEfficiency;
        this.gripCoefficient = gripCoefficient;
    }

    static DrivetrainConfig from(ConfigJson json) {
        return new DrivetrainConfig(
                json.string("type"),
                json.positive("wheelRadiusMetres"),
                json.positive("gearRatio"),
                json.positive("trackWidthMetres"),
                json.positive("wheelBaseMetres"),
                json.fraction("strafeEfficiency"),
                json.positive("gripCoefficient"));
    }

    /** What kind of drivetrain this is; {@link DriveModel} only knows how to drive "mecanum". */
    public String type() {
        return type;
    }

    /** Wheel radius in metres: the lever that converts shaft revolutions into floor travelled. */
    public double wheelRadiusMetres() {
        return wheelRadius;
    }

    /** Wheel revolutions per motor-shaft revolution, for a drivetrain geared after the encoder. */
    public double gearRatio() {
        return gearRatio;
    }

    /** Distance between the left and right wheel centres. */
    public double trackWidthMetres() {
        return trackWidth;
    }

    /** Distance between the front and rear wheel centres. */
    public double wheelBaseMetres() {
        return wheelBase;
    }

    /**
     * How much of the sideways motion the kinematics predict actually happens.
     *
     * <p>Mecanum rollers slip and scrub, so a real robot strafes noticeably slower than it drives
     * for the same wheel speeds. Without this factor, autonomous code tuned against the simulator
     * would over-travel sideways on the real field.</p>
     */
    public double strafeEfficiency() {
        return strafeEfficiency;
    }

    /**
     * How hard a wheel can push before it slips, as a coefficient of friction against the tiles.
     *
     * <p>What bounds the robot's acceleration. A rigid-body chassis is driven by forces at the
     * four contact patches, and a patch cannot deliver more than {@code gripCoefficient} times the
     * weight it carries &mdash; past that a real wheel spins rather than pushing harder, which is
     * why a robot on a dusty field accelerates worse without any motor changing.</p>
     *
     * <p>Not a fraction: a coefficient may exceed one, and soft rubber on foam tile does.
     * Distinct from {@link #strafeEfficiency}, which is about rollers scrubbing at 45 degrees
     * while gripping perfectly well.</p>
     */
    public double gripCoefficient() {
        return gripCoefficient;
    }
}
