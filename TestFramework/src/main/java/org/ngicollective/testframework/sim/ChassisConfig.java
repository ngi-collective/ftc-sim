package org.ngicollective.testframework.sim;

/**
 * The box the robot occupies and what it weighs: its footprint, how tall the frame is, how high
 * the deck sits, and its mass.
 *
 * <p>The footprint is what the chassis collides with the field with, and the heights are what the
 * browser extrudes. One set of numbers for both, so the robot a driver sees on screen is the robot
 * that hit the wall.</p>
 *
 * <p>The mass is what makes a collision have a consequence: a rigid-body chassis is accelerated by
 * wheel forces and decelerated by whatever it runs into, and both of those divide by this
 * number.</p>
 */
public final class ChassisConfig {

    private final double width;
    private final double length;
    private final double height;
    private final double deckHeight;
    private final double mass;

    ChassisConfig(double widthMetres, double lengthMetres, double heightMetres,
                  double deckHeightMetres, double massKilograms) {
        this.width = widthMetres;
        this.length = lengthMetres;
        this.height = heightMetres;
        this.deckHeight = deckHeightMetres;
        this.mass = massKilograms;
    }

    static ChassisConfig from(ConfigJson json) {
        return new ChassisConfig(
                json.positive("widthMetres"),
                json.positive("lengthMetres"),
                json.positive("heightMetres"),
                json.positive("deckHeightMetres"),
                json.positive("massKilograms"));
    }

    /** Side to side, across the robot's lateral axis. */
    public double widthMetres() {
        return width;
    }

    /** Nose to tail, along the robot's forward axis. */
    public double lengthMetres() {
        return length;
    }

    /** Thickness of the frame itself. */
    public double heightMetres() {
        return height;
    }

    /** Height of the deck above the floor, which is where anything mounted on top starts. */
    public double deckHeightMetres() {
        return deckHeight;
    }

    /**
     * What the whole robot weighs, in kilograms.
     *
     * <p>Configured rather than assumed because it is the one number that decides how a robot
     * behaves on contact: how fast it gets up to speed against a fixed grip limit, and how much a
     * collision costs it. The game manual caps a ROBOT at 42 lb (19 kg) and a typical one comes in
     * around 30 lb.</p>
     *
     * <p>The chassis's rotational inertia is <em>not</em> configured: it is derived from this mass
     * and the footprint as a uniform box. A separately configured inertia can contradict the
     * dimensions beside it, and nobody measures one anyway.</p>
     */
    public double massKilograms() {
        return mass;
    }
}
