package org.ngicollective.testframework.behavior;

/** The built-in catalog of continuous-rotation servo behaviors. */
public final class CRServoBehaviors {

    private CRServoBehaviors() {
    }

    /** A servo whose shaft reaches the demanded power with no spin-up time. */
    public static Behavior<CRServoState> ideal() {
        return (state, elapsedSeconds) -> state.setPhysicalPower(state.demandedPower());
    }

    /**
     * A servo whose shaft does not turn however hard it is driven &mdash; a ball wedged in the
     * intake, or a stripped gear.
     *
     * <p>The commanded power is left alone, so {@code getPower()} keeps echoing the command. That
     * gap is the point: an OpMode that trusts its own commands cannot see this fault.</p>
     */
    public static Behavior<CRServoState> jammed() {
        return (state, elapsedSeconds) -> state.setPhysicalPower(0.0);
    }

    /**
     * A servo whose shaft turns slower than asked, as one does when its horn is slipping or the
     * battery is sagging.
     *
     * @param fraction share of the demanded power that reaches the shaft, 0 to 1
     */
    public static Behavior<CRServoState> slipping(double fraction) {
        if (!(fraction >= 0.0 && fraction <= 1.0)) {
            throw new IllegalArgumentException("fraction must be within [0, 1]");
        }
        return (state, elapsedSeconds) -> state.setPhysicalPower(state.demandedPower() * fraction);
    }
}
