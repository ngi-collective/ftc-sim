package org.ngicollective.testframework.camera;

/**
 * A point in the image, in pixels from the top-left corner, x rightward and y downward.
 *
 * <p>Sub-pixel by design. The AprilTag detector reports corners to a fraction of a pixel and the
 * SDK's pose solver depends on that precision, so rounding a projected corner to an integer here
 * would inject an error the real pipeline does not have.</p>
 *
 * <p>A pixel may legitimately fall outside the frame: a tag can straddle the edge, and the
 * rasteriser needs the true off-frame corner to clip against rather than a clamped one.</p>
 */
public final class Pixel {

    private final double x;
    private final double y;

    public Pixel(double x, double y) {
        this.x = x;
        this.y = y;
    }

    public double x() {
        return x;
    }

    public double y() {
        return y;
    }

    /**
     * Shoelace area of a polygon of pixels, in square pixels, signed by its winding.
     *
     * <p>Both rasterisers that fill a projected polygon need this: the magnitude says whether
     * there is enough of the shape left to be worth drawing, and the sign says which way round
     * its corners run, which is what an inside-the-polygon test has to know before it can call a
     * side "inward". Projection flips the winding of anything the camera sees from behind, so the
     * sign cannot be assumed from how the corners were written down.</p>
     */
    public static double signedAreaOf(Pixel[] polygon) {
        double sum = 0.0;
        for (int i = 0; i < polygon.length; i++) {
            Pixel current = polygon[i];
            Pixel next = polygon[(i + 1) % polygon.length];
            sum += current.x() * next.y() - next.x() * current.y();
        }
        return sum / 2.0;
    }

    @Override
    public String toString() {
        return String.format("(%.2f, %.2f)px", x, y);
    }
}
