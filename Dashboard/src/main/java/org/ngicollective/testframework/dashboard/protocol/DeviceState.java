package org.ngicollective.testframework.dashboard.protocol;

/**
 * A flattened snapshot of one simulated device, shaped for the wire.
 *
 * <p>Deliberately one flat type rather than a class hierarchy: {@code kind} tells the UI which
 * fields carry meaning, and unused fields stay zero. That keeps the JSON stable and the TypeScript
 * side a single discriminated union.</p>
 */
public final class DeviceState {

    public final String name;
    /** {@code "motor"}, {@code "servo"} or {@code "imu"}. */
    public final String kind;
    /** The behavior currently driving this device, for the UI to show and to offer a reset from. */
    public final String behavior;

    // motor
    public double commandedPower;
    public double physicalPower;
    public double velocityTicksPerSecond;
    public int position;
    public String mode;

    // servo
    public double commandedPosition;
    public double hornPosition;

    // imu
    public double yawDegrees;
    public double yawRateDegreesPerSecond;

    public DeviceState(String name, String kind, String behavior) {
        this.name = name;
        this.kind = kind;
        this.behavior = behavior;
    }
}
