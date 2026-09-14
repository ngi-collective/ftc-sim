package org.ngicollective.testframework.camera;

/**
 * One flat-coloured convex polygon in the field frame: a floor tile, a perimeter wall, a panel of
 * the room the field stands in.
 *
 * <p>A polygon rather than a quad because clipping one against the camera's near plane can hand
 * back five corners; see {@link SurfaceRasteriser}. Corner order is free: a fill has to work out
 * the winding anyway, since projection reverses it for anything seen from behind.</p>
 */
public final class Surface {

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
     * How far away this surface counts as, for a painter's-algorithm depth sort: the depth of its
     * <em>farthest</em> corner.
     *
     * <p>Not its centre, and that is the whole point. A tag or a ball is compact, so its centre
     * stands for the thing; a floor tile is two feet across and a wall is twelve feet long, and
     * ranking one by its centre makes it nearer than the ball resting on it or the tag hanging in
     * front of it, which paints the field over its own contents. Ranking an extended surface by
     * its far edge makes it lose every tie against whatever stands on it, which is the answer a
     * depth buffer would give and the one a viewer expects.</p>
     */
    public double depthFrom(CameraView view) {
        double farthest = -Double.MAX_VALUE;
        for (Vec3 corner : corners) {
            farthest = Math.max(farthest, view.depthOf(corner));
        }
        return farthest;
    }
}
