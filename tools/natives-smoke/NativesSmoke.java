import org.openftc.apriltag.AprilTagDetectorJNI;

/**
 * Proves a vision-natives build loads and runs on this machine's JVM, the way the FTC SDK uses it.
 *
 * <p>Loading {@code AprilTagDetectorJNI} runs its static initializer, which calls
 * {@code System.loadLibrary("apriltag")}: the same lookup the SDK makes, through
 * {@code java.library.path} and the platform's own file naming. Creating a detector for the 36h11
 * family then runs AprilTag's C code and returns a native pointer, and releasing it frees it. A
 * library that is missing, misnamed, built for the wrong architecture or missing its JNI exports
 * fails here rather than in a team's first vision test.</p>
 *
 * <p>Run by .github/workflows/vision-natives.yml on every platform it builds, against OpenFTC's
 * published Java classes (org.openftc:apriltag).</p>
 */
public final class NativesSmoke {

    private NativesSmoke() {
    }

    public static void main(String[] args) {
        long detector = AprilTagDetectorJNI.createApriltagDetector(
                AprilTagDetectorJNI.TagFamily.TAG_36h11.string, 3.0f, 3);
        if (detector == 0) {
            System.err.println("natives-smoke: createApriltagDetector returned a null pointer");
            System.exit(1);
        }
        AprilTagDetectorJNI.releaseApriltagDetector(detector);
        System.out.println("natives-smoke: AprilTag loaded, created and released a 36h11 detector on "
                + System.getProperty("os.name") + " " + System.getProperty("os.arch"));
    }
}
