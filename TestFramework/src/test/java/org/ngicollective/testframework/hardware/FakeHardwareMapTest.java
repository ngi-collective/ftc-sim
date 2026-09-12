package org.ngicollective.testframework.hardware;

import com.qualcomm.robotcore.hardware.DcMotor;
import com.qualcomm.robotcore.hardware.DcMotorEx;
import com.qualcomm.robotcore.hardware.IMU;
import com.qualcomm.robotcore.hardware.Servo;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FakeHardwareMapTest {

    private final FakeHardwareMap hardware = FakeHardwareMap.builder()
            .addMotor("FL")
            .addServo("claw")
            .addImu("imu")
            .build();

    @Test
    void resolvesDevicesByEverySdkInterfaceTheyImplement() {
        FakeDcMotorEx motor = hardware.motor("FL");

        assertSame(motor, hardware.get(DcMotorEx.class, "FL"));
        assertSame(motor, hardware.get(DcMotor.class, "FL"));
        assertSame(hardware.servo("claw"), hardware.get(Servo.class, "claw"));
        assertSame(hardware.imu("imu"), hardware.get(IMU.class, "imu"));
    }

    @Test
    void tolerantOfTheWhitespaceTheSdkTrims() {
        assertSame(hardware.motor("FL"), hardware.get(DcMotorEx.class, "  FL "));
    }

    @Test
    void devicesAreAlsoReachableThroughTheSdkDeviceMappings() {
        assertSame(hardware.motor("FL"), hardware.dcMotor.get("FL"));
        assertSame(hardware.servo("claw"), hardware.servo.get("claw"));
    }

    @Test
    void unknownDeviceFailsTheWayTheRealHardwareMapDoes() {
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> hardware.get(DcMotorEx.class, "nonexistent"));

        assertTrue(thrown.getMessage().contains("nonexistent"), thrown.getMessage());
    }

    @Test
    void wrongTypeForAConfiguredNameDoesNotResolve() {
        assertNull(hardware.tryGet(Servo.class, "FL"));
        assertThrows(IllegalArgumentException.class, () -> hardware.get(Servo.class, "FL"));
    }

    @Test
    void duplicateDeviceNamesAreRejectedAtBuildTime() {
        assertThrows(IllegalArgumentException.class,
                () -> FakeHardwareMap.builder().addMotor("FL").addMotor("FL"));
    }

    @Test
    void typedAccessorRejectsAMismatchedType() {
        IllegalArgumentException thrown =
                assertThrows(IllegalArgumentException.class, () -> hardware.motor("claw"));

        assertTrue(thrown.getMessage().contains("FakeServo"), thrown.getMessage());
    }
}
