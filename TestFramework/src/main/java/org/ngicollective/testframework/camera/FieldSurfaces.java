package org.ngicollective.testframework.camera;

import org.ngicollective.testframework.sim.FieldConfig;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The field itself, as flat coloured quads: the tiled floor and the four perimeter walls.
 *
 * <p>This exists so the two views of one world agree. The Dashboard's field view draws the tiles,
 * the perimeter and the alliance ends; a camera that saw none of that could not be checked against
 * it, and "the camera sees something the field view does not" is the class of silent disagreement
 * this geometry removes. The tiling is therefore not decorative: it is
 * {@code driver-hub-dashboard/src/scene/Field.tsx}'s tiling, at the same pitch, clipped to the
 * perimeter the same way, so a tile counted in one view is the same tile in the other.</p>
 *
 * <h2>Geometry and colour only</h2>
 *
 * <p>There is no lighting anywhere in this renderer, so a surface is one flat fill by design and
 * not by omission. Nothing under test measures shading: a detector thresholds, and a threshold
 * asks only whether the floor is distinguishable from a tag's black square and a ball's colour.
 * Adding a light would add a second thing for the two views to disagree about.</p>
 *
 * <h2>Which end is red</h2>
 *
 * <p>Red is the end at <b>-X</b> and blue the end at <b>+X</b>, with the audience at -Y. The
 * authority is the field CAD, via
 * {@link org.ngicollective.testframework.season.BioBuzzField}'s conversion of it: the alliances
 * are on the X axis and the audience watches from -Y. The tags placed from that CAD and the walls
 * drawn here therefore describe one field rather than two rotated a quarter turn from each
 * other.</p>
 */
public final class FieldSurfaces {

    /**
     * How much brighter than {@code Field.tsx} a tile is drawn, so a threshold still works.
     *
     * <p>The dashboard's tiles are a dark theme under three.js lights. Filled flat and unlit, its
     * {@code #22303c} lands at a luminance of 45 &mdash; darker than the 60 a test and a detector
     * use to mean "this is a tag's black square", and far darker than real foam tiles, which is
     * what the grey backdrop beyond the perimeter has always stood in for. The gain lifts the
     * lighter tile to about that backdrop's brightness and keeps the dashboard's hue and its
     * light-to-dark ratio exactly. The walls are deliberately left alone: their alliance colours
     * are already saturated and bright, and a gain on them would clip red to 255 and shift the
     * hue.</p>
     */
    private static final double UNLIT_GAIN = 2.4;

    /** {@code Field.tsx}'s two tile colours, at the exposure a camera would report. */
    private static final int LIGHT_TILE = atCameraExposure(0x22303C);
    private static final int DARK_TILE = atCameraExposure(0x18242E);

    /** {@code Field.tsx}'s alliance and neutral perimeter colours, unchanged. */
    private static final int RED_WALL = 0xC0303F;
    private static final int BLUE_WALL = 0x2B62C4;
    private static final int NEUTRAL_WALL = 0x48586A;

    private FieldSurfaces() {
    }

    /**
     * Every surface of one field: the floor's tiles first, then the four walls.
     *
     * <p>Built once per scene rather than per frame, because the field does not move. The order
     * carries no meaning &mdash; what is in front of what is decided when the scene sorts its
     * drawables by depth.</p>
     */
    public static List<Surface> of(FieldConfig field) {
        double half = field.halfExtentMetres();
        double pitch = field.tileMetres();
        double height = field.wallHeightMetres();

        List<Surface> surfaces = new ArrayList<>();
        addTiles(surfaces, field.sizeMetres(), pitch, half);

        // Red at -X and blue at +X, per the CAD: see this class's javadoc. Only the inner face of
        // each wall is modelled, because a camera inside the perimeter never sees the other side
        // of one.
        surfaces.add(allianceWall(-half, half, height, RED_WALL));
        surfaces.add(allianceWall(half, half, height, BLUE_WALL));
        surfaces.add(audienceWall(-half, half, height));
        surfaces.add(audienceWall(half, half, height));
        return Collections.unmodifiableList(surfaces);
    }

    /**
     * The floor, cut into tiles at the true pitch and clipped to the perimeter.
     *
     * <p>Deliberately the same arithmetic as {@code Field.tsx}'s {@code tiles()}: the perimeter is
     * short of a whole number of tiles, so the edge row is a partial tile in both views rather
     * than a stretched one. Rows are walked along the dashboard's scene z, which is -Y in the
     * field frame, because the light/dark parity comes off the row index &mdash; walk them the
     * other way on an even-sized field and every tile swaps colour.</p>
     */
    private static void addTiles(List<Surface> surfaces, double size, double pitch, double half) {
        int count = (int) Math.ceil(size / pitch);
        double start = -(count * pitch) / 2.0;

        for (int column = 0; column < count; column++) {
            double x0 = Math.max(start + column * pitch, -half);
            double x1 = Math.min(start + (column + 1) * pitch, half);
            if (x1 - x0 <= 1e-6) {
                continue;
            }
            for (int row = 0; row < count; row++) {
                double z0 = Math.max(start + row * pitch, -half);
                double z1 = Math.min(start + (row + 1) * pitch, half);
                if (z1 - z0 <= 1e-6) {
                    continue;
                }
                int colour = (column + row) % 2 == 0 ? LIGHT_TILE : DARK_TILE;
                surfaces.add(quad(
                        new Vec3(x0, -z1, 0.0), new Vec3(x1, -z1, 0.0),
                        new Vec3(x1, -z0, 0.0), new Vec3(x0, -z0, 0.0), colour));
            }
        }
    }

    /**
     * One alliance's end wall, standing on the floor's edge at {@code x} and spanning the field
     * in y. The drive teams stand behind these.
     */
    private static Surface allianceWall(double x, double half, double height, int colour) {
        return quad(
                new Vec3(x, -half, 0.0), new Vec3(x, half, 0.0),
                new Vec3(x, half, height), new Vec3(x, -half, height), colour);
    }

    /** A side wall, at {@code y} and spanning the field in x: the audience side and its mirror. */
    private static Surface audienceWall(double y, double half, double height) {
        return quad(
                new Vec3(-half, y, 0.0), new Vec3(half, y, 0.0),
                new Vec3(half, y, height), new Vec3(-half, y, height), NEUTRAL_WALL);
    }

    private static Surface quad(Vec3 first, Vec3 second, Vec3 third, Vec3 fourth, int colour) {
        return new Surface(new Vec3[] {first, second, third, fourth},
                (colour >> 16) & 0xFF, (colour >> 8) & 0xFF, colour & 0xFF);
    }

    /** One channel triple scaled by {@link #UNLIT_GAIN}, clamped. */
    private static int atCameraExposure(int colour) {
        return (exposed((colour >> 16) & 0xFF) << 16)
                | (exposed((colour >> 8) & 0xFF) << 8)
                | exposed(colour & 0xFF);
    }

    private static int exposed(int channel) {
        return Math.min(255, (int) Math.round(channel * UNLIT_GAIN));
    }

    /**
     * One flat-coloured convex polygon in the field frame.
     *
     * <p>A polygon rather than a quad because clipping one against the camera's near plane can
     * hand back five corners; see {@link SurfaceRasteriser}. Corner order is free: a fill has to
     * work out the winding anyway, since projection reverses it for anything seen from behind.</p>
     */
    public static final class Surface {

        private final Vec3[] corners;
        private final int red;
        private final int green;
        private final int blue;

        public Surface(Vec3[] corners, int red, int green, int blue) {
            if (corners.length < 3) {
                throw new IllegalArgumentException(
                        "a surface needs at least three corners; got " + corners.length);
            }
            this.corners = corners.clone();
            this.red = red;
            this.green = green;
            this.blue = blue;
        }

        /** The corners in the field frame, in metres. */
        public Vec3[] corners() {
            return corners.clone();
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

        /**
         * How far away this surface counts as, for a painter's-algorithm depth sort: the depth of
         * its <em>farthest</em> corner.
         *
         * <p>Not its centre, and that is the whole point. A tag or a ball is compact, so its
         * centre stands for the thing; a floor tile is two feet across and a wall is twelve feet
         * long, and ranking one by its centre makes it nearer than the ball resting on it or the
         * tag hanging in front of it, which paints the field over its own contents. Ranking an
         * extended surface by its far edge makes it lose every tie against whatever stands on it,
         * which is the answer a depth buffer would give and the one a viewer expects.</p>
         */
        public double depthFrom(CameraView view) {
            double farthest = -Double.MAX_VALUE;
            for (Vec3 corner : corners) {
                farthest = Math.max(farthest, view.depthOf(corner));
            }
            return farthest;
        }
    }
}
