package org.firstinspires.ftc.teamcode;

import com.qualcomm.robotcore.hardware.DcMotor;
import com.qualcomm.robotcore.hardware.DcMotorEx;
import com.qualcomm.robotcore.hardware.DcMotorSimple;
import com.qualcomm.robotcore.hardware.HardwareMap;

/**
 * Single source of truth for the mecanum drivetrain's hardware-map names and
 * wiring (motor direction, run mode, zero-power behavior).
 *
 * Every OpMode that drives this chassis must go through {@link #initWithEncoders}
 * or {@link #initWithoutEncoders} instead of re-declaring the "FL"/"FR"/"BL"/"BR"
 * names and their directions inline. Two OpModes previously each hardcoded their
 * own copy of this wiring and had quietly drifted apart (BasicMecanumTeleOp had
 * FL/BL and FR/BR directions swapped relative to ExampleTeleOp and pedroPathing's
 * Constants.java). Routing every OpMode through here makes that class of drift
 * impossible: change the wiring once, everything using it picks it up.
 */
public class DriveHardware {

    public static final String FL_NAME = "FL";
    public static final String FR_NAME = "FR";
    public static final String BL_NAME = "BL";
    public static final String BR_NAME = "BR";

    public final DcMotorEx fl;
    public final DcMotorEx fr;
    public final DcMotorEx bl;
    public final DcMotorEx br;

    private DriveHardware(DcMotorEx fl, DcMotorEx fr, DcMotorEx bl, DcMotorEx br) {
        this.fl = fl;
        this.fr = fr;
        this.bl = bl;
        this.br = br;
    }

    /** Maps and orients the four drive motors, leaving encoder feedback active (brake on zero power). */
    public static DriveHardware initWithEncoders(HardwareMap hardwareMap) {
        DriveHardware hw = mapAndOrient(hardwareMap);
        for (DcMotorEx m : new DcMotorEx[]{hw.fl, hw.fr, hw.bl, hw.br}) {
            m.setMode(DcMotor.RunMode.RUN_USING_ENCODER);
            m.setZeroPowerBehavior(DcMotor.ZeroPowerBehavior.BRAKE);
        }
        return hw;
    }

    /** Maps and orients the four drive motors without encoder feedback. */
    public static DriveHardware initWithoutEncoders(HardwareMap hardwareMap) {
        DriveHardware hw = mapAndOrient(hardwareMap);
        for (DcMotorEx m : new DcMotorEx[]{hw.fl, hw.fr, hw.bl, hw.br}) {
            m.setMode(DcMotor.RunMode.RUN_WITHOUT_ENCODER);
        }
        return hw;
    }

    private static DriveHardware mapAndOrient(HardwareMap hardwareMap) {
        DcMotorEx fl = hardwareMap.get(DcMotorEx.class, FL_NAME);
        DcMotorEx fr = hardwareMap.get(DcMotorEx.class, FR_NAME);
        DcMotorEx bl = hardwareMap.get(DcMotorEx.class, BL_NAME);
        DcMotorEx br = hardwareMap.get(DcMotorEx.class, BR_NAME);

        fl.setDirection(DcMotorSimple.Direction.REVERSE);
        bl.setDirection(DcMotorSimple.Direction.REVERSE);
        fr.setDirection(DcMotorSimple.Direction.FORWARD);
        br.setDirection(DcMotorSimple.Direction.FORWARD);

        return new DriveHardware(fl, fr, bl, br);
    }

    /** Applies chassis-relative wheel powers computed by the drive logic. */
    public void setPowers(ChassisMotors powers) {
        fl.setPower(powers.getFL());
        fr.setPower(powers.getFR());
        bl.setPower(powers.getBL());
        br.setPower(powers.getBR());
    }

    /** Stops all four drive motors. */
    public void stop() {
        fl.setPower(0);
        fr.setPower(0);
        bl.setPower(0);
        br.setPower(0);
    }
}
