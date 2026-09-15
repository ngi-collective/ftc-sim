package org.ngicollective.testframework.sim;

import org.ngicollective.testframework.camera.Pose3d;
import org.ngicollective.testframework.camera.Vec3;

/**
 * Where a camera is bolted to the robot: three distances and three angles, as measured.
 *
 * <p>The robot frame &mdash; +X out the nose, +Y to the robot's left, +Z up, origin on the floor
 * at the centre of the footprint &mdash; in metres, with yaw counter-clockwise from the nose,
 * pitch positive upward and roll about the optical axis.</p>
 *
 * <p>Degrees are kept, rather than the {@link Pose3d} they turn into. A mount makes a round trip
 * through the configuration file every time someone saves one from the dashboard, and radians
 * would not survive it: {@code toDegrees(toRadians(35.0))} is 35.000000000000004, which is what
 * would then be written into the team's file. The arithmetic that turns these six numbers into a
 * pose lives in {@link #pose()} and nowhere else, so the camera the dashboard aims and the camera
 * an OpMode looks through cannot be aimed by two different formulae.</p>
 */
public final class CameraMount {

    /**
     * How many decimal places of a degree {@link #degreesOf} will try to recover.
     *
     * <p>Twelve because a double carries about fifteen significant digits and an angle is at most
     * three of them to the left of the point; past that there is nothing left to round to.</p>
     */
    private static final int MAX_RECOVERED_PLACES = 12;

    private final double forwardMetres;
    private final double leftMetres;
    private final double heightMetres;
    private final double yawDegrees;
    private final double pitchDegrees;
    private final double rollDegrees;

    public CameraMount(double forwardMetres, double leftMetres, double heightMetres,
                       double yawDegrees, double pitchDegrees, double rollDegrees) {
        this.forwardMetres = forwardMetres;
        this.leftMetres = leftMetres;
        this.heightMetres = heightMetres;
        this.yawDegrees = yawDegrees;
        this.pitchDegrees = pitchDegrees;
        this.rollDegrees = rollDegrees;
    }

    /**
     * The mount a camera built from one is aimed by, read back out of it.
     *
     * <p>For a caller holding a camera rather than the file it was built from: the dashboard,
     * asking a freshly built robot where its webcam ended up.</p>
     */
    public static CameraMount of(Pose3d pose) {
        Vec3 position = pose.position();
        return new CameraMount(position.x(), position.y(), position.z(),
                degreesOf(pose.yaw()), degreesOf(pose.pitch()), degreesOf(pose.roll()));
    }

    /**
     * The degrees {@link Math#toRadians} turns into exactly {@code radians}.
     *
     * <p>Not {@link Math#toDegrees}, which does not invert it: {@code toDegrees(toRadians(60.0))}
     * is 59.99999999999999, and one in eight angles written to a tenth of a degree comes back
     * like that. Left alone, that number is what the dashboard would show on a slider nobody had
     * touched and what a save would then write into the team's hand-formatted configuration file,
     * which is the churn this class exists to prevent.</p>
     *
     * <p>The recovery is exact or not at all. A short decimal is accepted only when it converts
     * back to the identical double, so no value is ever rounded to something it was not; an angle
     * with no short spelling keeps every digit it has.</p>
     */
    private static double degreesOf(double radians) {
        double degrees = Math.toDegrees(radians);
        double scale = 1.0;
        for (int places = 0; places <= MAX_RECOVERED_PLACES; places++) {
            double candidate = Math.rint(degrees * scale) / scale;
            if (Math.toRadians(candidate) == radians) {
                return candidate;
            }
            scale *= 10.0;
        }
        return degrees;
    }

    /** Metres ahead of the robot's centre. */
    public double forwardMetres() {
        return forwardMetres;
    }

    /** Metres to the robot's left of centre. */
    public double leftMetres() {
        return leftMetres;
    }

    /** Metres above the floor. */
    public double heightMetres() {
        return heightMetres;
    }

    /** Degrees the camera is turned from straight ahead, counter-clockwise positive. */
    public double yawDegrees() {
        return yawDegrees;
    }

    /** Degrees the camera is aimed above the horizon. */
    public double pitchDegrees() {
        return pitchDegrees;
    }

    /** Degrees the camera is rolled about its own optical axis. */
    public double rollDegrees() {
        return rollDegrees;
    }

    /** This mount as a pose in the robot frame, which is what the renderer looks through. */
    public Pose3d pose() {
        return Pose3d.ofDegrees(new Vec3(forwardMetres, leftMetres, heightMetres),
                yawDegrees, pitchDegrees, rollDegrees);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof CameraMount)) {
            return false;
        }
        CameraMount that = (CameraMount) other;
        return Double.compare(forwardMetres, that.forwardMetres) == 0
                && Double.compare(leftMetres, that.leftMetres) == 0
                && Double.compare(heightMetres, that.heightMetres) == 0
                && Double.compare(yawDegrees, that.yawDegrees) == 0
                && Double.compare(pitchDegrees, that.pitchDegrees) == 0
                && Double.compare(rollDegrees, that.rollDegrees) == 0;
    }

    @Override
    public int hashCode() {
        int result = Double.hashCode(forwardMetres);
        result = 31 * result + Double.hashCode(leftMetres);
        result = 31 * result + Double.hashCode(heightMetres);
        result = 31 * result + Double.hashCode(yawDegrees);
        result = 31 * result + Double.hashCode(pitchDegrees);
        result = 31 * result + Double.hashCode(rollDegrees);
        return result;
    }

    @Override
    public String toString() {
        return String.format(
                "forward=%.3fm left=%.3fm height=%.3fm yaw=%.1f\u00b0 pitch=%.1f\u00b0"
                        + " roll=%.1f\u00b0",
                forwardMetres, leftMetres, heightMetres, yawDegrees, pitchDegrees, rollDegrees);
    }
}
