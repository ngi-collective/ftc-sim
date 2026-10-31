package org.ngicollective.ftcsim.behavior;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.ngicollective.ftcsim.sim.PinpointMount;
import org.ngicollective.ftcsim.sim.Pose2d;

class PinpointOdometryTest {

    private static final double TICKS_PER_MM = PinpointMount.GOBILDA_4_BAR_TICKS_PER_MM;

    /** Pods 0.1 m left and 0.05 m forward of centre. */
    private static final PinpointMount OFFSET_MOUNT =
            new PinpointMount("pinpoint", 0.10, 0.05, TICKS_PER_MM, false, false);

    private static PinpointOdometry.Settings told(double xOffsetMm, double yOffsetMm,
                                                  boolean xReversed, boolean yReversed) {
        return new PinpointOdometry.Settings(
                TICKS_PER_MM, xOffsetMm, yOffsetMm, xReversed, yReversed, 1.0);
    }

    /** Runs a path through the pods and the device in small steps, as a tick loop would. */
    private static PinpointOdometry.Estimate follow(PinpointMount mount,
                                                    PinpointOdometry.Settings settings,
                                                    Pose2d... path) {
        PinpointOdometry.Estimate estimate = PinpointOdometry.Estimate.ORIGIN;
        for (int i = 1; i < path.length; i++) {
            estimate = PinpointOdometry.integrate(estimate,
                    PinpointOdometry.podTicks(path[i - 1], path[i], mount),
                    PinpointOdometry.turned(path[i - 1], path[i]), settings);
        }
        return estimate;
    }

    @Test
    void drivingForwardTurnsOnlyTheXPod() {
        PinpointOdometry.PodTicks ticks = PinpointOdometry.podTicks(
                Pose2d.ORIGIN, new Pose2d(0.5, 0.0, 0.0), PinpointMount.centred("p"));

        assertEquals(500.0 * TICKS_PER_MM, ticks.x, 1e-6);
        assertEquals(0.0, ticks.y, 1e-9);
    }

    @Test
    void strafingLeftTurnsOnlyTheYPod() {
        PinpointOdometry.PodTicks ticks = PinpointOdometry.podTicks(
                Pose2d.ORIGIN, new Pose2d(0.0, 0.3, 0.0), PinpointMount.centred("p"));

        assertEquals(0.0, ticks.x, 1e-9);
        assertEquals(300.0 * TICKS_PER_MM, ticks.y, 1e-6);
    }

    @Test
    void aTurnInPlaceRollsAnOffsetPodByItsLeverArm() {
        PinpointOdometry.PodTicks ticks = PinpointOdometry.podTicks(
                Pose2d.ORIGIN, new Pose2d(0.0, 0.0, Math.PI / 2.0), OFFSET_MOUNT);

        // A pod left of centre runs backwards as the robot turns left; one ahead runs leftwards.
        assertEquals(-100.0 * Math.PI / 2.0 * TICKS_PER_MM, ticks.x, 1e-6);
        assertEquals(50.0 * Math.PI / 2.0 * TICKS_PER_MM, ticks.y, 1e-6);
    }

    @Test
    void correctOffsetsCancelTheLeverArmOfATurnInPlace() {
        PinpointOdometry.Estimate estimate = follow(OFFSET_MOUNT, told(100.0, 50.0, false, false),
                Pose2d.ORIGIN, new Pose2d(0.0, 0.0, Math.PI / 2.0));

        assertEquals(0.0, estimate.xMm, 1e-6);
        assertEquals(0.0, estimate.yMm, 1e-6);
        assertEquals(Math.PI / 2.0, estimate.headingRadians, 1e-9);
    }

    @Test
    void untoldOffsetsReadATurnInPlaceAsTravel() {
        PinpointOdometry.Estimate estimate = follow(OFFSET_MOUNT, told(0.0, 0.0, false, false),
                Pose2d.ORIGIN, new Pose2d(0.0, 0.0, Math.PI / 2.0));

        double drift = Math.hypot(estimate.xMm, estimate.yMm);
        assertTrue(drift > 50.0, "a mis-told Pinpoint should drift on a turn, drifted " + drift);
    }

    @Test
    void aCurvedPathIsTrackedWhenTheDeviceIsToldTheTruth() {
        Pose2d[] arc = new Pose2d[201];
        for (int i = 0; i < arc.length; i++) {
            double heading = Math.PI * i / (arc.length - 1);
            // A half circle of radius 0.5 m, facing along it.
            arc[i] = new Pose2d(0.5 * Math.sin(heading), 0.5 * (1.0 - Math.cos(heading)), heading);
        }

        PinpointOdometry.Estimate estimate = follow(OFFSET_MOUNT, told(100.0, 50.0, false, false), arc);

        assertEquals(0.0, estimate.xMm, 0.5);
        assertEquals(1000.0, estimate.yMm, 0.5);
        assertEquals(Math.PI, estimate.headingRadians, 1e-9);
    }

    @Test
    void aReversedPodToldItIsReversedTracksForward() {
        PinpointMount reversed = new PinpointMount("p", 0.0, 0.0, TICKS_PER_MM, true, false);

        PinpointOdometry.Estimate right = follow(reversed, told(0.0, 0.0, true, false),
                Pose2d.ORIGIN, new Pose2d(0.4, 0.0, 0.0));
        PinpointOdometry.Estimate wrong = follow(reversed, told(0.0, 0.0, false, false),
                Pose2d.ORIGIN, new Pose2d(0.4, 0.0, 0.0));

        assertEquals(400.0, right.xMm, 1e-3);
        assertEquals(-400.0, wrong.xMm, 1e-3);
    }
}
