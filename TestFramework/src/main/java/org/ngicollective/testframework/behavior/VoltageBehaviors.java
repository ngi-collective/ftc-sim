package org.ngicollective.testframework.behavior;

/** The built-in catalog of voltage sensor behaviors. */
public final class VoltageBehaviors {

    private VoltageBehaviors() {
    }

    /** A working sensor: it reports the simulated pack voltage. */
    public static Behavior<VoltageState> reporting() {
        return (state, elapsedSeconds) -> state.setReportedVolts(state.worldVolts());
    }

    /**
     * A sensor that reads the same voltage forever.
     *
     * <p>This hides a sagging pack, so voltage compensation code sees a healthy battery while the
     * simulated motors are being starved &mdash; the end-of-match brownout that is impossible to
     * reproduce on the field on purpose.</p>
     *
     * @param volts the constant it reports
     */
    public static Behavior<VoltageState> flat(double volts) {
        return (state, elapsedSeconds) -> state.setReportedVolts(volts);
    }
}
