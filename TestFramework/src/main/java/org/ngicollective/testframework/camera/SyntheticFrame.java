package org.ngicollective.testframework.camera;

import java.util.Arrays;

/**
 * One rendered camera frame: RGBA bytes, four per pixel, row-major from the top-left.
 *
 * <p>RGBA and not something more convenient because that is what the SDK's processors demand.
 * {@code AprilTagProcessorImpl} converts with {@code COLOR_RGBA2GRAY} and
 * {@code ColorBlobLocatorProcessorImpl} with {@code COLOR_RGBA2RGB}; OpenCV rejects both on a
 * three-channel input. Handing over the exact layout {@code Mat.put} expects means the Android
 * side of the simulated camera is a copy and nothing more.</p>
 *
 * <p>Plain Java by design: no {@code Bitmap}, no {@code Mat}, nothing that needs a device. The
 * renderer and its tests therefore run on a plain JVM in milliseconds, which is the only reason
 * pixel-level assertions are practical at all.</p>
 */
public final class SyntheticFrame {

    private static final int BYTES_PER_PIXEL = 4;

    private final int width;
    private final int height;
    private final byte[] rgba;

    public SyntheticFrame(int width, int height) {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException(
                    "a frame must have a positive size; got " + width + "x" + height);
        }
        this.width = width;
        this.height = height;
        this.rgba = new byte[width * height * BYTES_PER_PIXEL];
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    /**
     * The frame's pixels, RGBA, for handing to {@code Mat.put(0, 0, ...)}.
     *
     * <p>The live array, not a copy: a frame is rendered and uploaded tens of times a second, and
     * copying a megabyte each time to defend against a caller that has no reason to scribble on
     * it would be a poor trade.</p>
     */
    public byte[] rgba() {
        return rgba;
    }

    /** Paints every pixel one opaque colour, which is how a render starts. */
    public void fill(int red, int green, int blue) {
        for (int i = 0; i < rgba.length; i += BYTES_PER_PIXEL) {
            rgba[i] = (byte) red;
            rgba[i + 1] = (byte) green;
            rgba[i + 2] = (byte) blue;
            rgba[i + 3] = (byte) 0xFF;
        }
    }

    /** Paints every pixel the same shade of grey, opaque. */
    public void fillGrey(int level) {
        fill(level, level, level);
    }

    /** Clears to transparent black, for a frame about to be fully painted. */
    public void clear() {
        Arrays.fill(rgba, (byte) 0);
    }

    /**
     * Blends an opaque colour over one pixel.
     *
     * @param coverage how much of the pixel the colour covers, 0 to 1; anti-aliasing is expressed
     *     as partial coverage rather than by rendering large and shrinking, which keeps the cost
     *     proportional to what is actually drawn
     */
    public void blend(int x, int y, int red, int green, int blue, double coverage) {
        if (x < 0 || y < 0 || x >= width || y >= height || coverage <= 0.0) {
            return;
        }
        int index = (y * width + x) * BYTES_PER_PIXEL;
        if (coverage >= 1.0) {
            rgba[index] = (byte) red;
            rgba[index + 1] = (byte) green;
            rgba[index + 2] = (byte) blue;
            rgba[index + 3] = (byte) 0xFF;
            return;
        }
        rgba[index] = mix(rgba[index], red, coverage);
        rgba[index + 1] = mix(rgba[index + 1], green, coverage);
        rgba[index + 2] = mix(rgba[index + 2], blue, coverage);
        rgba[index + 3] = (byte) 0xFF;
    }

    /** The red channel at a pixel, 0 to 255. */
    public int redAt(int x, int y) {
        return channel(x, y, 0);
    }

    public int greenAt(int x, int y) {
        return channel(x, y, 1);
    }

    public int blueAt(int x, int y) {
        return channel(x, y, 2);
    }

    /**
     * Perceived brightness at a pixel, 0 to 255.
     *
     * <p>The same Rec. 601 weighting OpenCV's {@code COLOR_RGBA2GRAY} uses, so a test asserting
     * "this pixel is black" is asking the question the detector will ask.</p>
     */
    public int luminanceAt(int x, int y) {
        return (int) Math.round(0.299 * redAt(x, y) + 0.587 * greenAt(x, y)
                + 0.114 * blueAt(x, y));
    }

    private int channel(int x, int y, int offset) {
        if (x < 0 || y < 0 || x >= width || y >= height) {
            throw new IllegalArgumentException("pixel (" + x + ", " + y + ") is outside a "
                    + width + "x" + height + " frame");
        }
        return rgba[(y * width + x) * BYTES_PER_PIXEL + offset] & 0xFF;
    }

    private static byte mix(byte existing, int incoming, double coverage) {
        int blended = (int) Math.round((existing & 0xFF) * (1.0 - coverage) + incoming * coverage);
        return (byte) Math.max(0, Math.min(255, blended));
    }
}
