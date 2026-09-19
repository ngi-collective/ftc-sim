package org.ngicollective.testframework.sim;

/**
 * How far a rotating rectangular footprint may sit from field centre before it overlaps a wall.
 *
 * <p>Shared because both chassis implementations need the same answer for the same reason, and for
 * two different jobs. The kinematic chassis clamps every step with it, since arithmetic is the only
 * thing stopping it at the perimeter. The rigid-body chassis collides with real walls and needs it
 * only for a <em>teleport</em>: placing a robot half inside a wall hands the solver a penetration
 * it will resolve by flinging the robot somewhere, which looks like a physics bug rather than like
 * the illegal placement it was.</p>
 *
 * <p>Two copies of this would be two answers to "is the robot on the field", and the one that
 * disagreed would be whichever was not being read.</p>
 */
public final class Footprint {

    private Footprint() {
    }

    /**
     * How far the centre may sit from field centre along X.
     *
     * <p>Depends on heading because the footprint turns with the robot: a robot at 45 degrees
     * presents its diagonal to the wall and has to stop sooner than one square to it.</p>
     */
    public static double limitX(double headingRadians, ChassisConfig chassis, FieldConfig field) {
        return limit(Math.cos(headingRadians), Math.sin(headingRadians), chassis, field);
    }

    /** The same along Y, where the robot's length and width swap roles. */
    public static double limitY(double headingRadians, ChassisConfig chassis, FieldConfig field) {
        return limit(Math.sin(headingRadians), Math.cos(headingRadians), chassis, field);
    }

    private static double limit(double alongAxis, double acrossAxis, ChassisConfig chassis,
                                FieldConfig field) {
        double halfLength = chassis.lengthMetres() / 2.0;
        double halfWidth = chassis.widthMetres() / 2.0;
        return Math.max(0.0, field.halfExtentMetres()
                - (halfLength * Math.abs(alongAxis) + halfWidth * Math.abs(acrossAxis)));
    }

    /** {@code value}, held within plus or minus {@code limit}. */
    public static double clamp(double value, double limit) {
        if (value < -limit) {
            return -limit;
        }
        return value > limit ? limit : value;
    }
}
