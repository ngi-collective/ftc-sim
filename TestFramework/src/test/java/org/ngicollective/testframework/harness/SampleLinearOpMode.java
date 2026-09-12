package org.ngicollective.testframework.harness;

import com.qualcomm.robotcore.eventloop.opmode.LinearOpMode;
import com.qualcomm.robotcore.hardware.DcMotorEx;

import java.util.concurrent.CountDownLatch;

/**
 * A LinearOpMode shaped like a real one: hardware lookup and telemetry during init, a
 * {@code waitForStart()}, a run loop, and a stop that cuts power.
 *
 * <p>The latches let the harness tests synchronise with the OpMode thread instead of sleeping.</p>
 */
class SampleLinearOpMode extends LinearOpMode {

    final CountDownLatch reachedWaitForStart = new CountDownLatch(1);
    final CountDownLatch reachedRunLoop = new CountDownLatch(1);
    volatile int loops;

    @Override
    public void runOpMode() {
        DcMotorEx drive = hardwareMap.get(DcMotorEx.class, "drive");

        telemetry.addData("Status", "Initialized");
        telemetry.update();
        reachedWaitForStart.countDown();

        waitForStart();

        drive.setPower(1.0);
        reachedRunLoop.countDown();

        while (opModeIsActive()) {
            loops++;
            telemetry.addData("Position", drive.getCurrentPosition());
            telemetry.update();
        }

        drive.setPower(0.0);
    }
}
