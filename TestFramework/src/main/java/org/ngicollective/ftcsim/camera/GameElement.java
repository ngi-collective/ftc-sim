package org.ngicollective.ftcsim.camera;

/**
 * A coloured ball on the field. A season names its own: BioBuzz's are in {@code BioBuzzElements}.
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
