package org.ngicollective.testframework.behavior;

import org.firstinspires.ftc.robotcore.external.navigation.AngleUnit;

/**
 * Simulated state of an IMU, in degrees.
 *
 * <p>{@code yaw} is the raw heading of the simulated robot. {@code yawOffset} is what
 * {@code IMU.resetYaw()} captured; the OpMode-visible heading is the difference, normalized to
 * (-180, 180].</p>
 */
public class ImuState {

    private volatile double yaw;
    private volatile double pitch;
    private volatile double roll;
    private volatile double yawOffset;
    private volatile double yawRateDegreesPerSecond;
    private volatile double chassisYawDegrees;

    /** Raw heading in degrees, before the reset offset. */
    public double getYaw() {
        return yaw;
    }

    public void setYaw(double yaw) {
        this.yaw = AngleUnit.normalizeDegrees(yaw);
    }

    public double getPitch() {
        return pitch;
    }

    public void setPitch(double pitch) {
        this.pitch = AngleUnit.normalizeDegrees(pitch);
    }

    public double getRoll() {
        return roll;
    }

    public void setRoll(double roll) {
        this.roll = AngleUnit.normalizeDegrees(roll);
    }

    public double getYawOffset() {
        return yawOffset;
    }

    public void setYawOffset(double yawOffset) {
        this.yawOffset = yawOffset;
    }

    /** Heading as the OpMode sees it: raw yaw minus the offset captured by {@code resetYaw()}. */
    public double reportedYaw() {
        return AngleUnit.normalizeDegrees(yaw - yawOffset);
    }

    /** Turn rate in degrees/second, read by rotating behaviors and reported as angular velocity. */
    public double getYawRateDegreesPerSecond() {
        return yawRateDegreesPerSecond;
    }

    public void setYawRateDegreesPerSecond(double yawRateDegreesPerSecond) {
        this.yawRateDegreesPerSecond = yawRateDegreesPerSecond;
    }

    /**
     * Heading of the chassis the IMU is bolted to, in degrees, as the drive model computed it from
     * the wheels.
     *
     * <p>Kept apart from {@link #getYaw()} on purpose: this is the truth, {@code yaw} is what the
     * sensor claims, and the gap between them is a fault worth being able to simulate. Only
     * {@link ImuBehaviors#followingChassis()} closes it.</p>
     */
    public double getChassisYawDegrees() {
        return chassisYawDegrees;
    }

    public void setChassisYawDegrees(double chassisYawDegrees) {
        this.chassisYawDegrees = AngleUnit.normalizeDegrees(chassisYawDegrees);
    }
}
