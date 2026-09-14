package org.ngicollective.testframework.dashboard.protocol;

/**
 * How the simulation itself is being run: the time dilation, whether it is frozen, and which
 * alliance's point of view the session takes.
 *
 * <p>Broadcast on change rather than every tick. These are settings a person just made, and echoing
 * them back is what lets a second browser &mdash; or the same one after a reload &mdash; show the
 * truth instead of its own stale slider position.</p>
 */
public final class SimStatus {

    /** Simulated seconds per wall-clock second, clamped by the backend to a usable range. */
    public final double multiplier;
    /** True while simulated time is frozen; a paused session still streams pose and device state so
     * the UI stays readable, and can be advanced a control cycle at a time. */
    public final boolean paused;
    public final Alliance alliance;

    public SimStatus(double multiplier, boolean paused, Alliance alliance) {
        this.multiplier = multiplier;
        this.paused = paused;
        this.alliance = alliance;
    }
}
