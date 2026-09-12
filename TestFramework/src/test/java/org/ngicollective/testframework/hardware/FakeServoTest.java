package org.ngicollective.testframework.hardware;

import com.qualcomm.robotcore.hardware.Servo;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.ngicollective.testframework.behavior.ServoBehaviors;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FakeServoTest {

    private FakeHardwareMap hardware;
    private FakeServo servo;

    @BeforeEach
    void setUp() {
        hardware = FakeHardwareMap.builder().addServo("claw").build();
        servo = hardware.servo("claw");
    }

    @Test
    void instantServoIsAtTheCommandedPositionAfterOneTick() {
        servo.setPosition(0.75);

        hardware.advance(0.001);

        assertEquals(0.75, servo.state().getPosition(), 1e-9);
    }

    @Test
    void getPositionEchoesTheCommandEvenWhileTheHornIsStillTravelling() {
        servo.setBehavior(ServoBehaviors.sweeping(1.0));
        servo.setPosition(1.0);

        hardware.advance(0.25);

        assertEquals(1.0, servo.getPosition(), 1e-9, "a real servo has no position feedback");
        assertEquals(0.25, servo.state().getPosition(), 1e-9);
    }

    @Test
    void scaleRangeMapsTheCommandIntoTheAllowedTravel() {
        servo.scaleRange(0.2, 0.6);
        servo.setPosition(0.5);

        hardware.advance(0.001);

        assertEquals(0.4, servo.state().getPosition(), 1e-9);
    }

    @Test
    void reverseMirrorsTheHornAcrossTheTravel() {
        servo.setDirection(Servo.Direction.REVERSE);
        servo.setPosition(0.3);

        hardware.advance(0.001);

        assertEquals(0.7, servo.state().getPosition(), 1e-9);
    }
}
