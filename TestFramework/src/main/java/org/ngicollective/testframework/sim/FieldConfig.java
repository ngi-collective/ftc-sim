package org.ngicollective.testframework.sim;

import java.net.URL;
import java.nio.file.Path;

/**
 * The field the robot is simulated on: a square walled arena, tiled.
 *
 * <p>A competition field is fixed by the game manual, so {@link #standard()} is a real answer
 * rather than a placeholder. The file exists for the practice fields that are not: a half field, or
 * a school gym floor taped out two tiles short.</p>
 */
public final class FieldConfig {

    /** The only schema this build understands; bumping it is how a breaking change announces itself. */
    private static final int VERSION = 1;

    /** 12 ft square, the DECODE field: 6 tiles of 0.6096 m per side. */
    private static final double STANDARD_SIZE_METRES = 3.5814;

    /** Height of the perimeter wall the robot can be pushed against. */
    private static final double STANDARD_WALL_HEIGHT_METRES = 0.312;

    /** One foam tile, 2 ft nominal. The grid a driver navigates by. */
    private static final double STANDARD_TILE_METRES = 0.6096;

    private final double size;
    private final double wallHeight;
    private final double tile;

    private FieldConfig(double sizeMetres, double wallHeightMetres, double tileMetres) {
        this.size = sizeMetres;
        this.wallHeight = wallHeightMetres;
        this.tile = tileMetres;
    }

    /** Reads a field description, failing with the file and field name if anything is missing. */
    public static FieldConfig load(Path file) {
        return from(ConfigJson.read(file, VERSION));
    }

    /** The same, from a classpath resource, for a build that runs from inside an APK. */
    static FieldConfig load(URL resource) {
        return from(ConfigJson.read(resource, VERSION));
    }

    private static FieldConfig from(ConfigJson json) {
        return new FieldConfig(
                json.positive("sizeMetres"),
                json.positive("wallHeightMetres"),
                json.positive("tileMetres"));
    }

    /** The competition field, for anything that has no reason to care about a practice setup. */
    public static FieldConfig standard() {
        return new FieldConfig(
                STANDARD_SIZE_METRES, STANDARD_WALL_HEIGHT_METRES, STANDARD_TILE_METRES);
    }

    /** Wall to wall, in metres. */
    public double sizeMetres() {
        return size;
    }

    public double wallHeightMetres() {
        return wallHeight;
    }

    /** One tile edge, in metres. */
    public double tileMetres() {
        return tile;
    }

    /**
     * Distance from field centre to a wall &mdash; the bound the drive model clamps against, and
     * the reason the origin is the centre rather than a corner: a robot on the red side and the
     * same robot on the blue side are then mirror images, not two different arithmetic problems.
     */
    public double halfExtentMetres() {
        return size / 2.0;
    }
}
