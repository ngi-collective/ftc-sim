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
        return new GameElement("POLLEN",
                new Vec3(xMetres, yMetres, POLLEN_DIAMETER_METRES / 2.0),
                POLLEN_DIAMETER_METRES, 240, 200, 30);
    }

    /** A red NECTAR ball, resting on the floor. */
    public static GameElement redNectar(double xMetres, double yMetres) {
        return new GameElement("RED NECTAR",
                new Vec3(xMetres, yMetres, NECTAR_DIAMETER_METRES / 2.0),
                NECTAR_DIAMETER_METRES, 200, 30, 40);
    }

    /** A blue NECTAR ball, resting on the floor. */
    public static GameElement blueNectar(double xMetres, double yMetres) {
        return new GameElement("BLUE NECTAR",
                new Vec3(xMetres, yMetres, NECTAR_DIAMETER_METRES / 2.0),
                NECTAR_DIAMETER_METRES, 30, 70, 200);
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
