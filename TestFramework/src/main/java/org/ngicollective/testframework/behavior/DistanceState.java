package org.ngicollective.testframework.behavior;

/**
 * Simulated state of a distance sensor, in metres.
 *
 * <p>{@link #worldMetres()} is the range the simulated field computed along the sensor's ray;
 * {@link #reportedMetres()} is what the OpMode reads. Both use {@code NaN} for "nothing in range",
 * which is what a real optical sensor gives back once the return signal is too weak to time.</p>
 */
public class DistanceState {

    private volatile double worldMetres = Double.NaN;
    private volatile double reportedMetres = Double.NaN;

    /** Distance to the nearest simulated obstacle along the ray, or {@code NaN} if there is none. */
    public double worldMetres() {
        return worldMetres;
    }

    public void setWorldMetres(double worldMetres) {
        this.worldMetres = worldMetres;
    }

    /** Distance as the OpMode reads it, or {@code NaN} when the sensor claims nothing is in range. */
    public double reportedMetres() {
        return reportedMetres;
    }

    public void setReportedMetres(double reportedMetres) {
        this.reportedMetres = reportedMetres;
    }
}
