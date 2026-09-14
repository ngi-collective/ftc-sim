package org.ngicollective.testframework.camera;

/**
 * Fills a flat-coloured convex polygon in the field frame: a floor tile, or a perimeter wall.
 *
 * <h2>Why this cannot project four corners and be done</h2>
 *
 * <p>A tag is a small plate that is either in front of the camera or not, which is why
 * {@link TagRasteriser} can map its four corners to pixels and invert that map. A floor runs out
 * from under the camera and carries on <em>behind</em> it, so some of its corners have no
 * projection at all: the depth they divide by is zero or negative, and projecting them anyway
 * throws the corner out to infinity or, worse, wraps it round to the opposite side of the image,
 * where it draws a plausible-looking polygon covering entirely the wrong pixels.</p>
 *
 * <p>So the polygon is clipped against the camera's near plane in camera space <em>first</em>, and
 * only the surviving part is projected. Clipping a convex polygon by a single plane leaves it
 * convex and may add one corner &mdash; a quad becomes a pentagon &mdash; which is why nothing
 * here assumes four of anything. A polygon entirely behind the plane loses every corner and draws
 * nothing, which is the honest answer for a wall behind the camera.</p>
 *
 * <p>Flat fills, supersampled to the same 4x4 grid as {@link TagRasteriser} and
 * {@link ElementRasteriser}, so a surface's edges soften exactly like every other edge in the
 * frame and a tag drawn over one has the same seam it would have over the background.</p>
 */
public final class SurfaceRasteriser {

    /** Sub-samples per pixel axis, matching {@link TagRasteriser}. */
    private static final int SAMPLES_PER_AXIS = 4;

    /**
     * Below this area in square pixels a surface is not worth drawing.
     *
     * <p>A wall seen exactly edge-on, or the floor from a camera lying on it, projects to a line.
     * The same reasoning as {@link TagRasteriser}: at a fraction of a pixel there is nothing there
     * to see, and this saves walking a bounding box that can span the whole frame to fill none of
     * it.</p>
     */
    private static final double MIN_AREA_PIXELS = 1.0;

    /** What {@link #cornersInside} found: no part of the pixel, all of it, or an edge across it. */
    private static final int NONE = 0;
    private static final int SOME = 1;
    private static final int ALL = 2;

    private SurfaceRasteriser() {
    }

    /**
     * Draws one surface, or nothing if none of it is both in front of the camera and on screen.
     *
     * @return whether any pixel was touched
     */
    public static boolean draw(SyntheticFrame frame, CameraView view,
                               Surface surface) {
        Vec3[] corners = surface.corners();
        Vec3[] inCamera = new Vec3[corners.length];
        for (int i = 0; i < corners.length; i++) {
            inCamera[i] = view.inCameraFrame(corners[i]);
        }

        Vec3[] visible = clippedToNearPlane(inCamera, view.nearPlaneMetres());
        if (visible.length < 3) {
            return false;
        }

        Pixel[] outline = new Pixel[visible.length];
        for (int i = 0; i < visible.length; i++) {
            // Clipping left every corner at or beyond the near plane, so none of these is null.
            outline[i] = view.projectFromCameraFrame(visible[i]);
        }
        return fill(frame, outline, surface.red(), surface.green(), surface.blue());
    }

    /**
     * The part of a camera-space polygon at or beyond the near plane.
     *
     * <p>Corners crossing the plane are replaced by the point where the edge meets it, with its
     * depth set to the plane exactly rather than to whatever the interpolation rounded to: a
     * corner a hair inside the plane has no projection, and this is the one place that could put
     * one there.</p>
     */
    private static Vec3[] clippedToNearPlane(Vec3[] polygon, double near) {
        Vec3[] kept = new Vec3[polygon.length + 1];
        int count = 0;
        for (int i = 0; i < polygon.length; i++) {
            Vec3 current = polygon[i];
            Vec3 next = polygon[(i + 1) % polygon.length];
            boolean currentIn = current.z() >= near;
            boolean nextIn = next.z() >= near;
            if (currentIn) {
                kept[count++] = current;
            }
            if (currentIn != nextIn) {
                double along = (near - current.z()) / (next.z() - current.z());
                kept[count++] = new Vec3(
                        current.x() + along * (next.x() - current.x()),
                        current.y() + along * (next.y() - current.y()),
                        near);
            }
        }
        Vec3[] clipped = new Vec3[count];
        System.arraycopy(kept, 0, clipped, 0, count);
        return clipped;
    }

    /** Paints the projected outline, with partial coverage along its edges. */
    private static boolean fill(SyntheticFrame frame, Pixel[] outline, int red, int green,
                                int blue) {
        double area = Pixel.signedAreaOf(outline);
        if (Math.abs(area) < MIN_AREA_PIXELS) {
            return false;
        }
        // Which side of an edge is inside depends on the winding, and projection reverses the
        // winding of anything the camera sees from behind, so it is read off the shape itself.
        double winding = Math.signum(area);

        int[] bounds = boundingBox(frame, outline);
        if (bounds == null) {
            return false;
        }

        int samples = SAMPLES_PER_AXIS * SAMPLES_PER_AXIS;
        double step = 1.0 / SAMPLES_PER_AXIS;
        boolean drew = false;

        // Edges as (origin, direction) with the inward side already signed, so a coverage test is
        // a multiply-add per edge and the winding is not consulted again.
        int edges = outline.length;
        double[] fromX = new double[edges];
        double[] fromY = new double[edges];
        double[] alongX = new double[edges];
        double[] alongY = new double[edges];
        for (int i = 0; i < edges; i++) {
            Pixel from = outline[i];
            Pixel to = outline[(i + 1) % edges];
            fromX[i] = from.x();
            fromY[i] = from.y();
            alongX[i] = winding * (to.x() - from.x());
            alongY[i] = winding * (to.y() - from.y());
        }

        for (int y = bounds[1]; y <= bounds[3]; y++) {
            for (int x = bounds[0]; x <= bounds[2]; x++) {
                int corners = cornersInside(fromX, fromY, alongX, alongY, x, y);
                if (corners == NONE) {
                    continue;
                }
                int covered;
                if (corners == ALL) {
                    covered = samples;
                } else {
                    covered = 0;
                    for (int subY = 0; subY < SAMPLES_PER_AXIS; subY++) {
                        for (int subX = 0; subX < SAMPLES_PER_AXIS; subX++) {
                            if (inside(fromX, fromY, alongX, alongY,
                                    x + (subX + 0.5) * step, y + (subY + 0.5) * step)) {
                                covered++;
                            }
                        }
                    }
                    if (covered == 0) {
                        continue;
                    }
                }
                frame.blend(x, y, red, green, blue, (double) covered / samples);
                drew = true;
            }
        }
        return drew;
    }

    /**
     * How much of a pixel's square can be inside the polygon, from its four corners.
     *
     * <p>Two shortcuts, both exact for a convex shape, and between them they decide most of a
     * frame without supersampling anything. All four corners inside means the whole square is
     * inside, because a convex shape contains everything between points it contains. All four
     * outside <em>the same edge</em> means the square cannot touch the shape at all, which is the
     * case that matters for cost: a surface clipped to the near plane has a bounding box the size
     * of the frame and covers a fraction of it, and sampling all of that sixteen times over is
     * most of the work a naive fill does.</p>
     *
     * @return {@link #ALL}, {@link #NONE}, or {@link #SOME} when the square straddles an edge and
     *     only supersampling can say how much of it is covered
     */
    private static int cornersInside(double[] fromX, double[] fromY, double[] alongX,
                                     double[] alongY, int x, int y) {
        int result = ALL;
        for (int i = 0; i < fromX.length; i++) {
            int in = 0;
            for (int corner = 0; corner < 4; corner++) {
                double cornerX = x + (corner & 1);
                double cornerY = y + ((corner >> 1) & 1);
                if (alongX[i] * (cornerY - fromY[i]) - alongY[i] * (cornerX - fromX[i]) >= 0.0) {
                    in++;
                }
            }
            if (in == 0) {
                return NONE;
            }
            if (in < 4) {
                result = SOME;
            }
        }
        return result;
    }

    private static boolean inside(double[] fromX, double[] fromY, double[] alongX,
                                  double[] alongY, double x, double y) {
        for (int i = 0; i < fromX.length; i++) {
            if (alongX[i] * (y - fromY[i]) - alongY[i] * (x - fromX[i]) < 0.0) {
                return false;
            }
        }
        return true;
    }

    /**
     * The pixels the outline could touch, clipped to the frame, or {@code null} if none of it
     * lands on screen.
     *
     * <p>A surface clipped to the near plane can project corners millions of pixels off screen,
     * which is fine: the cast saturates rather than wrapping, and the frame bounds do the rest.</p>
     */
    private static int[] boundingBox(SyntheticFrame frame, Pixel[] outline) {
        double minX = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE;
        double minY = Double.MAX_VALUE;
        double maxY = -Double.MAX_VALUE;
        for (Pixel corner : outline) {
            minX = Math.min(minX, corner.x());
            maxX = Math.max(maxX, corner.x());
            minY = Math.min(minY, corner.y());
            maxY = Math.max(maxY, corner.y());
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
