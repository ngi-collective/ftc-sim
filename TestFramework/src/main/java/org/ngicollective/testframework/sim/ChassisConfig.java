package org.ngicollective.testframework.sim;

/**
 * The box the robot occupies: its footprint, how tall the frame is, and how high the deck sits.
 *
 * <p>The footprint is what the drive model collides with the field walls, and the heights are what
 * the browser extrudes. One set of numbers for both, so the robot a driver sees on screen is the
 * robot that hit the wall.</p>
 */
public final class ChassisConfig {

    private final double width;
    private final double length;
    private final double height;
    private final double deckHeight;

    ChassisConfig(double widthMetres, double lengthMetres, double heightMetres,
                  double deckHeightMetres) {
        this.width = widthMetres;
        this.length = lengthMetres;
        this.height = heightMetres;
        this.deckHeight = deckHeightMetres;
    }

    static ChassisConfig from(ConfigJson json) {
        return new ChassisConfig(
                json.positive("widthMetres"),
                json.positive("lengthMetres"),
                json.positive("heightMetres"),
                json.positive("deckHeightMetres"));
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
}
