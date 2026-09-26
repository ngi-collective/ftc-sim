package org.ngicollective.testframework.vision;

import org.opencv.android.OpenCVLoader;

import java.lang.reflect.Method;

/**
 * Brings OpenCV's native library up, on whichever execution target this is.
 *
 * <p>The FTC SDK ships one copy of OpenCV, for Android and for ARM, and loads it with
 * {@code OpenCVLoader.initDebug()}. That is the only thing that works on a Robot Controller and
 * the only thing that cannot work on a plain JVM, where the same call finds no
 * {@code libopencv_java4} and answers false. Vision therefore used to fail in the Dashboard
 * Server with {@code UnsatisfiedLinkError: 'long org.opencv.core.Mat.n_Mat()'} thrown from
 * wherever the first {@code Mat} happened to be allocated.</p>
 *
 * <p>Off-device the natives come from {@code org.openpnp:opencv}, which carries builds for macOS,
 * Linux and Windows and extracts the right one itself. It is reached by reflection on purpose:
 * it is a test-scope dependency, so naming it in code here would put a compile-time dependency on
 * something that must never reach a competition APK.</p>
 *
 * <p>Idempotent, and deliberately called before the first {@code Mat} rather than left to
 * whichever frame callback allocates one: a failure here names the problem, a failure there is a
 * link error inside somebody else's processor.</p>
 */
public final class VisionNatives {

    /** The off-device loader, from org.openpnp:opencv. */
    private static final String LOCAL_LOADER = "nu.pattern.OpenCV";

    private static boolean loaded;

    private VisionNatives() {
    }

    /**
     * Loads OpenCV's natives if they are not up already.
     *
     * @throws IllegalStateException if neither execution target's loader can supply them
     */
    public static synchronized void ensureLoaded() {
        if (loaded) {
            return;
        }
        if (OpenCVLoader.initDebug()) {
            loaded = true;
            return;
        }
        loadLocally();
        loaded = true;
    }

    /** The desktop path: org.openpnp's own extract-and-load. */
    private static void loadLocally() {
        try {
            Method loadLocally = Class.forName(LOCAL_LOADER).getMethod("loadLocally");
            loadLocally.invoke(null);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException(
                    "OpenCV's natives are not loadable here. The SDK's copy is Android and ARM"
                            + " only, and " + LOCAL_LOADER + ", which supplies desktop builds, is"
                            + " not on the classpath. See tools/build-vision-natives.sh.", e);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(LOCAL_LOADER + " failed to load OpenCV's natives", e);
        }
    }
}
