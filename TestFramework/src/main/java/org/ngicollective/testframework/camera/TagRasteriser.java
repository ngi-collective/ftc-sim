package org.ngicollective.testframework.camera;

/**
 * Draws an AprilTag into a frame, through the quadrilateral the camera sees it as.
 *
 * <p>Inverse mapping: for every pixel the tag could touch, ask which cell of the tag that pixel
 * came from. Walking the tag's cells forwards instead would leave gaps wherever the projection
 * stretches &mdash; a tag seen edge-on covers a tall thin quad, and a forward walk would draw a
 * dotted line through it.</p>
 *
 * <h2>The quiet zone is not decoration</h2>
 *
 * <p>A tag36h11 is a black square with a 6x6 code inside it, and the detector finds it by looking
 * for that black square against something lighter. Drawn without the white margin real printed
 * tags carry, a tag against a dark background has no outer edge to find and simply does not
 * detect. So one cell of white is rendered around the black square, which costs nothing: the
 * homography is already defined over the tag's whole plane, so the margin is a wider sampling
 * window rather than extra geometry.</p>
 *
 * <p>Anti-aliased by supersampling, because the detector refines its corners to a fraction of a
 * pixel and hard-edged cells would quantise the pose it reports. Partial coverage at the tag's
 * boundary also lets a tag sit over whatever was drawn behind it without a jagged seam.</p>
 */
public final class TagRasteriser {

    /**
     * Sub-samples per pixel axis.
     *
     * <p>Chosen by measurement, not taste: rendered frames were fed to apriltag3 itself and the
     * range it solved was compared against the geometry. A 3.25 in tag through a 60&deg; lens at
     * 640x480 came out like this, as a percentage error in solved range:</p>
     *
     * <pre>
     *   samples   0.5 m    1.0 m    1.5 m    2.0 m    3.0 m
     *     2x2     -0.52    -0.55    -1.28    -0.83    +3.78
     *     4x4     -0.11    -0.55    -0.31    -0.83    +0.27
     * </pre>
     *
     * <p>FIRST's own field tolerance is an inch, which at 3 m is 0.85%, so 2x2 is outside it at
     * the far end and 4x4 is inside it everywhere. More is <em>not</em> better: at 8x8 a 15 px
     * tag stopped being detected at all, because softening the edges that far also softens the
     * code cells, and at under two pixels per cell there is no contrast left to decode. Sharpness
     * and corner accuracy pull in opposite directions and this is the measured middle.</p>
     */
    private static final int SAMPLES_PER_AXIS = 4;

    /** Cells of white margin around the black square, as printed tags have. */
    private static final double QUIET_CELLS = 1.0;

    private static final int BLACK = 0;
    private static final int WHITE = 255;

    /**
     * Below this area in square pixels a tag is not worth drawing.
     *
     * <p>An almost edge-on tag projects to a quad with no interior, which has no unique
     * homography. Skipping it is also the physically honest answer: at a fraction of a pixel there
     * is nothing there to see.</p>
     */
    private static final double MIN_AREA_PIXELS = 1.0;

    private TagRasteriser() {
    }

    /**
     * Draws one tag, or nothing at all if it is hidden or too small to matter.
     *
     * @return whether any pixel was touched, which is what a scene renderer reports as "this tag
     *     is in this frame"
     */
    public static boolean draw(SyntheticFrame frame, TagQuad quad) {
        if (!quad.isVisible()) {
            return false;
        }
        Pixel[] corners = quad.corners();
        if (Math.abs(Pixel.signedAreaOf(corners)) < MIN_AREA_PIXELS) {
            return false;
        }

        int cells = Tag36h11.CELLS_ACROSS;
        // Cell space runs with the image: column 0 and row 0 are the tag's top-left as seen,
        // which is corners[1]. See FieldTag for why that is the corner at positive tag x.
        double[][] fromCells = {{0.0, 0.0}, {cells, 0.0}, {cells, cells}, {0.0, cells}};
        double[][] toPixels = {
                {corners[1].x(), corners[1].y()},
                {corners[0].x(), corners[0].y()},
                {corners[3].x(), corners[3].y()},
                {corners[2].x(), corners[2].y()},
        };
        Homography homography = Homography.mapping(fromCells, toPixels);

        double low = -QUIET_CELLS;
        double high = cells + QUIET_CELLS;
        int[] bounds = boundingBox(frame, homography, low, high);
        if (bounds == null) {
            return false;
        }

        double[] cell = new double[2];
        int samples = SAMPLES_PER_AXIS * SAMPLES_PER_AXIS;
        double step = 1.0 / SAMPLES_PER_AXIS;
        boolean drew = false;

        for (int y = bounds[1]; y <= bounds[3]; y++) {
            for (int x = bounds[0]; x <= bounds[2]; x++) {
                int covered = 0;
                int total = 0;
                for (int subY = 0; subY < SAMPLES_PER_AXIS; subY++) {
                    for (int subX = 0; subX < SAMPLES_PER_AXIS; subX++) {
                        homography.applyInverse(
                                x + (subX + 0.5) * step, y + (subY + 0.5) * step, cell);
                        if (cell[0] < low || cell[0] >= high || cell[1] < low || cell[1] >= high) {
                            continue;
                        }
                        covered++;
                        total += shadeOf(quad.id(), cell[0], cell[1], cells);
                    }
                }
                if (covered == 0) {
                    continue;
                }
                int shade = total / covered;
                frame.blend(x, y, shade, shade, shade, (double) covered / samples);
                drew = true;
            }
        }
        return drew;
    }

    /** Black inside a dark cell, white in a light cell or anywhere in the quiet zone. */
    private static int shadeOf(int id, double column, double row, int cells) {
        if (column < 0.0 || column >= cells || row < 0.0 || row >= cells) {
            return WHITE;
        }
        return Tag36h11.isWhite(id, (int) row, (int) column) ? WHITE : BLACK;
    }

    /**
     * The pixels the tag and its quiet zone could touch, clipped to the frame, or {@code null} if
     * none of it lands on screen.
     */
    private static int[] boundingBox(SyntheticFrame frame, Homography homography,
                                     double low, double high) {
        double[] corner = new double[2];
        double minX = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE;
        double minY = Double.MAX_VALUE;
        double maxY = -Double.MAX_VALUE;
        double[][] outer = {{low, low}, {high, low}, {high, high}, {low, high}};
        for (double[] point : outer) {
            homography.apply(point[0], point[1], corner);
            minX = Math.min(minX, corner[0]);
            maxX = Math.max(maxX, corner[0]);
            minY = Math.min(minY, corner[1]);
            maxY = Math.max(maxY, corner[1]);
        }

        int left = Math.max(0, (int) Math.floor(minX));
        int top = Math.max(0, (int) Math.floor(minY));
        int right = Math.min(frame.width() - 1, (int) Math.ceil(maxX));
        int bottom = Math.min(frame.height() - 1, (int) Math.ceil(maxY));
        if (left > right || top > bottom) {
            return null;
        }
        return new int[] {left, top, right, bottom};
    }
}
