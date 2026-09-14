package org.ngicollective.testframework.vision;

import static org.junit.Assert.assertTrue;

import android.os.Build;
import android.util.Log;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.opencv.android.OpenCVLoader;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.imgproc.Imgproc;
import org.openftc.apriltag.AprilTagDetectorJNI;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Diagnostic probe, not a regression test.
 *
 * <p>It answers exactly one question: <em>does the arm64-only native FTC vision stack load and
 * run inside an x86_64 Android emulator through Google's NDK translation layer?</em> The vision
 * dependencies of {@code org.firstinspires.ftc:Vision} resolve to
 * {@code org.openftc:opencv-repackaged-bundled-dylibs} and {@code org.openftc:apriltag}, and
 * both of those AARs ship {@code arm64-v8a} and {@code armeabi-v7a} only - there is no x86_64
 * build of {@code libapriltag.so} on Maven at all. Whether an x86_64 host (Intel Mac, x86 Linux,
 * a GitHub-hosted CI runner) can still develop against this stack therefore comes down to
 * whether an {@code x86_64} system image with {@code libndk_translation.so} can execute those
 * ARM libraries. That is an empirical question about a specific emulator image, so this class
 * asks the emulator instead of reasoning about it.</p>
 *
 * <p>It <strong>reports</strong> rather than verifies: every step is wrapped so that a failure
 * is logged and the probe keeps going, and the only assertion is that the probe reached its own
 * end. A probe that aborted on the first {@code UnsatisfiedLinkError} would answer half the
 * question. Read the answer out of logcat, not out of the pass/fail result - every line is
 * tagged {@code ABI_PROBE}.</p>
 *
 * <p><strong>Expected to be deleted</strong> once the x86_64 decision is recorded. It has no
 * value as a standing test: it asserts nothing about this project's behaviour.</p>
 */
@RunWith(AndroidJUnit4.class)
public class NativeAbiProbe {

    /** Tag and grep handle. {@code adb logcat -d -s ABI_PROBE:*} shows the whole answer. */
    private static final String TAG = "ABI_PROBE";

    @Test
    public void reportNativeAbiSupport() {
        log("---- begin native ABI probe ----");

        reportAbis();
        boolean openCvLoaded = reportOpenCvLoad();
        if (openCvLoaded) {
            reportOpenCvExecution();
        } else {
            log("opencv.execute=skipped (library never loaded)");
        }
        reportAprilTagLoad();
        reportLoadedLibraryPaths();

        log("---- end native ABI probe ----");
        // Deliberately not an assertion about the natives: the workflow that runs this needs a
        // complete log far more than it needs a red X.
        assertTrue("probe did not reach its own end", true);
    }

    /** (a) What the image claims it can run. */
    private void reportAbis() {
        log("device=" + Build.MANUFACTURER + "/" + Build.MODEL
                + " api=" + Build.VERSION.SDK_INT
                + " fingerprint=" + Build.FINGERPRINT);
        log("Build.SUPPORTED_ABIS=" + Arrays.toString(Build.SUPPORTED_ABIS));
        log("Build.SUPPORTED_32_BIT_ABIS=" + Arrays.toString(Build.SUPPORTED_32_BIT_ABIS));
        log("Build.SUPPORTED_64_BIT_ABIS=" + Arrays.toString(Build.SUPPORTED_64_BIT_ABIS));
        log("ro.product.cpu.abilist=" + systemProperty("ro.product.cpu.abilist"));
        log("ro.product.cpu.abi=" + systemProperty("ro.product.cpu.abi"));
        // Set on images that carry Google's ARM-to-x86 translator; its presence is the whole
        // reason an x86_64 image could run these libraries at all.
        log("ro.dalvik.vm.native.bridge=" + systemProperty("ro.dalvik.vm.native.bridge"));
    }

    /**
     * (b) Whether {@code libopencv_java4.so} resolves. {@code initDebug} returns false on a
     * clean failure and throws {@link UnsatisfiedLinkError} when the ABI is simply absent, so
     * both outcomes have to be captured.
     */
    private boolean reportOpenCvLoad() {
        try {
            boolean loaded = OpenCVLoader.initDebug();
            log("opencv.initDebug=" + loaded);
            return loaded;
        } catch (Throwable failure) {
            log("opencv.initDebug threw " + describe(failure));
            return false;
        }
    }

    /**
     * (c) Whether translated OpenCV code actually executes. Resolving a shared object and
     * running its instructions under a native bridge are separate failure modes, and only the
     * second one matters to a developer trying to work on vision.
     */
    private void reportOpenCvExecution() {
        Mat source = null;
        Mat destination = null;
        try {
            source = new Mat(48, 64, CvType.CV_8UC4);
            destination = new Mat();
            Imgproc.cvtColor(source, destination, Imgproc.COLOR_RGBA2GRAY);
            log("opencv.execute=ok cvtColor produced "
                    + destination.cols() + "x" + destination.rows()
                    + "/" + CvType.typeToString(destination.type()));
        } catch (Throwable failure) {
            log("opencv.execute threw " + describe(failure));
        } finally {
            if (source != null) {
                source.release();
            }
            if (destination != null) {
                destination.release();
            }
        }
    }

    /**
     * (d) Whether {@code libapriltag.so} resolves and runs. This is the harder half: unlike
     * OpenCV there is no x86_64 build of this library published anywhere, so a working result
     * here can only mean translation.
     */
    private void reportAprilTagLoad() {
        long detector = 0;
        try {
            detector = AprilTagDetectorJNI.createApriltagDetector("tag36h11", 3f, 3);
            log("apriltag.createApriltagDetector=0x" + Long.toHexString(detector));
        } catch (Throwable failure) {
            log("apriltag.createApriltagDetector threw " + describe(failure));
        } finally {
            if (detector != 0) {
                try {
                    AprilTagDetectorJNI.releaseApriltagDetector(detector);
                    log("apriltag.releaseApriltagDetector=ok");
                } catch (Throwable failure) {
                    log("apriltag.releaseApriltagDetector threw " + describe(failure));
                }
            }
        }
    }

    /**
     * (e) Which ABI the process really chose. Android picks one primary ABI per process, and
     * {@code /proc/self/maps} is the only place that says which directory the loader pulled the
     * vision libraries out of - {@code .../lib/arm64} versus {@code .../lib/x86_64}, plus any
     * {@code libndk_translation.so} mapping that proves a native bridge is in play.
     */
    private void reportLoadedLibraryPaths() {
        List<String> interesting = new ArrayList<>();
        Set<String> directories = new LinkedHashSet<>();
        try (BufferedReader maps = new BufferedReader(new FileReader("/proc/self/maps"))) {
            String line;
            while ((line = maps.readLine()) != null) {
                int pathStart = line.indexOf('/');
                if (pathStart < 0) {
                    continue;
                }
                String path = line.substring(pathStart);
                String lowered = path.toLowerCase();
                if (!lowered.contains(".so")) {
                    continue;
                }
                if (!lowered.contains("opencv") && !lowered.contains("apriltag")
                        && !lowered.contains("easyopencv") && !lowered.contains("translation")
                        && !lowered.contains("houdini") && !lowered.contains("native_bridge")) {
                    continue;
                }
                if (interesting.contains(path)) {
                    continue;
                }
                interesting.add(path);
                int lastSlash = path.lastIndexOf('/');
                directories.add(lastSlash > 0 ? path.substring(0, lastSlash) : path);
            }
        } catch (IOException failure) {
            log("maps read threw " + describe(failure));
            return;
        }

        if (interesting.isEmpty()) {
            log("maps.vision_libraries=none mapped");
        }
        for (String path : interesting) {
            log("maps.library=" + path);
        }
        for (String directory : directories) {
            log("maps.directory=" + directory);
        }
        log("nativeLibraryDir=" + nativeLibraryDir());
    }

    /**
     * The APK's own native library directory, which encodes the ABI the package manager selected
     * for this process (for example {@code .../lib/arm64} or {@code .../lib/x86_64}).
     */
    private static String nativeLibraryDir() {
        try {
            return InstrumentationRegistry.getInstrumentation()
                    .getTargetContext().getApplicationInfo().nativeLibraryDir;
        } catch (Throwable failure) {
            return "unavailable: " + describe(failure);
        }
    }

    /**
     * Reads a system property. There is no public API for this, and the probe wants
     * {@code ro.product.cpu.abilist} verbatim rather than the framework's parsed view of it.
     */
    private static String systemProperty(String key) {
        try {
            Class<?> systemProperties = Class.forName("android.os.SystemProperties");
            Object value = systemProperties.getMethod("get", String.class).invoke(null, key);
            String text = value == null ? "" : value.toString();
            return text.isEmpty() ? "<unset>" : text;
        } catch (Throwable failure) {
            return "unreadable: " + describe(failure);
        }
    }

    private static String describe(Throwable failure) {
        return failure.getClass().getName() + ": " + failure.getMessage();
    }

    private static void log(String message) {
        // Tag and message both carry the marker, so `logcat -s ABI_PROBE:*` and a plain
        // `grep ABI_PROBE` over an unfiltered log both find every line.
        Log.i(TAG, "ABI_PROBE " + message);
    }
}
