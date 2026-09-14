package org.ngicollective.testframework.sim;

import java.net.URL;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A robot's physical description, loaded from one JSON file.
 *
 * <p>These numbers are physics, and they are needed in three places at once: the drive model
 * integrates them into a field pose, the tests assert against them, and the browser draws the robot
 * they describe. Keeping them in a data file next to the OpModes is what stops the encoder
 * resolution from being written down three times and drifting apart. Device <em>names</em>
 * deliberately stay in code, where a rename breaks a build instead of a run.</p>
 *
 * <p>Loading is strict: see {@link ConfigJson} for why nothing here is allowed to default.</p>
 */
public final class RobotConfig {

    /** The only schema this build understands; bumping it is how a breaking change announces itself. */
    private static final int VERSION = 1;

    private final String name;
    private final ChassisConfig chassis;
    private final DrivetrainConfig drivetrain;
    private final String imuName;
    private final Map<String, MotorConfig> motors;

    private RobotConfig(String name, ChassisConfig chassis, DrivetrainConfig drivetrain,
                        String imuName, Map<String, MotorConfig> motors) {
        this.name = name;
        this.chassis = chassis;
        this.drivetrain = drivetrain;
        this.imuName = imuName;
        this.motors = Collections.unmodifiableMap(motors);
    }

    /** Reads a robot description, failing with the file and field name if anything is missing. */
    public static RobotConfig load(Path file) {
        return from(ConfigJson.read(file, VERSION));
    }

    /**
     * The same, from a classpath resource. On a Control Hub or an emulator the configuration is
     * packaged into the APK, where there is no working directory to resolve a file against.
     */
    static RobotConfig load(URL resource) {
        return from(ConfigJson.read(resource, VERSION));
    }

    private static RobotConfig from(ConfigJson json) {
        ConfigJson motorsJson = json.child("motors");
        // LinkedHashMap: the file's order is the order the dashboard lists motors in, and a stable
        // order makes two runs of the same session diff cleanly.
        Map<String, MotorConfig> motors = new LinkedHashMap<>();
        for (String hardwareName : motorsJson.names()) {
            motors.put(hardwareName, MotorConfig.from(motorsJson.child(hardwareName)));
        }
        if (motors.isEmpty()) {
            throw new IllegalArgumentException(json.source() + ": \"motors\" declares no motors");
        }
        return new RobotConfig(
                json.string("name"),
                ChassisConfig.from(json.child("chassis")),
                DrivetrainConfig.from(json.child("drivetrain")),
                json.child("imu").string("name"),
                motors);
    }

    /** The robot's name, as shown in the dashboard. */
    public String name() {
        return name;
    }

    public ChassisConfig chassis() {
        return chassis;
    }

    public DrivetrainConfig drivetrain() {
        return drivetrain;
    }

    /** The name the IMU is configured under, which is also where the chassis heading is published. */
    public String imuName() {
        return imuName;
    }

    /** Every motor, keyed by the name the OpMode looks it up under, in file order. */
    public Map<String, MotorConfig> motors() {
        return motors;
    }
}
