package org.ngicollective.testframework.sim;

/**
 * Where a camera is bolted to the robot, and how fast it streams.
 *
 * <p>The six degrees of freedom are {@link CameraMount}'s, which documents the frame they are
 * measured in. Height is the one number that may be omitted: it defaults to the chassis deck,
 * since that is where a camera actually gets mounted, and a number nobody measured is better
 * inherited from the robot than invented here.</p>
 *
 * <p>Optics are deliberately absent. The simulated camera reports itself as a specific real
 * webcam so that the SDK hands the vision processors a genuine calibration, and the renderer then
 * draws through that same calibration; a focal length in this file could only disagree with
 * it.</p>
 */
public final class CameraConfig {

    private final String name;
    private final CameraMount mount;
    private final double framesPerSecond;

    CameraConfig(String name, CameraMount mount, double framesPerSecond) {
        this.name = name;
        this.mount = mount;
        this.framesPerSecond = framesPerSecond;
    }

    static CameraConfig from(ConfigJson json, ChassisConfig chassis) {
        return new CameraConfig(
                json.string("name"),
                new CameraMount(
                        json.number("forwardMetres"),
                        json.number("leftMetres"),
                        json.names().contains("heightMetres")
                                ? json.positive("heightMetres")
                                : chassis.deckHeightMetres(),
                        json.number("yawDegrees"),
                        json.number("pitchDegrees"),
                        json.number("rollDegrees")),
                json.positive("framesPerSecond"));
    }

    /** The name an OpMode looks the camera up under. */
    public String name() {
        return name;
    }

    /** Where on the robot the camera is bolted, and which way it is aimed. */
    public CameraMount mount() {
        return mount;
    }

    /** Frames per second of simulated time. */
    public double framesPerSecond() {
        return framesPerSecond;
    }
}
