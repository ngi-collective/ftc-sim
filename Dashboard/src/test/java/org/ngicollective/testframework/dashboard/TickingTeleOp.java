package org.ngicollective.testframework.dashboard;

import com.qualcomm.robotcore.eventloop.opmode.LinearOpMode;
import com.qualcomm.robotcore.eventloop.opmode.TeleOp;
import com.qualcomm.robotcore.hardware.DcMotorEx;

/** A minimal TeleOp shaped like a real one, for driving the backend under test. */
@TeleOp(name = "Ticking TeleOp")
public class TickingTeleOp extends LinearOpMode {

    @Override
    public void runOpMode() {
        DcMotorEx drive = hardwareMap.get(DcMotorEx.class, "drive");
        telemetry.addData("Status", "Initialized");
        telemetry.update();

        waitForStart();

        while (opModeIsActive()) {
            drive.setPower(gamepad1.left_stick_y);
            telemetry.addData("Position", drive.getCurrentPosition());
            telemetry.update();
        }
        drive.setPower(0.0);
    }
}
