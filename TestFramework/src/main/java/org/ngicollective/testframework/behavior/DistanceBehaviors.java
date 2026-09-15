package org.ngicollective.testframework.behavior;

/** The built-in catalog of distance sensor behaviors. */
public final class DistanceBehaviors {

    private DistanceBehaviors() {
    }

    /** A working sensor: it reports whatever the simulated field put in front of it. */
    public static Behavior<DistanceState> measuring() {
        return (state, elapsedSeconds) -> state.setReportedMetres(state.worldMetres());
    }

    /**
     * A sensor that never sees anything, the way a real one behaves past its range or with its
     * window covered in tape. It reports {@code NaN} even with a wall in front of it.
     */
    public static Behavior<DistanceState> blind() {
        return (state, elapsedSeconds) -> state.setReportedMetres(Double.NaN);
    }

    /**
     * A sensor frozen on one reading. A ranging sensor that has stopped updating is the worst kind
     * of failure to find from an OpMode, because the number it returns is entirely plausible.
     *
     * @param metres the reading it is stuck on
     */
    public static Behavior<DistanceState> stuckAt(double metres) {
        return (state, elapsedSeconds) -> state.setReportedMetres(metres);
    }
}
