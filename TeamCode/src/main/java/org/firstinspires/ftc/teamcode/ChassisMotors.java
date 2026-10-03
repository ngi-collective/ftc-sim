package org.firstinspires.ftc.teamcode;

public class ChassisMotors {
    double fl = 0.0;
    double fr = 0.0;
    double bl = 0.0;
    double br = 0.0;

    ChassisMotors(double fl, double fr, double bl, double br) {
        this.fl = fl;
        this.fr = fr;
        this.bl = bl;
        this.br = br;
    }

    public double getBL() {
        return bl;
    }
    public double getFL() {
        return fl;
    }
    public double getFR() {
        return fr;
    }
    public double getBR() {
        return br;
    }

}
