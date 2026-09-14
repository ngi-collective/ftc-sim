package org.ngicollective.testframework.sim;

/**
 * Where a camera is bolted to the robot, and how fast it streams.
 *
 * <p>Six degrees of freedom, because BioBuzz needs them: the AprilTag clusters hang under the
 * CELLs facing the floor, three to four feet up, so a camera that could only yaw would never see
 * one. Pitch is positive upward for that reason.</p>
 *
 * <p>The mount is in the robot frame &mdash; +X out the nose, +Y to the robot's left, +Z up, from
 * the floor at the centre of the footprint &mdash; because that is the frame someone can hold a
 * ruler against. Height defaults to the chassis deck, since that is where a camera actually gets
 * mounted, and a number nobody measured is better inherited from the robot than invented here.</p>
 *
 * <p>Optics are deliberately absent. The simulated camera reports itself as a specific real
 * webcam so that the SDK hands the vision processors a genuine calibration, and the renderer then
 * draws through that same calibration; a focal length in this file could only disagree with
 * it.</p>
 */
public final class CameraConfig {

    private final String name;
    private final double x;
    private final double y;
    private final double z;
    private final double yaw;
    private final double pitch;
    private final double roll;
    private final double framesPerSecond;

    CameraConfig(String name, double xMetres, double yMetres, double zMetres,
                 double yawDegrees, double pitchDegrees, double rollDegrees,
                 double framesPerSecond) {
        this.name = name;
        this.x = xMetres;
        this.y = yMetres;
        this.z = zMetres;
        this.yaw = yawDegrees;
        this.pitch = pitchDegrees;
        this.roll = rollDegrees;
        this.framesPerSecond = framesPerSecond;
    }

    static CameraConfig from(ConfigJson json, ChassisConfig chassis) {
        return new CameraConfig(
                json.string("name"),
                json.number("forwardMetres"),
                json.number("leftMetres"),
                json.names().contains("heightMetres")
                        ? json.positive("heightMetres")
                        : chassis.deckHeightMetres(),
                json.number("yawDegrees"),
                json.number("pitchDegrees"),
                json.number("rollDegrees"),
                json.positive("framesPerSecond"));
    }

    /** The name an OpMode looks the camera up under. */
    public String name() {
        return name;
    }

    /** Metres ahead of the robot's centre. */
    public double forwardMetres() {
        return x;
    }

    /** Metres to the robot's left of centre. */
    public double leftMetres() {
        return y;
    }

    /** Metres above the floor. */
    public double heightMetres() {
        return z;
    }

    /** Degrees the camera is turned from straight ahead, counter-clockwise positive. */
    public double yawDegrees() {
        return yaw;
    }

    /** Degrees the camera is aimed above the horizon. */
    public double pitchDegrees() {
        return pitch;
    }

    /** Degrees the camera is rolled about its own optical axis. */
    public double rollDegrees() {
        return roll;
    }

    /** Frames per second of simulated time. */
    public double framesPerSecond() {
        return framesPerSecond;
    }
}
