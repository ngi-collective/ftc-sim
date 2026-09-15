package org.ngicollective.testframework.behavior;

/**
 * Simulated state of a battery voltage sensor.
 *
 * <p>{@link #worldVolts()} is the pack voltage the simulation is modelling, sag and all;
 * {@link #reportedVolts()} is what the OpMode reads.</p>
 */
public class VoltageState {

    private volatile double worldVolts;
    private volatile double reportedVolts;

    /** Voltage the simulated battery is really at. */
    public double worldVolts() {
        return worldVolts;
    }

    public void setWorldVolts(double worldVolts) {
        this.worldVolts = worldVolts;
    }

    /** Voltage as {@code VoltageSensor.getVoltage()} returns it. */
    public double reportedVolts() {
        return reportedVolts;
    }

    public void setReportedVolts(double reportedVolts) {
        this.reportedVolts = reportedVolts;
    }
}
