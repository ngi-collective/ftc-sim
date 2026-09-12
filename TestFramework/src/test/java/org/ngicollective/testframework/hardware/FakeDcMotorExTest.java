package org.ngicollective.testframework.hardware;

import com.qualcomm.robotcore.hardware.DcMotor;
import com.qualcomm.robotcore.hardware.DcMotorSimple;

import org.firstinspires.ftc.robotcore.external.navigation.AngleUnit;
import org.firstinspires.ftc.robotcore.external.navigation.CurrentUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.ngicollective.testframework.behavior.MotorBehaviors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FakeDcMotorExTest {

    private FakeHardwareMap hardware;
    private FakeDcMotorEx motor;

    @BeforeEach
    void setUp() {
        hardware = FakeHardwareMap.builder().addMotor("drive").build();
        motor = hardware.motor("drive");
        motor.state().setMaxTicksPerSecond(1000.0);
    }

    @Test
    void idealMotorIntegratesPositionFromCommandedPower() {
        motor.setPower(0.5);

        hardware.advance(2.0);

        assertEquals(500.0, motor.getVelocity(), 1e-9);
        assertEquals(1000, motor.getCurrentPosition());
    }

    @Test
    void reversingSpinsTheShaftBackwardsWhileTheEncoderStillCountsUpForPositivePower() {
        motor.setDirection(DcMotorSimple.Direction.REVERSE);
        motor.setPower(1.0);

        hardware.advance(1.0);

        // The commanded frame is unchanged: positive power still reads as forward progress.
        assertEquals(1.0, motor.getPower(), 1e-9);
        assertEquals(1000, motor.getCurrentPosition());
        // The simulated shaft is what actually reversed.
        assertEquals(-1.0, motor.state().getPhysicalPower(), 1e-9);
        assertEquals(-1000.0, motor.state().getPosition(), 1e-9);
    }

    @Test
    void runToPositionStopsOnTargetAndClearsBusy() {
        motor.setTargetPosition(300);
        motor.setMode(DcMotor.RunMode.RUN_TO_POSITION);
        motor.setPower(1.0);

        hardware.advance(0.1);
        assertTrue(motor.isBusy());

        hardware.advance(1.0);

        assertEquals(300, motor.getCurrentPosition());
        assertFalse(motor.isBusy());
        assertEquals(0.0, motor.getVelocity(), 1e-9);
    }

    @Test
    void runToPositionDrivesBackwardsWhenTheTargetIsBehind() {
        motor.state().setPosition(500.0);
        motor.setTargetPosition(100);
        motor.setMode(DcMotor.RunMode.RUN_TO_POSITION);
        motor.setPower(1.0);

        hardware.advance(1.0);

        assertEquals(100, motor.getCurrentPosition());
    }

    @Test
    void stopAndResetEncoderZeroesThePosition() {
        motor.setPower(1.0);
        hardware.advance(1.0);

        motor.setMode(DcMotor.RunMode.STOP_AND_RESET_ENCODER);

        assertEquals(0, motor.getCurrentPosition());
    }

    @Test
    void aStalledMotorNeverMovesHoweverItIsCommanded() {
        motor.setBehavior(MotorBehaviors.stalled());
        motor.setPower(1.0);

        hardware.advance(5.0);

        assertEquals(0, motor.getCurrentPosition());
        // The OpMode still sees the power it asked for: a stall is invisible from the command side.
        assertEquals(1.0, motor.getPower(), 1e-9);
    }

    @Test
    void resetBehaviorRestoresWhatTheMotorWasBuiltWith() {
        motor.setBehavior(MotorBehaviors.stalled());
        motor.setPower(1.0);
        hardware.advance(1.0);

        motor.resetBehavior();
        hardware.advance(1.0);

        assertEquals(1000, motor.getCurrentPosition());
    }

    @Test
    void rampingMotorTakesItsRampTimeToReachFullSpeed() {
        motor.setBehavior(MotorBehaviors.ramping(1.0));
        motor.setPower(1.0);

        hardware.advance(0.5);
        assertEquals(500.0, motor.getVelocity(), 1e-9);

        hardware.advance(0.5);
        assertEquals(1000.0, motor.getVelocity(), 1e-9);

        hardware.advance(0.5);
        assertEquals(1000.0, motor.getVelocity(), 1e-9, "must not accelerate past the demand");
    }

    @Test
    void floatingCoastsDownSlowerThanBraking() {
        motor.setBehavior(MotorBehaviors.ramping(1.0, 3.0));
        motor.setPower(1.0);
        hardware.advance(1.0);

        motor.setZeroPowerBehavior(DcMotor.ZeroPowerBehavior.FLOAT);
        motor.setPower(0.0);
        hardware.advance(1.0);

        assertEquals(1000.0 - 1000.0 / 3.0, motor.getVelocity(), 1e-9);
    }

    @Test
    void setVelocityInTicksIsExpressedAsPower() {
        motor.setVelocity(250.0);

        assertEquals(0.25, motor.getPower(), 1e-9);
    }

    @Test
    void angularVelocityNeedsTheEncoderResolutionAndSaysSoWhenItIsMissing() {
        IllegalStateException thrown =
                assertThrows(IllegalStateException.class, () -> motor.getVelocity(AngleUnit.DEGREES));
        assertTrue(thrown.getMessage().contains("setTicksPerRevolution"), thrown.getMessage());

        motor.state().setTicksPerRevolution(500.0);
        motor.setPower(1.0);
        hardware.advance(1.0);

        assertEquals(720.0, motor.getVelocity(AngleUnit.DEGREES), 1e-9);
    }

    @Test
    void overCurrentTripsOnceTheSimulatedDrawPassesTheAlert() {
        motor.setCurrentAlert(4.0, CurrentUnit.AMPS);
        motor.state().setCurrentAmps(3.9);
        assertFalse(motor.isOverCurrent());

        motor.state().setCurrentAmps(4.1);

        assertTrue(motor.isOverCurrent());
        assertEquals(4100.0, motor.getCurrent(CurrentUnit.MILLIAMPS), 1e-6);
    }

    @Test
    void surfacesWithNoSimulationSaySoInsteadOfReturningNull() {
        assertThrows(UnsupportedOperationException.class, motor::getMotorType);
        assertThrows(UnsupportedOperationException.class, motor::getController);
    }
}
