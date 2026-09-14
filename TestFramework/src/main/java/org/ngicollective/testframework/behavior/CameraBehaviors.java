package org.ngicollective.testframework.behavior;

/** Ready-made {@link Behavior}s for a simulated camera. */
public final class CameraBehaviors {

    private CameraBehaviors() {
    }

    /**
     * Delivers frames at the camera's configured rate, off the simulated clock.
     *
     * <p>The ordinary case, and the only one so far. A stalled or frame-dropping camera is a real
     * failure mode and this is where injecting it will go, but an unused preset would be scaffold
     * rather than a feature.</p>
     */
    public static Behavior<CameraState> streaming() {
        return new Behavior<CameraState>() {
            @Override
            public void advance(CameraState state, double elapsedSeconds) {
                state.tick(elapsedSeconds);
            }
        };
    }
}
