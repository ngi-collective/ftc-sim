package org.ngicollective.testframework.dashboard.protocol;

/**
 * Where the simulated robot is and how fast it is going, pushed every control cycle.
 *
 * <p>The pose is in the FTC field frame &mdash; origin at field centre, metres, heading CCW-positive
 * with zero facing +X &mdash; because that is the frame the drive model, the autonomous paths and
 * the field drawings all already use. The browser is the only place that ever converts, into
 * Three.js' Y-up scene frame, and it does that at draw time.</p>
 *
 * <p>Angles cross the wire in degrees even though the model works in radians: every number here is
 * read by a human or a UI widget, and radians in a readout are a needless conversion for the reader
 * to do in their head.</p>
 */
public final class SimPose {

    /** Wall clock, so the UI can tell a stale stream from a stopped robot. */
    public final long timestampMillis;
    /** Simulated seconds since the hardware map was built; diverges from wall time under a
     * multiplier or a pause, which is exactly what makes it worth sending. */
    public final double elapsedSeconds;

    public final double x;
    public final double y;
    public final double headingDegrees;

    /** Robot-frame chassis velocity: forward, and left-positive lateral, in metres/second. */
    public final double forwardVelocity;
    public final double lateralVelocity;
    public final double yawRateDegreesPerSecond;

    /** True while the chassis is pressed against a field wall, so the UI can explain why commanded
     * power is producing no motion. */
    public final boolean wallContact;

    public SimPose(long timestampMillis, double elapsedSeconds, double x, double y,
                   double headingDegrees, double forwardVelocity, double lateralVelocity,
                   double yawRateDegreesPerSecond, boolean wallContact) {
        this.timestampMillis = timestampMillis;
        this.elapsedSeconds = elapsedSeconds;
        this.x = x;
        this.y = y;
        this.headingDegrees = headingDegrees;
        this.forwardVelocity = forwardVelocity;
        this.lateralVelocity = lateralVelocity;
        this.yawRateDegreesPerSecond = yawRateDegreesPerSecond;
        this.wallContact = wallContact;
    }
}
