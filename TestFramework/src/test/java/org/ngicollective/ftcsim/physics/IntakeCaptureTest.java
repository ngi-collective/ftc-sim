package org.ngicollective.ftcsim.physics;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.ngicollective.ftcsim.behavior.ImuBehaviors;
import org.ngicollective.ftcsim.camera.GameElement;
import org.ngicollective.ftcsim.camera.Structure;
import org.ngicollective.ftcsim.camera.TestBalls;
import org.ngicollective.ftcsim.hardware.FakeHardwareMap;
import org.ngicollective.ftcsim.sim.FieldConfig;
import org.ngicollective.ftcsim.sim.Pose2d;
import org.ngicollective.ftcsim.sim.RobotConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * A pickup robot's mouth: a box on the robot that removes every ball reaching into it.
 *
 * <p>The opposite of {@link IntakeTest}'s roller, which carries a ball and never decides it is
 * held. A mouth is for a robot whose OpMode waits for the ball to be gone, so here a ball is gone
 * from {@code elements()} the tick it arrives, in both worlds.</p>
 */
class IntakeCaptureTest {

    private static final double TICK = 0.02;

    /** A chassis with a mouth across its nose, 0.24 m out, and nothing else on it. */
    private static final String ROBOT_JSON = "{\n"
            + "  \"version\": 1,\n"
            + "  \"name\": \"Pickup\",\n"
            + "  \"chassis\": { \"widthMetres\": 0.38, \"lengthMetres\": 0.40,"
            + " \"heightMetres\": 0.05, \"deckHeightMetres\": 0.105,"
            + " \"massKilograms\": 14.0 },\n"
            + "  \"drivetrain\": { \"type\": \"mecanum\", \"wheelRadiusMetres\": 0.048,"
            + " \"gearRatio\": 1.0, \"trackWidthMetres\": 0.32, \"wheelBaseMetres\": 0.29,"
            + " \"strafeEfficiency\": 0.8, \"gripCoefficient\": 0.9 },\n"
            + "  \"imu\": { \"name\": \"imu\" },\n"
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
            + "  },\n"
            + "  \"intake\": {\n"
            + "    \"mouth\": { \"forwardMetres\": 0.24, \"leftMetres\": 0.0,"
            + " \"heightMetres\": 0.04, \"lengthMetres\": 0.08, \"widthMetres\": 0.24,"
            + " \"tallMetres\": 0.10 }\n"
            + "  }\n"
            + "}\n";

    @TempDir
    Path configDirectory;

    private RobotConfig robot;

    @BeforeEach
    void readTheRobot() throws IOException {
        Path file = configDirectory.resolve("pickup.json");
        Files.write(file, ROBOT_JSON.getBytes(UTF_8));
        robot = RobotConfig.load(file);
    }

    private FieldPhysics ode(GameElement... balls) {
        return new OdeFieldPhysics(Arrays.asList(balls), Collections.<Structure>emptyList(),
                FieldConfig.standard(), robot);
    }

    private FieldPhysics still(GameElement... balls) {
        return new StillFieldPhysics(Arrays.asList(balls), FieldConfig.standard(), robot);
    }

    @Test
    void theSolverRemovesABallInsideTheMouth() {
        assertCapturesOnlyTheBallAtTheMouth(ode(TestBalls.pollen(0.24, 0.0),
                TestBalls.pollen(1.0, 0.0)));
    }

    @Test
    void theStillWorldRemovesABallInsideTheMouthToo() {
        assertCapturesOnlyTheBallAtTheMouth(still(TestBalls.pollen(0.24, 0.0),
                TestBalls.pollen(1.0, 0.0)));
    }

    private void assertCapturesOnlyTheBallAtTheMouth(FieldPhysics physics) {
        try {
            List<GameElement> captured = physics.capture(robot.intakeMouth());

            assertEquals(1, captured.size(), "only the ball at the mouth: " + captured);
            assertEquals(0.24, captured.get(0).centre().x(), 1e-9);
            assertEquals(1, physics.elements().size(), "the far ball stays on the field");
            assertEquals(1, physics.bodies().size(), "and is the only body left to draw");
            assertTrue(physics.capture(robot.intakeMouth()).isEmpty(),
                    "a captured ball is gone, so it cannot be captured twice");
        } finally {
            physics.destroy();
        }
    }

    @Test
    void aWorldWithNoRobotCapturesNothing() {
        FieldPhysics physics = new StillFieldPhysics(
                Collections.singletonList(TestBalls.pollen(0.24, 0.0)), FieldConfig.standard(), null);

        assertTrue(physics.capture(robot.intakeMouth()).isEmpty());
        assertEquals(1, physics.elements().size());
    }

    @Test
    void theHardwareMapCollectsTheBallOnTheTickItArrives() {
        FakeHardwareMap hardware = FakeHardwareMap.builder()
                .addMotor("FL").addMotor("FR").addMotor("BL").addMotor("BR")
                .addImu("imu", ImuBehaviors.followingChassis())
                .withDrivetrain(robot, FieldConfig.standard())
                .withMechanisms(robot)
                .build();
        FieldPhysics physics = ode(TestBalls.pollen(0.24, 0.0));
        hardware.setPhysics(physics);
        hardware.drive().setPose(Pose2d.ORIGIN);

        hardware.advance(TICK);

        assertTrue(physics.elements().isEmpty(),
                "a ball whose surface is inside the mouth should be collected");
        physics.destroy();
    }
}
