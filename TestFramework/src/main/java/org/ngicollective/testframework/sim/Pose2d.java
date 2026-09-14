package org.ngicollective.testframework.sim;

import org.firstinspires.ftc.robotcore.external.navigation.AngleUnit;

/**
 * Where the robot is on the field: metres from field centre, heading in radians.
 *
 * <p>One frame, used everywhere in Java and on the wire: origin at field centre, +X and +Y in the
 * floor plane, heading counter-clockwise-positive with 0 meaning the nose points along +X. The
 * browser's scene frame (Y up, nose at -Z) is a different thing entirely, and the conversion lives
 * in the browser so that no Java code ever has to hold both conventions in its head.</p>
 *
 * <p>Immutable, and non-finite components are rejected at construction. A pose is the output every
 * other part of this simulator is judged against: a NaN that leaks in from a divide-by-zero
 * upstream would otherwise spread silently into the encoders, the telemetry and the 3D scene, and
 * the first symptom would be a robot that vanished from the field.</p>
 */
public final class Pose2d {

    /** Field centre, nose along +X. */
    public static final Pose2d ORIGIN = new Pose2d(0.0, 0.0, 0.0);

    private final double x;
    private final double y;
    private final double heading;

    public Pose2d(double xMetres, double yMetres, double headingRadians) {
        if (!isFinite(xMetres) || !isFinite(yMetres) || !isFinite(headingRadians)) {
            throw new IllegalArgumentException("a pose must be finite; got x=" + xMetres
                    + " y=" + yMetres + " heading=" + headingRadians + " (radians)");
        }
        this.x = xMetres;
        this.y = yMetres;
        // Normalized to (-pi, pi] on the way in, so two poses that describe the same attitude are
        // never a full turn apart when something subtracts them.
        this.heading = AngleUnit.normalizeRadians(headingRadians);
    }

    /** Metres from field centre along +X. */
    public double x() {
        return x;
    }

    /** Metres from field centre along +Y. */
    public double y() {
        return y;
    }

    /** Heading in radians, counter-clockwise-positive, normalized to (-pi, pi]. */
    public double heading() {
        return heading;
    }

    /** Heading in degrees &mdash; the unit the wire protocol and the driver's eyes both use. */
    public double headingDegrees() {
        return Math.toDegrees(heading);
    }

    @Override
    public String toString() {
        return String.format("(%.3fm, %.3fm, %.1f\u00b0)", x, y, headingDegrees());
    }

    private static boolean isFinite(double value) {
        return !Double.isNaN(value) && !Double.isInfinite(value);
    }
}
