package org.ngicollective.testframework.dashboard;

import com.qualcomm.robotcore.eventloop.opmode.OpMode;
import com.qualcomm.robotcore.eventloop.opmode.TeleOp;
import com.qualcomm.robotcore.hardware.DcMotorEx;

/**
 * An iterative TeleOp shaped like a real one: four mecanum motors driven from the left stick.
 *
 * <p>The counterpart to {@link TickingTeleOp}, which is a {@code LinearOpMode} and therefore runs
 * on its own thread. An iterative OpMode's lifecycle calls happen inside the backend's control
 * cycle, so a session driving this one is deterministic end to end &mdash; pump a cycle and the
 * OpMode has run, commanded its motors and composed its telemetry by the time the pump
 * returns.</p>
 *
 * <p>Each phase names itself in telemetry because that is the channel a browser watches: which
 * lifecycle method the backend is calling is otherwise only visible from inside the OpMode.</p>
 */
@TeleOp(name = "Mecanum Iterative TeleOp")
public class MecanumIterativeTeleOp extends OpMode {

    private DcMotorEx frontLeft;
    private DcMotorEx frontRight;
    private DcMotorEx backLeft;
    private DcMotorEx backRight;

    private int initLoops;
    private int loops;

    @Override
    public void init() {
        frontLeft = hardwareMap.get(DcMotorEx.class, "FL");
        frontRight = hardwareMap.get(DcMotorEx.class, "FR");
        backLeft = hardwareMap.get(DcMotorEx.class, "BL");
        backRight = hardwareMap.get(DcMotorEx.class, "BR");
        telemetry.addData("Phase", "init");
        telemetry.update();
    }

    @Override
    public void init_loop() {
        initLoops++;
        telemetry.addData("Phase", "init_loop");
        telemetry.addData("Cycles", initLoops);
        telemetry.update();
    }

    @Override
    public void loop() {
        loops++;
        // Stick forward is negative on a real gamepad, and the left wheels are mirrored, so their
        // shafts turn the other way for the same forward motion. Both conventions belong here
        // rather than in the test: this is what a team's own TeleOp looks like.
        double forward = -gamepad1.left_stick_y;
        frontLeft.setPower(-forward);
        backLeft.setPower(-forward);
        frontRight.setPower(forward);
        backRight.setPower(forward);

        telemetry.addData("Phase", "loop");
        telemetry.addData("Cycles", loops);
        telemetry.update();
    }
}
