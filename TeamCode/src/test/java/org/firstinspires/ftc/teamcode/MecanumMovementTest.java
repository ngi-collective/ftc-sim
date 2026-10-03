package org.firstinspires.ftc.teamcode;

import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MecanumMovementTest {

    @Test
    void mecanumLeft() {
        MecanumMovement movement = new MecanumMovement();
        JoystickInput jsl = new JoystickInput();
        JoystickInput jsr = new JoystickInput();
        jsl.update(-1.0, 0.0);

        ChassisMotors motors = movement.calculate(jsl, jsr);

        assertEquals(-1.0, motors.getFL(), 1e-6);
        assertEquals(1.0, motors.getBL(), 1e-6);
        assertEquals(1.0, motors.getFR(), 1e-6);
        assertEquals(-1.0, motors.getBR(), 1e-6);
    }

    @Test
    void mecanumRight() {
        MecanumMovement movement = new MecanumMovement();
        JoystickInput jsl = new JoystickInput();
        JoystickInput jsr = new JoystickInput();
        jsl.update(1.0, 0.0);

        ChassisMotors motors = movement.calculate(jsl, jsr);

        assertEquals(1.0, motors.getFL(), 1e-6);
        assertEquals(-1.0, motors.getBL(), 1e-6);
        assertEquals(-1.0, motors.getFR(), 1e-6);
        assertEquals(1.0, motors.getBR(), 1e-6);
    }

    @Test
    void mecanumForward() {
        MecanumMovement movement = new MecanumMovement();
        JoystickInput jsl = new JoystickInput();
        JoystickInput jsr = new JoystickInput();
        jsl.update(0.0, 1.0);

        ChassisMotors motors = movement.calculate(jsl, jsr);

        assertEquals(1.0, motors.getFL(), 1e-6);
        assertEquals(1.0, motors.getBL(), 1e-6);
        assertEquals(1.0, motors.getFR(), 1e-6);
        assertEquals(1.0, motors.getBR(), 1e-6);
    }

    @Test
    void mecanumBackward() {
        MecanumMovement movement = new MecanumMovement();
        JoystickInput jsl = new JoystickInput();
        JoystickInput jsr = new JoystickInput();
        jsl.update(0.0, -1.0);

        ChassisMotors motors = movement.calculate(jsl, jsr);

        assertEquals(-1.0, motors.getFL(), 1e-6);
        assertEquals(-1.0, motors.getBL(), 1e-6);
        assertEquals(-1.0, motors.getFR(), 1e-6);
        assertEquals(-1.0, motors.getBR(), 1e-6);
    }

    @Test
    void mecanumTwistLeft() {
        MecanumMovement movement = new MecanumMovement();
        JoystickInput jsl = new JoystickInput();
        JoystickInput jsr = new JoystickInput();
        jsr.update(-1.0, 0.0);

        ChassisMotors motors = movement.calculate(jsl, jsr);

        assertEquals(-1.0, motors.getFL(), 1e-6);
        assertEquals(-1.0, motors.getBL(), 1e-6);
        assertEquals(1.0, motors.getFR(), 1e-6);
        assertEquals(1.0, motors.getBR(), 1e-6);
    }

    @Test
    void mecanumTwistRight() {
        MecanumMovement movement = new MecanumMovement();
        JoystickInput jsl = new JoystickInput();
        JoystickInput jsr = new JoystickInput();
        jsr.update(1.0, 0.0);

        ChassisMotors motors = movement.calculate(jsl, jsr);

        assertEquals(1.0, motors.getFL(), 1e-6);
        assertEquals(1.0, motors.getBL(), 1e-6);
        assertEquals(-1.0, motors.getFR(), 1e-6);
        assertEquals(-1.0, motors.getBR(), 1e-6);
    }

}