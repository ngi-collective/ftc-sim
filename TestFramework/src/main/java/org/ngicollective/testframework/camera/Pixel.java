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

    @Override
    public String toString() {
        return String.format("(%.2f, %.2f)px", x, y);
    }
}
