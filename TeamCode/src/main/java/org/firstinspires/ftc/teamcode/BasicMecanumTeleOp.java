package org.firstinspires.ftc.teamcode;

import com.qualcomm.robotcore.eventloop.opmode.LinearOpMode;
import com.qualcomm.robotcore.eventloop.opmode.TeleOp;

@TeleOp(name = "Basic Mecanum", group = "Linear OpMode")
public class BasicMecanumTeleOp extends LinearOpMode {

    private final MecanumMovement movement = new MecanumMovement();

    @Override
    public void runOpMode() {

        DriveHardware drive = DriveHardware.initWithoutEncoders(hardwareMap);

        JoystickInput leftStick = new JoystickInput();
        JoystickInput rightStick = new JoystickInput();

        telemetry.addData("Status", "Initialized");
        telemetry.update();

        waitForStart();

        while (opModeIsActive()) {

            // Real stick Y is negative when pushed forward; MecanumMovement's
            // algorithm treats positive Y as forward, so negate it here.
            leftStick.update(gamepad1.left_stick_x, -gamepad1.left_stick_y);
            rightStick.update(gamepad1.right_stick_x, gamepad1.right_stick_y);

            ChassisMotors powers = movement.calculate(leftStick, rightStick);

            drive.setPowers(powers);

            telemetry.addData("FL | FR", "%.2f | %.2f", powers.getFL(), powers.getFR());
            telemetry.addData("BL | BR", "%.2f | %.2f", powers.getBL(), powers.getBR());
            telemetry.update();
        }

        drive.stop();
    }
}
