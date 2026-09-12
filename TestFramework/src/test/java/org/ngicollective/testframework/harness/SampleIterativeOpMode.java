package org.ngicollective.testframework.harness;

import com.qualcomm.robotcore.eventloop.opmode.OpMode;
import com.qualcomm.robotcore.hardware.DcMotorEx;

/** An iterative OpMode covering every lifecycle callback the harness drives. */
class SampleIterativeOpMode extends OpMode {

    DcMotorEx drive;
    int initLoops;
    int loops;
    boolean stopped;

    @Override
    public void init() {
        drive = hardwareMap.get(DcMotorEx.class, "drive");
        telemetry.addData("Status", "Initialized");
        telemetry.update();
    }

    @Override
    public void init_loop() {
        initLoops++;
    }

    @Override
    public void start() {
        drive.setPower(0.5);
    }

    @Override
    public void loop() {
        loops++;
        drive.setPower(gamepad1.left_stick_y);
        telemetry.addData("Position", drive.getCurrentPosition());
        telemetry.update();
    }

    @Override
    public void stop() {
        drive.setPower(0.0);
        stopped = true;
    }
}
