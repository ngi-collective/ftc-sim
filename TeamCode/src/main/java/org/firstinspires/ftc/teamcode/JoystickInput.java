package org.firstinspires.ftc.teamcode;

public class JoystickInput {
    private double x = 0.0;
    private double y = 0.0;

    public void update(double v, double v1) {
        this.x = v;
        this.y = v1;
    }

    public double getX()  {
        return this.x;
    }

    public double getY()  {
        return this.y;
    }

}
