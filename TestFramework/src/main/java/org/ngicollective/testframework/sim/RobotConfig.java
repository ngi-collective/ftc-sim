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
    private final CameraConfig camera;
    private final Map<String, MotorConfig> motors;
    private final Map<String, ServoConfig> servos;
    private final Map<String, SensorConfig> sensors;

    private RobotConfig(String name, ChassisConfig chassis, DrivetrainConfig drivetrain,
                        String imuName, CameraConfig camera, Map<String, MotorConfig> motors,
                        Map<String, ServoConfig> servos, Map<String, SensorConfig> sensors) {
        this.name = name;
        this.chassis = chassis;
        this.drivetrain = drivetrain;
        this.imuName = imuName;
        this.camera = camera;
        this.motors = Collections.unmodifiableMap(motors);
        this.servos = Collections.unmodifiableMap(servos);
        this.sensors = Collections.unmodifiableMap(sensors);
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
        // Unlike "motors", these two blocks are optional, and absent means none. Every robot
        // description written before mechanisms were simulated is still a correct version 1 file:
        // requiring the blocks, or bumping the version to add them, would break configurations
        // that describe a perfectly valid robot which happens to have no servos worth modelling.
        Map<String, ServoConfig> servos = new LinkedHashMap<>();
        if (json.names().contains("servos")) {
            ConfigJson servosJson = json.child("servos");
            for (String hardwareName : servosJson.names()) {
                servos.put(hardwareName,
                        ServoConfig.from(hardwareName, servosJson.child(hardwareName)));
            }
        }
        Map<String, SensorConfig> sensors = new LinkedHashMap<>();
        if (json.names().contains("sensors")) {
            ConfigJson sensorsJson = json.child("sensors");
            for (String hardwareName : sensorsJson.names()) {
                sensors.put(hardwareName,
                        SensorConfig.from(hardwareName, sensorsJson.child(hardwareName)));
            }
        }
        ChassisConfig chassis = ChassisConfig.from(json.child("chassis"));
        return new RobotConfig(
                json.string("name"),
                chassis,
                DrivetrainConfig.from(json.child("drivetrain")),
                json.child("imu").string("name"),
                CameraConfig.from(json.child("camera"), chassis),
                motors,
                servos,
                sensors);
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

    /** Where the camera is mounted, and what it is configured under. */
    public CameraConfig camera() {
        return camera;
    }

    /** Every motor, keyed by the name the OpMode looks it up under, in file order. */
    public Map<String, MotorConfig> motors() {
        return motors;
    }

    /**
     * Every servo, keyed by the name the OpMode looks it up under, in file order. Empty when the
     * robot declares none.
     */
    public Map<String, ServoConfig> servos() {
        return servos;
    }

    /**
     * Every simulated sensor, keyed by the name the OpMode looks it up under, in file order. Empty
     * when the robot declares none.
     */
    public Map<String, SensorConfig> sensors() {
        return sensors;
    }
}
