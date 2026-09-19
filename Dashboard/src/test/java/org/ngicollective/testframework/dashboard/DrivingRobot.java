package org.ngicollective.testframework.dashboard;

import org.ngicollective.testframework.behavior.ImuBehaviors;
import org.ngicollective.testframework.hardware.FakeHardwareMap;
import org.ngicollective.testframework.hardware.SimulatedRobot;
import org.ngicollective.testframework.sim.FieldConfig;
import org.ngicollective.testframework.sim.MotorConfig;
import org.ngicollective.testframework.sim.RobotConfig;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * A robot that can actually drive: four mecanum wheels, an IMU that follows the chassis, and the
 * {@code DriveModel} that turns one into the other.
 *
 * <p>Needed because a hardware map only gets a pose &mdash; and therefore a {@code sim/pose}
 * stream, a {@code sim/config} payload and anywhere for {@code placeRobot} to place anything
 * &mdash; when its configuration declares a drivetrain. The one-motor robot the rest of the suite
 * uses deliberately declares none, which is what makes it the right fixture for the
 * no-drivetrain side of those contracts and the wrong one for this side.</p>
 *
 * <p>The geometry goes through a file because that is the only way to build a {@link RobotConfig}:
 * the loader is strict on purpose (see {@code ConfigJson}) and there is no programmatic builder, so
 * a robot with no file cannot exist. Writing the same JSON a team commits keeps these numbers
 * where a reader can see them instead of resolving a path into another module's source tree.</p>
 */
final class DrivingRobot implements SimulatedRobot {

    /** The same shape as {@code TeamCode/robot-config/verity.json}, trimmed to what a drive needs. */
    private static final String CONFIG_JSON = "{\n"
            + "  \"version\": 1,\n"
            + "  \"name\": \"DrivingBot\",\n"
            + "  \"chassis\": { \"widthMetres\": 0.38, \"lengthMetres\": 0.40,"
            + " \"heightMetres\": 0.05, \"deckHeightMetres\": 0.105,"
            + " \"massKilograms\": 14.0 },\n"
            + "  \"drivetrain\": {\n"
            + "    \"type\": \"mecanum\",\n"
            + "    \"wheelRadiusMetres\": 0.048,\n"
            + "    \"gearRatio\": 1.0,\n"
            + "    \"trackWidthMetres\": 0.32,\n"
            + "    \"wheelBaseMetres\": 0.29,\n"
            + "    \"strafeEfficiency\": 0.8,\n"
            + "    \"gripCoefficient\": 0.9\n"
            + "  },\n"
            + "  \"imu\": { \"name\": \"imu\" },\n"
            + "  \"camera\": {\n"
            + "    \"name\": \"Webcam 1\",\n"
            + "    \"forwardMetres\": 0.16,\n"
            + "    \"leftMetres\": 0.0,\n"
            + "    \"yawDegrees\": 0.0,\n"
            + "    \"pitchDegrees\": 35.0,\n"
            + "    \"rollDegrees\": 0.0,\n"
            + "    \"framesPerSecond\": 30.0\n"
            + "  },\n"
            + "  \"motors\": {\n"
            + "    \"FL\": { \"role\": \"frontLeft\",  \"mirrored\": true,"
            + "  \"rpm\": 312.0, \"ticksPerRevolution\": 537.7 },\n"
            + "    \"FR\": { \"role\": \"frontRight\", \"mirrored\": false,"
            + " \"rpm\": 312.0, \"ticksPerRevolution\": 537.7 },\n"
            + "    \"BL\": { \"role\": \"backLeft\",   \"mirrored\": true,"
            + "  \"rpm\": 312.0, \"ticksPerRevolution\": 537.7 },\n"
            + "    \"BR\": { \"role\": \"backRight\",  \"mirrored\": false,"
            + " \"rpm\": 312.0, \"ticksPerRevolution\": 537.7 }\n"
            + "  }\n"
            + "}\n";

    private static final RobotConfig CONFIG = write();

    private final FieldConfig field = FieldConfig.standard();

    @Override
    public String name() {
        return CONFIG.name();
    }

    @Override
    public FakeHardwareMap create() {
        FakeHardwareMap.Builder builder =
                FakeHardwareMap.builder().addImu(CONFIG.imuName(), ImuBehaviors.followingChassis());
        for (String motor : CONFIG.motors().keySet()) {
            builder.addMotor(motor);
        }
        FakeHardwareMap hardware = builder.withDrivetrain(CONFIG, field).build();
        for (Map.Entry<String, MotorConfig> entry : CONFIG.motors().entrySet()) {
            MotorConfig motor = entry.getValue();
            hardware.motor(entry.getKey())
                    .state()
                    .setMaxSpeed(motor.rpm(), motor.ticksPerRevolution());
        }
        return hardware;
    }

    /** The geometry this robot drives with, for a test that wants to predict where it ends up. */
    RobotConfig config() {
        return CONFIG;
    }

    private static RobotConfig write() {
        try {
            Path file = Files.createTempFile("dashboard-driving-robot", ".json");
            file.toFile().deleteOnExit();
            Files.write(file, CONFIG_JSON.getBytes(StandardCharsets.UTF_8));
            return RobotConfig.load(file);
        } catch (IOException e) {
            throw new IllegalStateException("could not write the test robot's configuration", e);
        }
    }
}
