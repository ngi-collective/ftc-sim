package org.ngicollective.testframework.vision;

import android.media.Image;

import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.imgproc.Imgproc;

import java.nio.ByteBuffer;

/**
 * Turns a Camera2 {@code YUV_420_888} image into the RGBA {@link Mat} an FTC
 * {@code VisionProcessor} expects.
 *
 * <p>RGBA specifically, not RGB: the SDK's processors are written against what EasyOpenCV hands
 * them, and both {@code AprilTagProcessorImpl} ({@code COLOR_RGBA2GRAY}) and
 * {@code ColorBlobLocatorProcessorImpl} ({@code COLOR_RGBA2RGB}) call conversions that OpenCV
 * rejects on a 3-channel input.</p>
 *
 * <p>{@code YUV_420_888} only promises 4:2:0 planes; it says nothing about strides or whether
 * chroma is planar or interleaved. The generic path below handles any of it, with a bulk copy for
 * the common semi-planar case, which is what the emulator's camera HAL actually produces.</p>
 *
 * <p>Not thread safe, and buffers are allocated once and reused: this runs on every frame.</p>
 */
final class Yuv420Rgba {

    private final int width;
    private final int height;
    private final byte[] nv21;
    private final Mat yuv;
    private final Mat rgba;

    Yuv420Rgba(int width, int height) {
        this.width = width;
        this.height = height;
        this.nv21 = new byte[width * height * 3 / 2];
        // NV21 stacks half-height interleaved chroma underneath full-height luma, as one channel.
        this.yuv = new Mat(height + height / 2, width, CvType.CV_8UC1);
        this.rgba = new Mat(height, width, CvType.CV_8UC4);
    }

    /** Returns a Mat owned by this converter, overwritten on the next call. */
    Mat convert(Image image) {
        Image.Plane[] planes = image.getPlanes();
        copyLuma(planes[0]);
        copyChroma(planes[1], planes[2]);
        yuv.put(0, 0, nv21);
        Imgproc.cvtColor(yuv, rgba, Imgproc.COLOR_YUV2RGBA_NV21);
        return rgba;
    }

    void release() {
        yuv.release();
        rgba.release();
    }

    private void copyLuma(Image.Plane plane) {
        ByteBuffer buffer = plane.getBuffer();
        int rowStride = plane.getRowStride();
        if (rowStride == width) {
            buffer.position(0);
            buffer.get(nv21, 0, width * height);
            return;
        }
        for (int row = 0, offset = 0; row < height; row++, offset += width) {
            buffer.position(row * rowStride);
            buffer.get(nv21, offset, width);
        }
    }

    /** NV21 wants chroma interleaved as V,U pairs at half resolution. */
    private void copyChroma(Image.Plane u, Image.Plane v) {
        ByteBuffer uBuffer = u.getBuffer();
        ByteBuffer vBuffer = v.getBuffer();
        int chromaWidth = width / 2;
        int chromaHeight = height / 2;
        int start = width * height;
        int interleaved = chromaWidth * chromaHeight * 2 - 1;

        // Semi-planar and tightly packed: the V plane's buffer already *is* the interleaved VU
        // block, one byte short of it, since the final U byte falls outside the V plane.
        if (v.getPixelStride() == 2 && v.getRowStride() == width && vBuffer.capacity() >= interleaved) {
            vBuffer.position(0);
            vBuffer.get(nv21, start, interleaved);
            nv21[start + interleaved] = uBuffer.get(uBuffer.capacity() - 1);
            return;
        }

        int vRowStride = v.getRowStride();
        int vPixelStride = v.getPixelStride();
        int uRowStride = u.getRowStride();
        int uPixelStride = u.getPixelStride();
        for (int row = 0; row < chromaHeight; row++) {
            int out = start + row * width;
            int vRow = row * vRowStride;
            int uRow = row * uRowStride;
            for (int col = 0; col < chromaWidth; col++) {
                nv21[out++] = vBuffer.get(vRow + col * vPixelStride);
                nv21[out++] = uBuffer.get(uRow + col * uPixelStride);
            }
        }
    }
}
