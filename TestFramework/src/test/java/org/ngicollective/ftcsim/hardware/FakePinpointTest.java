package org.ngicollective.ftcsim.hardware;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.qualcomm.hardware.gobilda.GoBildaPinpointDriver;

import org.firstinspires.ftc.robotcore.external.navigation.AngleUnit;
import org.firstinspires.ftc.robotcore.external.navigation.DistanceUnit;
import org.firstinspires.ftc.robotcore.external.navigation.Pose2D;
import org.firstinspires.ftc.robotcore.external.navigation.UnnormalizedAngleUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.ngicollective.ftcsim.behavior.ImuBehaviors;
import org.ngicollective.ftcsim.behavior.PinpointState;
import org.ngicollective.ftcsim.sim.FieldConfig;
import org.ngicollective.ftcsim.sim.PinpointMount;
import org.ngicollective.ftcsim.sim.Pose2d;
import org.ngicollective.ftcsim.sim.RobotConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The SDK's {@code GoBildaPinpointDriver}, unmodified, talking to a simulated Pinpoint.
 *
 * <p>Everything here goes through the driver, because the driver is what a team's code calls: a
 * register layout, a CRC or a bulk-read scope that disagreed with it would fail here and nowhere
 * else.</p>
 */
class FakePinpointTest {

    private static final double TICK = 0.02;

    private static FakeHardwareMap pinpointOnly(PinpointMount mount) {
        return FakeHardwareMap.builder().addPinpoint(mount).build();
    }

    /** Moves the chassis along a straight line in small steps, as the world would. */
    private static void move(FakeHardwareMap hardware, PinpointState state, Pose2d from, Pose2d to,
                             int steps) {
        for (int i = 1; i <= steps; i++) {
            double t = (double) i / steps;
            state.observeChassis(new Pose2d(
                    from.x() + (to.x() - from.x()) * t,
                    from.y() + (to.y() - from.y()) * t,
                    from.heading() + (to.heading() - from.heading()) * t));
            hardware.advance(TICK);
        }
    }

    @Test
    void anOpModeGetsTheSdkDriverByTheConfiguredName() {
        FakeHardwareMap hardware = pinpointOnly(PinpointMount.centred("pinpoint"));

        GoBildaPinpointDriver driver = hardware.get(GoBildaPinpointDriver.class, "pinpoint");

        assertNotNull(driver);
        assertEquals(1, driver.getDeviceID());
        assertEquals(PinpointState.FIRMWARE_VERSION, driver.getDeviceVersion());
    }

    @Test
    void drivingForwardReadsBackThroughTheDriversBulkRead() {
        FakeHardwareMap hardware = pinpointOnly(PinpointMount.centred("pinpoint"));
        GoBildaPinpointDriver driver = hardware.get(GoBildaPinpointDriver.class, "pinpoint");
        driver.setEncoderResolution(GoBildaPinpointDriver.GoBildaOdometryPods.goBILDA_4_BAR_POD);
        PinpointState state = hardware.pinpoint("pinpoint").state();
        state.observeChassis(Pose2d.ORIGIN);

        move(hardware, state, Pose2d.ORIGIN, new Pose2d(0.6, 0.0, 0.0), 30);
        driver.update();

        assertEquals(GoBildaPinpointDriver.DeviceStatus.READY, driver.getDeviceStatus());
        assertEquals(600.0, driver.getPosX(DistanceUnit.MM), 0.01);
        assertEquals(0.0, driver.getPosY(DistanceUnit.MM), 0.01);
        assertEquals(Math.round(600.0 * PinpointMount.GOBILDA_4_BAR_TICKS_PER_MM),
                driver.getEncoderX(), 1);
        assertEquals(0.6 / (30 * TICK) * 1000.0, driver.getVelX(DistanceUnit.MM), 0.5,
                "velocity is the last step's, in millimetres a second");
    }

    @Test
    void setPositionPutsTheRobotInFieldCoordinates() {
        FakeHardwareMap hardware = pinpointOnly(PinpointMount.centred("pinpoint"));
        GoBildaPinpointDriver driver = hardware.get(GoBildaPinpointDriver.class, "pinpoint");
        PinpointState state = hardware.pinpoint("pinpoint").state();
        state.observeChassis(Pose2d.ORIGIN);

        driver.setPosition(new Pose2D(DistanceUnit.INCH, 24.0, -12.0, AngleUnit.DEGREES, 90.0));
        // Facing +Y now, by the device's reckoning; the chassis drives its own forward.
        move(hardware, state, Pose2d.ORIGIN, new Pose2d(0.254, 0.0, 0.0), 10);
        driver.update();

        assertEquals(24.0, driver.getPosX(DistanceUnit.INCH), 1e-3);
        assertEquals(-2.0, driver.getPosY(DistanceUnit.INCH), 1e-3);
        assertEquals(90.0, driver.getHeading(AngleUnit.DEGREES), 1e-3);
    }

    @Test
    void headingKeepsCountingPastAFullTurn() {
        FakeHardwareMap hardware = pinpointOnly(PinpointMount.centred("pinpoint"));
        GoBildaPinpointDriver driver = hardware.get(GoBildaPinpointDriver.class, "pinpoint");
        PinpointState state = hardware.pinpoint("pinpoint").state();
        state.observeChassis(Pose2d.ORIGIN);

        // A turn and a quarter, in steps short enough to stay on the short way round. Stepped
        // here rather than through move(), because a Pose2d of 2.5 pi is already a quarter turn.
        for (int i = 1; i <= 100; i++) {
            state.observeChassis(new Pose2d(0.0, 0.0, 2.5 * Math.PI * i / 100));
            hardware.advance(TICK);
        }
        driver.update();

        assertEquals(450.0, driver.getHeading(UnnormalizedAngleUnit.DEGREES), 1e-3);
        assertEquals(90.0, driver.getHeading(AngleUnit.DEGREES), 1e-3);
    }

    @Test
    void crcAndANarrowBulkReadBothDecode() {
        FakeHardwareMap hardware = pinpointOnly(PinpointMount.centred("pinpoint"));
        GoBildaPinpointDriver driver = hardware.get(GoBildaPinpointDriver.class, "pinpoint");
        driver.setErrorDetectionType(GoBildaPinpointDriver.ErrorDetectionType.CRC);
        driver.setBulkReadScope(GoBildaPinpointDriver.Register.X_POSITION,
                GoBildaPinpointDriver.Register.H_ORIENTATION);
        PinpointState state = hardware.pinpoint("pinpoint").state();
        state.observeChassis(Pose2d.ORIGIN);

        move(hardware, state, Pose2d.ORIGIN, new Pose2d(0.3, 0.0, 0.0), 10);
        driver.update();

        assertEquals(300.0, driver.getPosX(DistanceUnit.MM), 0.01);
        // Outside the scope: the driver reads this one register by itself, CRC and all.
        assertEquals(0.0, driver.getPosY(DistanceUnit.MM), 0.01);
        assertTrue(driver.getDeviceStatus() != GoBildaPinpointDriver.DeviceStatus.FAULT_BAD_READ,
                "a CRC the driver rejects reports FAULT_BAD_READ");
    }

    @Test
    void recalibratingReportsCalibratingForAQuarterSecond() {
        FakeHardwareMap hardware = pinpointOnly(PinpointMount.centred("pinpoint"));
        GoBildaPinpointDriver driver = hardware.get(GoBildaPinpointDriver.class, "pinpoint");

        driver.recalibrateIMU();
        driver.update();
        assertEquals(GoBildaPinpointDriver.DeviceStatus.CALIBRATING, driver.getDeviceStatus());

        hardware.advance(PinpointState.CALIBRATION_SECONDS + TICK);
        driver.update();
        assertEquals(GoBildaPinpointDriver.DeviceStatus.READY, driver.getDeviceStatus());
    }

    @Test
    void placingTheRobotIsNotAMotion(@TempDir Path directory) throws IOException {
        FakeHardwareMap hardware = drivenRobot(directory);
        GoBildaPinpointDriver driver = hardware.get(GoBildaPinpointDriver.class, "pinpoint");
        hardware.advance(TICK);

        hardware.drive().setPose(new Pose2d(1.0, 1.0, 1.0));
        hardware.advance(TICK);
        driver.update();

        assertEquals(0.0, driver.getPosX(DistanceUnit.MM), 1e-3);
        assertEquals(0.0, driver.getPosY(DistanceUnit.MM), 1e-3);
        assertEquals(0.0, driver.getHeading(AngleUnit.RADIANS), 1e-6);
    }

    @Test
    void theDrivetrainMovesThePods(@TempDir Path directory) throws IOException {
        FakeHardwareMap hardware = drivenRobot(directory);
        GoBildaPinpointDriver driver = hardware.get(GoBildaPinpointDriver.class, "pinpoint");
        driver.setOffsets(-84.0, -168.0, DistanceUnit.MM);
        driver.setEncoderResolution(GoBildaPinpointDriver.GoBildaOdometryPods.goBILDA_4_BAR_POD);
        driver.setEncoderDirections(GoBildaPinpointDriver.EncoderDirection.FORWARD,
                GoBildaPinpointDriver.EncoderDirection.FORWARD);

        for (String wheel : new String[] {"FL", "FR", "BL", "BR"}) {
            hardware.motor(wheel).setPower(0.5);
        }
        for (int tick = 0; tick < 50; tick++) {
            hardware.advance(TICK);
        }
        driver.update();

        Pose2d truth = hardware.drive().pose();
        assertTrue(truth.x() > 0.3, "the robot should have driven forward: " + truth);
        assertEquals(truth.x() * 1000.0, driver.getPosX(DistanceUnit.MM), 1.0);
        assertEquals(truth.y() * 1000.0, driver.getPosY(DistanceUnit.MM), 1.0);
        assertEquals(truth.heading(), driver.getHeading(AngleUnit.RADIANS), 1e-4);
    }

    /** A plain mecanum robot with a Pinpoint whose pods sit 84 mm right and 168 mm back. */
    private static FakeHardwareMap drivenRobot(Path directory) throws IOException {
        String json = "{\n"
                + "  \"version\": 1,\n"
                + "  \"name\": \"Odometer\",\n"
                + "  \"chassis\": { \"widthMetres\": 0.38, \"lengthMetres\": 0.40,"
                + " \"heightMetres\": 0.05, \"deckHeightMetres\": 0.105,"
                + " \"massKilograms\": 14.0 },\n"
                + "  \"drivetrain\": { \"type\": \"mecanum\", \"wheelRadiusMetres\": 0.048,"
                + " \"gearRatio\": 1.0, \"trackWidthMetres\": 0.32, \"wheelBaseMetres\": 0.29,"
                + " \"strafeEfficiency\": 0.8, \"gripCoefficient\": 0.9 },\n"
                + "  \"imu\": { \"name\": \"imu\" },\n"
                + "  \"pinpoint\": { \"name\": \"pinpoint\", \"xPodLeftMetres\": -0.084,"
                + " \"yPodForwardMetres\": -0.168, \"ticksPerMm\": 19.89436789,"
                + " \"xPodReversed\": false, \"yPodReversed\": false },\n"
                + "  \"camera\": { \"name\": \"Webcam 1\", \"forwardMetres\": 0.16,"
                + " \"leftMetres\": 0.0, \"yawDegrees\": 0.0, \"pitchDegrees\": 35.0,"
                + " \"rollDegrees\": 0.0, \"framesPerSecond\": 30.0 },\n"
                + "  \"motors\": {\n"
                + "    \"FL\": { \"role\": \"frontLeft\",  \"mirrored\": false, \"rpm\": 312.0,"
                + " \"ticksPerRevolution\": 537.7 },\n"
                + "    \"FR\": { \"role\": \"frontRight\", \"mirrored\": false, \"rpm\": 312.0,"
                + " \"ticksPerRevolution\": 537.7 },\n"
                + "    \"BL\": { \"role\": \"backLeft\",   \"mirrored\": false, \"rpm\": 312.0,"
                + " \"ticksPerRevolution\": 537.7 },\n"
                + "    \"BR\": { \"role\": \"backRight\",  \"mirrored\": false, \"rpm\": 312.0,"
                + " \"ticksPerRevolution\": 537.7 }\n"
                + "  }\n"
                + "}\n";
        Path file = directory.resolve("odometer.json");
        Files.write(file, json.getBytes(UTF_8));
        RobotConfig robot = RobotConfig.load(file);

        FakeHardwareMap hardware = FakeHardwareMap.builder()
                .addMotor("FL").addMotor("FR").addMotor("BL").addMotor("BR")
                .addImu("imu", ImuBehaviors.followingChassis())
                .addPinpoint(robot.pinpoint())
                .withDrivetrain(robot, FieldConfig.standard())
                .build();
        for (String wheel : new String[] {"FL", "FR", "BL", "BR"}) {
            hardware.motor(wheel).state().setMaxSpeed(312.0, 537.7);
        }
        return hardware;
    }
}
