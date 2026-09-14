package org.ngicollective.camerastream;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;

/**
 * Turns rendered RGBA pixels into JPEG bytes.
 *
 * <p>Lossy, and that is a deliberate trade recorded here because it is easy to forget downstream:
 * the pixels a browser shows are <em>not</em> bit-identical to the ones the detector was handed.
 * A 640&times;480 RGBA frame is 1.2&nbsp;MB, so 15&nbsp;fps of raw pixels is 18&nbsp;MB/s and
 * base64 over the JSON socket is worse; the same frame as JPEG is about 40&nbsp;kB. The view exists
 * to show geometry and framing &mdash; where the tags are, whether they are in shot, how big they
 * are &mdash; and JPEG preserves all of that. Exact pixels are a job for saving a single frame, not
 * for a stream.</p>
 *
 * <p>Not thread-safe: an {@link ImageWriter} is stateful, and the buffers are reused on purpose so
 * that a 15&nbsp;fps stream does not hand the collector two megabytes of garbage a frame.</p>
 */
public final class JpegEncoder {

    /**
     * Biased high for a synthetic scene. A tag is hard black cells against hard white, which is
     * the pattern JPEG rings around worst, and ringing on tag cells is exactly the artefact that
     * would make somebody distrust a view built to be trusted. Verified by eye against a rendered
     * cluster; lower it only with a frame in front of you.
     */
    private static final float QUALITY = 0.85f;

    private final ImageWriter writer;
    private final ImageWriteParam params;

    private BufferedImage image;
    private int[] pixels;

    public JpegEncoder() {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
        if (!writers.hasNext()) {
            throw new IllegalStateException(
                    "this JVM has no JPEG writer, so the camera view cannot be served; a headless "
                            + "JDK without ImageIO's JPEG plugin is the usual cause");
        }
        writer = writers.next();
        params = writer.getDefaultWriteParam();
        params.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        params.setCompressionQuality(QUALITY);
    }

    /**
     * Encodes one frame.
     *
     * @param rgba   four bytes per pixel, row-major from the top-left, as the renderer produces
     * @param width  frame width in pixels
     * @param height frame height in pixels
     */
    public byte[] encode(byte[] rgba, int width, int height) {
        int expected = width * height * 4;
        if (rgba.length != expected) {
            throw new IllegalArgumentException("a " + width + "x" + height + " RGBA frame is "
                    + expected + " bytes, not " + rgba.length);
        }
        // Alpha is dropped rather than composited: the renderer paints an opaque scene, and a JPEG
        // has nowhere to put transparency anyway.
        int[] target = buffer(width, height);
        for (int i = 0, p = 0; p < target.length; i += 4, p++) {
            target[p] = (rgba[i] & 0xFF) << 16 | (rgba[i + 1] & 0xFF) << 8 | (rgba[i + 2] & 0xFF);
        }

        ByteArrayOutputStream bytes = new ByteArrayOutputStream(64 * 1024);
        try (MemoryCacheImageOutputStream stream = new MemoryCacheImageOutputStream(bytes)) {
            writer.setOutput(stream);
            writer.write(null, new IIOImage(image, null, null), params);
        } catch (IOException e) {
            // A write to memory cannot fail for any reason a caller could act on.
            throw new IllegalStateException("JPEG encoding failed", e);
        }
        return bytes.toByteArray();
    }

    /** The reusable image for this size, replaced only when the frame size changes. */
    private int[] buffer(int width, int height) {
        if (image == null || image.getWidth() != width || image.getHeight() != height) {
            image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
            pixels = ((DataBufferInt) image.getRaster().getDataBuffer()).getData();
        }
        return pixels;
    }
}
