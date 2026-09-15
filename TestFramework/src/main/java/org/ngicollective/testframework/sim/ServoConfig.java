package org.ngicollective.testframework.sim;

/**
 * One servo as the configuration file describes it: whether it spins or aims, and what it does to
 * the balls it reaches.
 *
 * <p>A claw or an aiming servo needs nothing here beyond its type &mdash; the simulator has no
 * reason to model what its horn is near. A continuous-rotation intake does, because a ball inside
 * its mouth gets dragged, and a test about collecting balls is only worth writing if collecting
 * them happens. Declaring a {@link #sweep()} volume is how a servo says it is that kind of
 * mechanism.</p>
 */
public final class ServoConfig {

    /** A servo that spins for as long as it is powered: an intake roller. */
    private static final String CONTINUOUS = "continuous";

    /** A servo that holds the angle it was commanded to: a claw, or an aimer. */
    private static final String POSITIONAL = "positional";

    private final String name;
    private final boolean continuous;
    private final VolumeConfig sweep;
    private final double surfaceSpeed;

    ServoConfig(String name, boolean continuous, VolumeConfig sweep,
                double surfaceMetresPerSecond) {
        this.name = name;
        this.continuous = continuous;
        this.sweep = sweep;
        this.surfaceSpeed = surfaceMetresPerSecond;
    }

    static ServoConfig from(String name, ConfigJson json) {
        String type = json.string("type");
        boolean continuous;
        if (CONTINUOUS.equals(type)) {
            continuous = true;
        } else if (POSITIONAL.equals(type)) {
            continuous = false;
        } else {
            // Loudly, because the two types are simulated by different devices: a misspelt
            // "continous" that fell back to positional would produce an intake that aims instead
            // of spinning, and nothing downstream could tell that from an intake commanded badly.
            throw new IllegalArgumentException(json.source() + ": servo \"" + name
                    + "\" has \"type\": \"" + type + "\", which is not one of "
                    + CONTINUOUS + ", " + POSITIONAL);
        }
        if (!json.names().contains("sweep")) {
            return new ServoConfig(name, continuous, null, 0.0);
        }
        // A sweep without a surface speed would drag balls at nothing per second, which is the
        // same as not dragging them, so the pair is required together.
        return new ServoConfig(name, continuous,
                VolumeConfig.from(json.child("sweep")),
                json.positive("surfaceMetresPerSecond"));
    }

    /** The name an OpMode looks this servo up under. */
    public String name() {
        return name;
    }

    /** Whether this is a continuous-rotation servo, commanded by power rather than by position. */
    public boolean continuous() {
        return continuous;
    }

    /** Whether this servo drags the balls that enter its {@link #sweep()}. */
    public boolean sweepsBalls() {
        return sweep != null;
    }

    /**
     * The space this servo's rollers reach into, or null when it reaches into none.
     *
     * <p>Sized against the game pieces it has to swallow: a POLLEN ball is 0.071&nbsp;m across and
     * a NECTAR 0.091&nbsp;m, so a mouth under about 0.09&nbsp;m tall quietly refuses the larger
     * one.</p>
     */
    public VolumeConfig sweep() {
        return sweep;
    }

    /**
     * How fast the roller surface moves at full commanded power, in metres/second: the speed a
     * ball held against it is dragged at.
     *
     * <p>A surface speed rather than an RPM because the radius of what is bolted to the horn is
     * not written down anywhere, and the only thing the physics needs is the speed of the rubber
     * touching the ball. Zero when {@link #sweepsBalls()} is false.</p>
     */
    public double surfaceMetresPerSecond() {
        return surfaceSpeed;
    }
}
