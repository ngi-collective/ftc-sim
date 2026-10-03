package org.ngicollective.testframework.season;

import org.ngicollective.testframework.camera.GameElement;
import org.ngicollective.testframework.camera.Vec3;

/** BioBuzz's scoring elements: POLLEN, and NECTAR in each alliance's colour. */
public final class BioBuzzElements {

    /** BioBuzz POLLEN: 2.8 in of yellow, forty per match. */
    public static final double POLLEN_DIAMETER_METRES = 0.071;

    /** BioBuzz NECTAR: 3.6 in, red or blue, eight of each. */
    public static final double NECTAR_DIAMETER_METRES = 0.091;

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

    private BioBuzzElements() {
    }
}
