package org.ngicollective.ftcsim.behavior;

/** Presets for a simulated goBILDA Pinpoint. */
public final class PinpointBehaviors {

    private PinpointBehaviors() {
    }

    /**
     * Pods on the floor and a working gyro: the device counts the chassis's true motion and
     * decodes it with whatever it was configured with.
     */
    public static Behavior<PinpointState> tracking() {
        return (state, elapsedSeconds) -> state.track(elapsedSeconds);
    }
}
