package org.ngicollective.testframework.hardware;

import com.qualcomm.robotcore.hardware.DcMotorSimple;

import org.junit.jupiter.api.Test;
import org.ngicollective.testframework.behavior.CRServoBehaviors;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FakeCRServoTest {

    @Test
    void anIdealCrServoReachesTheCommandedPowerOnceATickHasAdvanced() {
        FakeCRServo intake = new FakeCRServo("intake", 0, CRServoBehaviors.ideal());

        intake.setPower(0.6);
        intake.advance(0.02);

        assertEquals(0.6, intake.state().getPhysicalPower(), 1e-9);
    }

    @Test
    void aReversedCrServoDrivesTheOppositeWayForTheSameCommandedPower() {
        FakeCRServo intake = new FakeCRServo("intake", 0, CRServoBehaviors.ideal());
        intake.setDirection(DcMotorSimple.Direction.REVERSE);

        intake.setPower(0.6);
        intake.advance(0.02);

        assertEquals(-0.6, intake.state().getPhysicalPower(), 1e-9);
        assertEquals(0.6, intake.getPower(), 1e-9,
                "real hardware echoes the command, so reversing must not change what getPower says");
    }

    @Test
    void aJammedCrServoReportsFullPowerWhileItsShaftDoesNothing() {
        FakeCRServo intake = new FakeCRServo("intake", 0, CRServoBehaviors.jammed());

        intake.setPower(1.0);
        intake.advance(0.02);

        assertEquals(1.0, intake.getPower(), 1e-9,
                "the OpMode has no feedback and cannot see this fault");
        assertEquals(0.0, intake.state().getPhysicalPower(), 1e-9);
    }

    @Test
    void aSlippingCrServoTurnsSlowerThanItWasAskedTo() {
        FakeCRServo intake = new FakeCRServo("intake", 0, CRServoBehaviors.slipping(0.25));

        intake.setPower(-0.8);
        intake.advance(0.02);

        assertEquals(-0.2, intake.state().getPhysicalPower(), 1e-9);
    }

    @Test
    void swappingInAJamMidRunStopsAShaftThatWasAlreadyTurning() {
        FakeCRServo intake = new FakeCRServo("intake", 0, CRServoBehaviors.ideal());
        intake.setPower(1.0);
        intake.advance(0.02);

        intake.setBehavior(CRServoBehaviors.jammed());
        intake.advance(0.02);

        assertEquals(0.0, intake.state().getPhysicalPower(), 1e-9);
    }
}
