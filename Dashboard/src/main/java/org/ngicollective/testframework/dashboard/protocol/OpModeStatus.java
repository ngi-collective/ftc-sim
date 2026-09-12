package org.ngicollective.testframework.dashboard.protocol;

/** What the session is doing right now, broadcast whenever it changes. */
public final class OpModeStatus {

    public enum State {
        /** Nothing selected or the previous run has finished. */
        STOPPED,
        /** Initialized and holding, waiting for start. */
        INIT,
        /** Started and running. */
        RUNNING
    }

    public final String opMode;
    public final State state;
    /** The failure that ended the run, or null. */
    public final String failure;

    public OpModeStatus(String opMode, State state, String failure) {
        this.opMode = opMode;
        this.state = state;
        this.failure = failure;
    }

    public static OpModeStatus stopped() {
        return new OpModeStatus(null, State.STOPPED, null);
    }
}
