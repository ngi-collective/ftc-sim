package org.ngicollective.testframework.hardware;

import org.firstinspires.ftc.robotcore.external.navigation.AngleUnit;
import org.firstinspires.ftc.robotcore.external.navigation.AxesOrder;
import org.firstinspires.ftc.robotcore.external.navigation.AxesReference;
import org.junit.jupiter.api.Test;
import org.ngicollective.testframework.behavior.ImuBehaviors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FakeImuTest {

    @Test
    void rotatingBehaviorTurnsTheRobotOverSimulatedTime() {
        FakeHardwareMap hardware = FakeHardwareMap.builder()
                .addImu("imu", ImuBehaviors.rotating(90.0))
                .build();

        hardware.advance(1.0);

        assertEquals(90.0, hardware.imu("imu").getRobotYawPitchRollAngles().getYaw(AngleUnit.DEGREES), 1e-6);
        assertEquals(90.0, hardware.imu("imu").getRobotAngularVelocity(AngleUnit.DEGREES).zRotationRate, 1e-6);
    }

    @Test
    void headingWrapsTheWayTheSdkNormalizesIt() {
        FakeHardwareMap hardware = FakeHardwareMap.builder()
                .addImu("imu", ImuBehaviors.rotating(90.0))
                .build();

        hardware.advance(3.0);

        assertEquals(-90.0, hardware.imu("imu").getRobotYawPitchRollAngles().getYaw(AngleUnit.DEGREES), 1e-6);
    }

    @Test
    void resetYawRezeroesTheReportedHeadingWithoutMovingTheRobot() {
        FakeHardwareMap hardware = FakeHardwareMap.builder()
                .addImu("imu", ImuBehaviors.rotating(45.0))
                .build();
        FakeImu imu = hardware.imu("imu");
        hardware.advance(1.0);

        imu.resetYaw();

        assertEquals(0.0, imu.getRobotYawPitchRollAngles().getYaw(AngleUnit.DEGREES), 1e-6);
        assertEquals(45.0, imu.state().getYaw(), 1e-6, "the robot has not turned back");

        hardware.advance(1.0);
        assertEquals(45.0, imu.getRobotYawPitchRollAngles().getYaw(AngleUnit.DEGREES), 1e-6);
    }

    @Test
    void orientationAgreesWithTheYawPitchRollView() {
        FakeHardwareMap hardware = FakeHardwareMap.builder().addImu("imu").build();
        FakeImu imu = hardware.imu("imu");
        imu.state().setYaw(30.0);

        float firstAngle = imu.getRobotOrientation(
                AxesReference.INTRINSIC, AxesOrder.ZYX, AngleUnit.DEGREES).firstAngle;

        assertEquals(30.0f, firstAngle, 1e-4);
    }

    @Test
    void recordsWhetherTheOpModeEverInitializedIt() {
        FakeHardwareMap hardware = FakeHardwareMap.builder().addImu("imu").build();
        FakeImu imu = hardware.imu("imu");
        assertFalse(imu.isInitialized());

        imu.initialize(null);

        assertTrue(imu.isInitialized());
    }
}
