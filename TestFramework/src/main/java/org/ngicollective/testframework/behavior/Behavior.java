package org.ngicollective.testframework.behavior;

/**
 * Advances a simulated device's state as simulated time passes.
 *
 * <p>A fake device owns a mutable state object; the {@code Behavior} is the pluggable strategy that
 * decides how that state evolves between ticks. Swapping the behavior on a live device is how a
 * test (or the dashboard) injects a fault such as a stalled motor.</p>
 *
 * @param <S> the device's state type
 */
public interface Behavior<S> {

    /**
     * @param state           the device's mutable state, already updated with anything the OpMode
     *                        commanded since the previous call
     * @param elapsedSeconds  simulated seconds since the previous call; always &gt;= 0
     */
    void advance(S state, double elapsedSeconds);
}
