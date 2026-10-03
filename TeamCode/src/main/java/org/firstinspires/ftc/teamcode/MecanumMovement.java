package org.firstinspires.ftc.teamcode;

public class MecanumMovement {

    public ChassisMotors calculate(JoystickInput jsl, JoystickInput jsr) {
        double driveMult = 1;
        double twistMult = 1;

        double fieldDrive = jsl.getY();
        double fieldStrafe = jsl.getX();
        double twist = jsr.getX();

        // ================= MECANUM KINEMATICS =================
        // Normalize against raw (pre-multiplied) magnitudes so wheel ratios
        // are correct before slow mode scales everything down.
        double denominator = Math.max(
                Math.abs(fieldDrive) + Math.abs(fieldStrafe) + Math.abs(twist), 1.0);

        double normDrive  = (fieldDrive  / denominator) * driveMult;
        double normStrafe = (fieldStrafe / denominator) * driveMult;
        double normTwist  = (twist       / denominator) * twistMult;

        double flPower = normDrive + normStrafe + normTwist;
        double blPower = normDrive - normStrafe + normTwist;
        double frPower = normDrive - normStrafe - normTwist;
        double brPower = normDrive + normStrafe - normTwist;

        return new ChassisMotors(flPower, frPower, blPower, brPower);

    }
}
