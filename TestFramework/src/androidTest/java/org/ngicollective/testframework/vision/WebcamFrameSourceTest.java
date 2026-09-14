package org.ngicollective.testframework.vision;

import android.content.Context;
import android.util.Log;
import android.util.Size;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import androidx.test.rule.GrantPermissionRule;
import org.ngicollective.testframework.camera.SyntheticFrame;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * That a physically attached camera still reaches a frame, now that it lives behind
 * {@link org.ngicollective.testframework.camera.FrameSource}.
 *
 * <p>Structural only, and deliberately so. Until the host operating system has granted camera
 * access to whatever launched the emulator, the guest streams uniformly <em>black</em> frames
 * while everything else behaves correctly: the camera opens, frames arrive, timestamps advance.
 * Asserting on image content would therefore fail for a reason that has nothing to do with this
 * code. What this test defends is the Camera2 plumbing, which is the part that was ported.</p>
 *
 * <p>Needs an AVD built from a {@code google_apis} image with {@code hw.camera.back=webcam0}: the
 * AOSP {@code default} images ship no camera HAL at all. Outside CI for that reason.</p>
 */
@RunWith(AndroidJUnit4.class)
public class WebcamFrameSourceTest {

    private static final String TAG = "WebcamFrameSourceTest";

    /**
     * Grants the camera permission the test APK declares.
     *
     * <p>Declaring it is not enough on API 23 and up: an ungranted dangerous permission surfaces
     * from Camera2 as a bare "could not open camera 0", with the real reason
     * ("cannot open camera without camera permission") only in logcat.</p>
     */
    @Rule
    public GrantPermissionRule cameraPermission =
            GrantPermissionRule.grant(android.Manifest.permission.CAMERA);

    @Test
    public void aRealCameraDeliversFramesIntoASyntheticFrame() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();

        try (WebcamFrameSource source = new WebcamFrameSource(context, new Size(640, 480))) {
            Size resolution = source.resolution();
            Log.i(TAG, "camera streaming " + resolution);
            assertTrue("a camera should offer a sane frame size, got " + resolution,
                    resolution.getWidth() >= 160 && resolution.getHeight() >= 120);

            SyntheticFrame frame =
                    new SyntheticFrame(resolution.getWidth(), resolution.getHeight());

            long deadline = System.currentTimeMillis() + 5000;
            while (source.frameCount() < 5 && System.currentTimeMillis() < deadline) {
                Thread.sleep(50);
            }
            Log.i(TAG, "delivered " + source.frameCount() + " frames");
            assertTrue("the camera delivered no frames in five seconds",
                    source.frameCount() >= 5);

            source.render(frame);
            assertEquals(resolution.getWidth() * resolution.getHeight() * 4,
                    frame.rgba().length);

            // Opaque everywhere: the converter writes an alpha of 255, and a frame that is
            // transparent is one that never got copied.
            assertEquals(255, frame.rgba()[3] & 0xFF);
        }
    }

    @Test
    public void askingForTheWrongSizeIsRefusedRatherThanScaled() throws Exception {
        // Scaling here would silently invalidate the calibration the detector is using, so the
        // mismatch has to be loud.
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();

        try (WebcamFrameSource source = new WebcamFrameSource(context, new Size(640, 480))) {
            Size resolution = source.resolution();
            SyntheticFrame wrongSize =
                    new SyntheticFrame(resolution.getWidth() / 2, resolution.getHeight() / 2);
            try {
                source.render(wrongSize);
                throw new AssertionError("expected a refusal for a mismatched frame size");
            } catch (IllegalArgumentException expected) {
                assertTrue(expected.getMessage().contains("streams"));
            }
        }
    }
}
