package org.ngicollective.testframework.camera;

/**
 * A coloured ball on the field: BioBuzz POLLEN or NECTAR.
 *
 * <p>Tags alone would not exercise the vision OpModes this framework exists to test. Three of the
 * four in this repository use {@code ColorBlobLocatorProcessor} or
 * {@code PredominantColorProcessor}, which look for colour and never touch an AprilTag, so a
 * tags-only simulator would leave them untestable.</p>
 *
 * <p>A sphere, described by its centre and radius. The manual is explicit that the real ones "are
 * not perfectly spherical and may vary in size", which is a reason to treat the radius as nominal
 * rather than a reason to model lumpiness: a blob detector measures area and centroid, and both
 * survive the idealisation.</p>
 */
public final class GameElement {

    /** BioBuzz POLLEN: 2.8 in of yellow, forty per match. */
    public static final double POLLEN_DIAMETER_METRES = 0.071;

    /** BioBuzz NECTAR: 3.6 in, red or blue, eight of each. */
    public static final double NECTAR_DIAMETER_METRES = 0.091;

    private final String name;
    private final Vec3 centre;
    private final double radius;
    private final int red;
    private final int green;
    private final int blue;

    public GameElement(String name, Vec3 centre, double diameterMetres,
                       int red, int green, int blue) {
        if (!(diameterMetres > 0.0)) {
            throw new IllegalArgumentException(
                    name + " must have a positive diameter; got " + diameterMetres + "m");
        }
        this.name = name;
        this.centre = centre;
        this.radius = diameterMetres / 2.0;
        this.red = red;
        this.green = green;
        this.blue = blue;
    }

    /** A POLLEN ball, resting on the floor at the given place. */
    public static GameElement pollen(double xMetres, double yMetres) {
        return pollenAt(xMetres, yMetres, POLLEN_DIAMETER_METRES / 2.0);
    }

    /**
     * A POLLEN ball at a given height, for the ones that do not start on the floor.
     *
     * <p>A match stages four of them stacked inside each FLOWER, 21 in of cage above the tiles, so
     * "resting on the floor" is not the only legal starting place. The height is the ball's
     * <em>centre</em>, like every other position here.</p>
     */
    public static GameElement pollenAt(double xMetres, double yMetres, double zMetres) {
        return new GameElement("POLLEN", new Vec3(xMetres, yMetres, zMetres),
                POLLEN_DIAMETER_METRES, 240, 200, 30);
    }

    /** A red NECTAR ball, resting on the floor. */
    public static GameElement redNectar(double xMetres, double yMetres) {
        return redNectarAt(xMetres, yMetres, NECTAR_DIAMETER_METRES / 2.0);
    }

    /**
     * A red NECTAR ball at a given height, for the ones that do not start on the floor.
     *
     * <p>A match stages three NECTAR in each upward-facing CELL, four and a half feet up, so the
     * FLOWER's stacked POLLEN are not the only thing that starts off the tiles.</p>
     */
    public static GameElement redNectarAt(double xMetres, double yMetres, double zMetres) {
        return new GameElement("RED NECTAR", new Vec3(xMetres, yMetres, zMetres),
                NECTAR_DIAMETER_METRES, 200, 30, 40);
    }

    /** A blue NECTAR ball, resting on the floor. */
    public static GameElement blueNectar(double xMetres, double yMetres) {
        return blueNectarAt(xMetres, yMetres, NECTAR_DIAMETER_METRES / 2.0);
    }

    /** A blue NECTAR ball at a given height; see {@link #redNectarAt}. */
    public static GameElement blueNectarAt(double xMetres, double yMetres, double zMetres) {
        return new GameElement("BLUE NECTAR", new Vec3(xMetres, yMetres, zMetres),
                NECTAR_DIAMETER_METRES, 30, 70, 200);
    }

    /**
     * The same ball somewhere else &mdash; what a physics step produces.
     *
     * <p>A copy rather than a move, because a scene is immutable and a renderer half way through
     * drawing one must not have the floor shift under it. The name, radius and colour come along
     * unchanged: those are what the ball <em>is</em>, and only where it is has changed.</p>
     */
    public GameElement movedTo(Vec3 centre) {
        return new GameElement(name, centre, radius * 2.0, red, green, blue);
    }

    public String name() {
        return name;
    }

    public Vec3 centre() {
        return centre;
    }

    public double radiusMetres() {
        return radius;
    }

    public int red() {
        return red;
    }

    public int green() {
        return green;
    }

    public int blue() {
        return blue;
    }

    @Override
    public String toString() {
        return String.format("%s at %s", name, centre);
    }
}
