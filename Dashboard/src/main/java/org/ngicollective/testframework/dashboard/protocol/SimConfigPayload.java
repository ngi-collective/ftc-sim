package org.ngicollective.testframework.dashboard.protocol;

/**
 * The geometry the browser needs before it can draw anything: this robot's dimensions and
 * drivetrain, and the field it is driving on.
 *
 * <p>Sent on connect and again on every OpMode init, rather than baked into the web app, because
 * the numbers live in the team's {@code robot-config} JSON next to the OpModes. A robot that grows
 * a wider chassis should show up in the 3D view after editing one file and re-initializing, not
 * after a rebuild of the dashboard.</p>
 *
 * <p>Field names mirror the JSON files character for character so there is exactly one vocabulary
 * to learn &mdash; file, wire and UI.</p>
 */
public final class SimConfigPayload {

    public final Robot robot;
    public final Field field;

    public SimConfigPayload(Robot robot, Field field) {
        this.robot = robot;
        this.field = field;
    }

    public static final class Robot {

        public final String name;
        public final Chassis chassis;
        public final Drivetrain drivetrain;

        public Robot(String name, Chassis chassis, Drivetrain drivetrain) {
            this.name = name;
            this.chassis = chassis;
            this.drivetrain = drivetrain;
        }
    }

    /** Metres. {@code deckHeightMetres} is where mechanisms mount, above the chassis plate. */
    public static final class Chassis {

        public final double widthMetres;
        public final double lengthMetres;
        public final double heightMetres;
        public final double deckHeightMetres;

        public Chassis(double widthMetres, double lengthMetres, double heightMetres,
                       double deckHeightMetres) {
            this.widthMetres = widthMetres;
            this.lengthMetres = lengthMetres;
            this.heightMetres = heightMetres;
            this.deckHeightMetres = deckHeightMetres;
        }
    }

    /**
     * The kinematics the drive model is running, so the UI can label wheels correctly and reason
     * about what the robot is physically able to do.
     *
     * <p>Motor directions are deliberately absent: the drive hardware owns them at runtime, and a
     * second copy on the wire could only ever disagree with it.</p>
     */
    public static final class Drivetrain {

        public final String type;
        public final double wheelRadiusMetres;
        public final double gearRatio;
        public final double trackWidthMetres;
        public final double wheelBaseMetres;
        public final double strafeEfficiency;

        public Drivetrain(String type, double wheelRadiusMetres, double gearRatio,
                          double trackWidthMetres, double wheelBaseMetres,
                          double strafeEfficiency) {
            this.type = type;
            this.wheelRadiusMetres = wheelRadiusMetres;
            this.gearRatio = gearRatio;
            this.trackWidthMetres = trackWidthMetres;
            this.wheelBaseMetres = wheelBaseMetres;
            this.strafeEfficiency = strafeEfficiency;
        }
    }

    /** The playing field: a square of {@code sizeMetres}, walled and tiled. */
    public static final class Field {

        public final double sizeMetres;
        public final double wallHeightMetres;
        public final double tileMetres;

        public Field(double sizeMetres, double wallHeightMetres, double tileMetres) {
            this.sizeMetres = sizeMetres;
            this.wallHeightMetres = wallHeightMetres;
            this.tileMetres = tileMetres;
        }
    }
}
