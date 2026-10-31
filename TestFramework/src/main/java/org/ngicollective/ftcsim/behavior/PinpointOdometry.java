package org.ngicollective.ftcsim.behavior;

import org.firstinspires.ftc.robotcore.external.navigation.AngleUnit;
import org.ngicollective.ftcsim.sim.PinpointMount;
import org.ngicollective.ftcsim.sim.Pose2d;

/**
 * The arithmetic of a two-pod odometry computer, as pure functions.
 *
 * <p>Two halves, and keeping them apart is the point. {@link #podTicks} is the robot: how far each
 * physical pod's wheel turned while the chassis moved from one pose to another.
 * {@link #integrate} is the device: what it concludes from those ticks given the offsets,
 * directions and resolution it was <em>told</em>. A Pinpoint configured to match its pods tracks
 * the chassis exactly; one configured wrongly drifts in the direction the error points.</p>
 */
public final class PinpointOdometry {

    private PinpointOdometry() {
    }

    /** Ticks counted by the two pods, before the device applies any direction setting. */
    public static final class PodTicks {

        public final double x;
        public final double y;

        public PodTicks(double x, double y) {
            this.x = x;
            this.y = y;
        }
    }

    /** What the device was told about its pods. Millimetres, as the registers hold them. */
    public static final class Settings {

        public final double ticksPerMm;
        public final double xPodOffsetMm;
        public final double yPodOffsetMm;
        public final boolean xReversed;
        public final boolean yReversed;
        public final double yawScalar;

        public Settings(double ticksPerMm, double xPodOffsetMm, double yPodOffsetMm,
                        boolean xReversed, boolean yReversed, double yawScalar) {
            this.ticksPerMm = ticksPerMm;
            this.xPodOffsetMm = xPodOffsetMm;
            this.yPodOffsetMm = yPodOffsetMm;
            this.xReversed = xReversed;
            this.yReversed = yReversed;
            this.yawScalar = yawScalar;
        }
    }

    /** The device's pose estimate: millimetres, and radians that keep counting past a turn. */
    public static final class Estimate {

        public static final Estimate ORIGIN = new Estimate(0.0, 0.0, 0.0);

        public final double xMm;
        public final double yMm;
        public final double headingRadians;

        public Estimate(double xMm, double yMm, double headingRadians) {
            this.xMm = xMm;
            this.yMm = yMm;
            this.headingRadians = headingRadians;
        }
    }

    /**
     * How far each pod's wheel turned while the chassis moved from {@code before} to
     * {@code after}.
     *
     * <p>A pod rolls on the floor, so it measures the chassis's true motion at the point where it
     * is mounted: wheel slip and wall contact that fool the drive encoders do not fool it. A
     * rotation moves a pod as well as a translation does, by the turn times its lever arm, which is
     * why the offsets exist at all.</p>
     */
    public static PodTicks podTicks(Pose2d before, Pose2d after, PinpointMount mount) {
        double turned = turned(before, after);
        double midHeading = before.heading() + turned / 2.0;
        double dx = after.x() - before.x();
        double dy = after.y() - before.y();
        double forward = Math.cos(midHeading) * dx + Math.sin(midHeading) * dy;
        double left = -Math.sin(midHeading) * dx + Math.cos(midHeading) * dy;

        double xPodMetres = forward - mount.xPodLeftMetres() * turned;
        double yPodMetres = left + mount.yPodForwardMetres() * turned;
        double ticksPerMetre = mount.ticksPerMm() * 1000.0;
        return new PodTicks(
                sign(mount.xPodReversed()) * xPodMetres * ticksPerMetre,
                sign(mount.yPodReversed()) * yPodMetres * ticksPerMetre);
    }

    /** The heading change from {@code before} to {@code after}, the short way round. */
    public static double turned(Pose2d before, Pose2d after) {
        return AngleUnit.normalizeRadians(after.heading() - before.heading());
    }

    /**
     * Applies the device's direction setting to raw pod ticks, which is what its encoder registers
     * report: the driver documents a FORWARD X pod as one that "should increase when the robot is
     * moving forward".
     */
    public static PodTicks directed(PodTicks raw, Settings settings) {
        return new PodTicks(sign(settings.xReversed) * raw.x, sign(settings.yReversed) * raw.y);
    }

    /**
     * The device's next estimate, from the ticks its pods counted and the turn its IMU measured.
     *
     * <p>Undoes the lever-arm motion with the offsets it was told, then rotates the robot-relative
     * step into the estimate's own frame about the mid-step heading.</p>
     */
    public static Estimate integrate(Estimate estimate, PodTicks raw, double imuTurnRadians,
                                     Settings settings) {
        PodTicks counted = directed(raw, settings);
        double turned = imuTurnRadians * settings.yawScalar;
        double forwardMm = counted.x / settings.ticksPerMm + settings.xPodOffsetMm * turned;
        double leftMm = counted.y / settings.ticksPerMm - settings.yPodOffsetMm * turned;
        double midHeading = estimate.headingRadians + turned / 2.0;
        return new Estimate(
                estimate.xMm + Math.cos(midHeading) * forwardMm - Math.sin(midHeading) * leftMm,
                estimate.yMm + Math.sin(midHeading) * forwardMm + Math.cos(midHeading) * leftMm,
                estimate.headingRadians + turned);
    }

    private static double sign(boolean reversed) {
        return reversed ? -1.0 : 1.0;
    }
}
