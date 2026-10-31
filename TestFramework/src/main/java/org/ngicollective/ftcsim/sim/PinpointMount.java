package org.ngicollective.ftcsim.sim;

/**
 * Where a goBILDA Pinpoint's two odometry pods physically sit on the robot, and how they count.
 *
 * <p>This is the robot, not the OpMode's opinion of it. An OpMode tells the Pinpoint its pod
 * offsets, directions and resolution through the driver; the simulated device then decodes the
 * ticks these pods produced with whatever it was told. When the two disagree the reported pose is
 * wrong in the same way it would be on the field, which is what makes a tuning procedure worth
 * running against the simulator.</p>
 *
 * <p>Offsets follow the Pinpoint's own convention: the X (forward) pod's offset is how far
 * <em>left</em> of the tracking point it sits, and the Y (strafe) pod's is how far
 * <em>forward</em>.</p>
 */
public final class PinpointMount {

    /** goBILDA's 4-Bar pod, in ticks per millimetre, as the SDK driver declares it. */
    public static final double GOBILDA_4_BAR_TICKS_PER_MM = 19.89436789;

    /** goBILDA's Swingarm pod, in ticks per millimetre, as the SDK driver declares it. */
    public static final double GOBILDA_SWINGARM_TICKS_PER_MM = 13.26291192;

    private final String name;
    private final double xPodLeftMetres;
    private final double yPodForwardMetres;
    private final double ticksPerMm;
    private final boolean xPodReversed;
    private final boolean yPodReversed;

    public PinpointMount(String name, double xPodLeftMetres, double yPodForwardMetres,
                         double ticksPerMm, boolean xPodReversed, boolean yPodReversed) {
        if (!(ticksPerMm > 0.0)) {
            throw new IllegalArgumentException("ticksPerMm must be positive, got " + ticksPerMm);
        }
        this.name = name;
        this.xPodLeftMetres = xPodLeftMetres;
        this.yPodForwardMetres = yPodForwardMetres;
        this.ticksPerMm = ticksPerMm;
        this.xPodReversed = xPodReversed;
        this.yPodReversed = yPodReversed;
    }

    /** Both pods on the tracking point, 4-Bar pods, counting the way the driver calls forward. */
    public static PinpointMount centred(String name) {
        return new PinpointMount(name, 0.0, 0.0, GOBILDA_4_BAR_TICKS_PER_MM, false, false);
    }

    static PinpointMount from(ConfigJson json) {
        return new PinpointMount(
                json.string("name"),
                json.number("xPodLeftMetres"),
                json.number("yPodForwardMetres"),
                json.positive("ticksPerMm"),
                json.bool("xPodReversed"),
                json.bool("yPodReversed"));
    }

    /** The name an OpMode looks the Pinpoint up by. */
    public String name() {
        return name;
    }

    public double xPodLeftMetres() {
        return xPodLeftMetres;
    }

    public double yPodForwardMetres() {
        return yPodForwardMetres;
    }

    public double ticksPerMm() {
        return ticksPerMm;
    }

    /** Whether the X pod counts down when the robot drives forward. */
    public boolean xPodReversed() {
        return xPodReversed;
    }

    /** Whether the Y pod counts down when the robot strafes left. */
    public boolean yPodReversed() {
        return yPodReversed;
    }
}
