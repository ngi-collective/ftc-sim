package org.ngicollective.testframework.hardware;

import com.qualcomm.robotcore.hardware.HardwareDevice;

import org.ngicollective.testframework.behavior.Behavior;

/**
 * Base class for a simulated hardware device: a configured name, a mutable state object, and the
 * {@link Behavior} that advances it.
 *
 * @param <S> the device's state type
 */
public abstract class FakeDevice<S> implements HardwareDevice {

    private final String configuredName;
    private final S state;
    private final Behavior<S> declaredBehavior;
    private volatile Behavior<S> behavior;
    private volatile String behaviorLabel = "default";

    protected FakeDevice(String configuredName, S state, Behavior<S> declaredBehavior) {
        this.configuredName = configuredName;
        this.state = state;
        this.declaredBehavior = declaredBehavior;
        this.behavior = declaredBehavior;
    }

    /** The name this device is configured under in the {@link FakeHardwareMap}. */
    public String configuredName() {
        return configuredName;
    }

    /** The mutable simulated state. Read it to assert, write it to force a condition. */
    public S state() {
        return state;
    }

    public Behavior<S> behavior() {
        return behavior;
    }

    /**
     * What the current behavior is called, for a dashboard or failure message to show.
     *
     * <p>{@code "default"} until something overrides it; the name an override supplied, or
     * {@code "custom"} for a behavior swapped in from code without one.</p>
     */
    public String behaviorLabel() {
        return behaviorLabel;
    }

    /** Swaps in a different behavior mid-run &mdash; this is how a fault gets injected. */
    public void setBehavior(Behavior<S> behavior) {
        setBehavior("custom", behavior);
    }

    /** Swaps in a different behavior under a name a dashboard can display. */
    public void setBehavior(String label, Behavior<S> behavior) {
        if (behavior == null) {
            throw new IllegalArgumentException("behavior must not be null");
        }
        this.behavior = behavior;
        this.behaviorLabel = label;
    }

    /** Restores the behavior this device was built with. */
    public void resetBehavior() {
        this.behavior = declaredBehavior;
        this.behaviorLabel = "default";
    }

    /** Advances this device by {@code elapsedSeconds} of simulated time. */
    public void advance(double elapsedSeconds) {
        if (elapsedSeconds < 0.0) {
            throw new IllegalArgumentException("cannot advance simulated time backwards");
        }
        behavior.advance(state, elapsedSeconds);
    }

    @Override
    public Manufacturer getManufacturer() {
        return Manufacturer.Other;
    }

    @Override
    public String getConnectionInfo() {
        return "simulated";
    }

    @Override
    public int getVersion() {
        return 1;
    }

    @Override
    public void close() {
        // Nothing to release.
    }

    @Override
    public String toString() {
        return getDeviceName() + "(\"" + configuredName + "\")";
    }
}
