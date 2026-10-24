package org.ngicollective.ftcsim.dashboard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.ngicollective.ftcsim.behavior.ImuBehaviors;
import org.ngicollective.ftcsim.camera.CameraIntrinsics;
import org.ngicollective.ftcsim.camera.GameElement;
import org.ngicollective.ftcsim.camera.Pose3d;
import org.ngicollective.ftcsim.camera.SceneFrameSource;
import org.ngicollective.ftcsim.camera.SimulatedCamera;
import org.ngicollective.ftcsim.camera.SimulatedScene;
import org.ngicollective.ftcsim.camera.Vec3;
import org.ngicollective.ftcsim.dashboard.protocol.BodiesPayload;
import org.ngicollective.ftcsim.dashboard.protocol.OpModeInfo;
import org.ngicollective.ftcsim.dashboard.protocol.ScenePayload;
import org.ngicollective.ftcsim.hardware.FakeHardwareMap;
import org.ngicollective.ftcsim.hardware.FakeWebcam;
import org.ngicollective.ftcsim.hardware.SimulatedRobot;
import org.ngicollective.ftcsim.season.BioBuzzElements;
import org.ngicollective.ftcsim.season.BioBuzzField;
import org.ngicollective.ftcsim.season.BioBuzzHive;
import org.ngicollective.ftcsim.sim.FieldConfig;
import org.ngicollective.ftcsim.sim.RobotConfig;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.Supplier;

/**
 * What {@code sim/bodies} promises the Field View, and when it says nothing at all.
 *
 * <p>The silence is as much of the contract as the frames are. A field nobody has driven into
 * publishes its arrangement once, in {@code sim/scene}, and then stops talking: that is what keeps
 * an idle session off the socket, and it is why the browser's body buffer has to answer "nothing
 * to say" rather than "everything is at the origin".</p>
 */
class BodyPublicationTest {

    /** A ball dropped from here has somewhere to fall, and lands within a few tenths of a second. */
    private static final double DROP_HEIGHT_METRES = 0.4;

    private LocalDashboardBackend backend;
    private ManualTicks ticks;
    private final List<BodiesPayload> published = new ArrayList<>();

    @AfterEach
    void tearDown() {
        if (backend != null) {
            backend.close();
        }
    }

    @Test
    void aFieldNobodyHasTouchedSaysNothingAtAll() {
        session(restingBall());

        for (int tick = 0; tick < 50; tick++) {
            ticks.pump();
        }

        assertTrue(published.isEmpty(),
                "a still field published " + published.size() + " body frames; the arrangement in"
                        + " sim/scene already said where the ball was");
    }

    @Test
    void aBallInMotionIsPublishedEveryCycle() {
        session(droppedBall());

        for (int tick = 0; tick < 10; tick++) {
            ticks.pump();
        }

        assertEquals(10, published.size(),
                "a moving ball should be reported on every control cycle, like the pose");
        BodiesPayload first = published.get(0);
        assertEquals(1, first.bodies.size());
        assertTrue(first.bodies.get(0).z < DROP_HEIGHT_METRES,
                "the ball should have started falling, but is still at " + first.bodies.get(0).z);
        assertTrue(published.get(9).bodies.get(0).z < first.bodies.get(0).z,
                "the ball should keep falling across cycles");
    }

    @Test
    void aBallThatHasSettledStopsBeingPublished() {
        session(droppedBall());

        // Long enough to fall 0.4 m, bounce, and come to rest: the fall alone is under 0.3 s.
        for (int tick = 0; tick < 250; tick++) {
            ticks.pump();
        }
        int whileFalling = published.size();
        published.clear();
        for (int tick = 0; tick < 50; tick++) {
            ticks.pump();
        }

        assertTrue(whileFalling > 0, "the ball never moved at all");
        assertTrue(published.isEmpty(),
                "a settled ball is still being published " + published.size() + " times; the field"
                        + " is at rest and the socket should be quiet");
    }

    /**
     * The join between the two payloads. A body whose id names no element has no colour and no
     * radius on the browser's side, which is to say it is not drawn.
     */
    @Test
    void everyPublishedBodyIsAnElementTheSceneAlreadyDescribed() {
        session(droppedBall());
        ScenePayload scene = backend.scene();

        ticks.pump();

        List<Integer> elementIds = new ArrayList<>();
        for (ScenePayload.Element element : scene.elements) {
            elementIds.add(element.id);
        }
        assertFalse(published.isEmpty(), "the dropped ball should have been published");
        for (BodiesPayload.Body body : published.get(0).bodies) {
            assertTrue(elementIds.contains(body.id),
                    "body " + body.id + " is in no scene element; ids are " + elementIds);
        }
    }

    /**
     * The camera renders from the same world, so its scene has to follow the physics rather than
     * the arrangement the session was handed.
     *
     * <p>This is the two-views-agree rule ADR-0002 states for the camera view: the Field View draws
     * {@code sim/bodies} and the detector sees {@code SceneFrameSource}, and if those two drift a
     * driver cannot tell a vision bug from a drawing bug.</p>
     */
    @Test
    void theCameraSeesTheBallWhereThePhysicsPutIt() {
        session(droppedBall());
        SceneFrameSource frames = (SceneFrameSource) backend.cameraFrames();
        Vec3 before = frames.scene().elements().get(0).centre();

        for (int tick = 0; tick < 10; tick++) {
            ticks.pump();
        }

        Vec3 after = frames.scene().elements().get(0).centre();
        assertNotEquals(before.z(), after.z(),
                "the camera is still rendering the ball where the arrangement put it");
        assertEquals(published.get(published.size() - 1).bodies.get(0).z, after.z(), 1e-9,
                "the camera and the wire disagree about where the ball is");
    }

    /**
     * And a HIVE mid-tip rides the same message, because it moves for the same reason at the same
     * moment: the shot that tips a basket is the shot that scatters what was in it.
     *
     * <p>The angle is what the browser needs and all it needs: one number against the pivot the
     * scene already described. A quaternion here would let it draw a HIVE at an angle its own axis
     * cannot reach.</p>
     */
    @Test
    void aTippingHiveIsPublishedBesideTheBallsItIsSpilling() {
        session(BioBuzzField.scene(BioBuzzField.HiveTip.AUDIENCE_UP,
                BioBuzzField.HiveTip.AUDIENCE_DOWN).withElements(tippingLoad()));

        for (int tick = 0; tick < 150; tick++) {
            ticks.pump();
        }

        BodiesPayload last = published.get(published.size() - 1);
        assertEquals(2, last.pivots.size(), "both HIVEs, whether or not they moved");
        assertEquals(BioBuzzHive.RED, last.pivots.get(0).structureName);
        assertEquals(2.0 * BioBuzzHive.TIP_DEGREES,
                Math.toDegrees(last.pivots.get(0).angleRadians), 1.0,
                "red's HIVE should have gone over to its other stop");
        assertEquals(2.0 * BioBuzzHive.TIP_DEGREES,
                Math.toDegrees(last.pivots.get(1).angleRadians), 1.0,
                "and blue's should be where its scenario staged it: " + last.pivots.get(1));

        // Mid-swing, which is the frame that matters: an angle only ever published at a stop would
        // animate as a jump and would make the manual's "LAUNCHING at a tipping HIVE" untestable.
        boolean caughtInTheAct = false;
        for (BodiesPayload frame : published) {
            double degrees = Math.toDegrees(frame.pivots.get(0).angleRadians);
            caughtInTheAct |= degrees > 5.0 && degrees < 55.0;
        }
        assertTrue(caughtInTheAct, "no frame caught the HIVE part way round");
    }

    /**
     * The rule the Field View hides captured balls by: a frame lists every ball on the field, so
     * one a frame leaves out is gone. A frame of only the balls that moved would make the browser
     * hide every ball at rest.
     */
    @Test
    void aFrameListsTheBallsAtRestBesideTheOneThatMoved() {
        SimulatedScene both = new SimulatedScene(Collections.emptyList(), Arrays.asList(
                droppedBall().elements().get(0), BioBuzzElements.pollen(-0.6, 0.5)));
        session(both);

        ticks.pump();

        assertFalse(published.isEmpty(), "the dropped ball should have been published");
        assertEquals(2, published.get(0).bodies.size(),
                "the resting ball has to be in the frame too, or the browser stops drawing it");
    }

    @TempDir
    Path configDirectory;

    /**
     * A capture on a field where nothing moves, which is the case that has no other signal: the
     * ball is taken from rest, so the only thing that changed is that it is gone.
     *
     * <p>Every view has to agree it left. The body frame stops naming it, the scene a browser gets
     * on connecting no longer describes it and keeps the survivor's id, and the camera stops
     * rendering it.</p>
     */
    @Test
    void aBallCapturedFromRestLeavesEveryViewAndTheSurvivorKeepsItsId() {
        RobotConfig pickup = pickupRobot();
        List<FakeHardwareMap> built = new ArrayList<>();
        SimulatedScene field = new SimulatedScene(Collections.emptyList(), Arrays.asList(
                BioBuzzElements.pollen(0.24, 0.0),      // id 0: at the mouth
                BioBuzzElements.pollen(1.2, 0.6)));     // id 1: out of reach
        ticks = new ManualTicks();
        OpModeInfo info = new OpModeInfo(
                "Ticking TeleOp", "", "TeleOp", TickingTeleOp.class.getName());
        backend = new LocalDashboardBackend(pickupShowing(field, pickup, built),
                Collections.singletonList(new OpModeEntry(info, TickingTeleOp::new)), ticks);
        backend.subscribeBodies(published::add);
        backend.placeRobot(0.0, 0.0, 0.0);

        ticks.pump();

        assertFalse(published.isEmpty(), "a capture is a change to the field and has to be sent");
        List<BodiesPayload.Body> bodies = published.get(published.size() - 1).bodies;
        assertEquals(1, bodies.size(), "only the ball out of reach is left");
        assertEquals(1, bodies.get(0).id, "and it is still ball 1");

        List<ScenePayload.Element> elements = backend.scene().elements;
        assertEquals(1, elements.size());
        assertEquals(1, elements.get(0).id,
                "a browser connecting now has to move ball 1's mesh, not a renumbered ball 0");

        SceneFrameSource camera = (SceneFrameSource)
                ((FakeWebcam) built.get(built.size() - 1)
                        .devices().get("Webcam 1")).frameSource();
        assertEquals(1, camera.scene().elements().size(), "the camera still sees the captured ball");
    }

    /** A four-wheeled chassis with a mouth across its nose, 0.24 m out, and nothing else. */
    private RobotConfig pickupRobot() {
        String json = "{\n"
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
                + "  \"intake\": { \"mouth\": { \"forwardMetres\": 0.24, \"leftMetres\": 0.0,"
                + " \"heightMetres\": 0.04, \"lengthMetres\": 0.08, \"widthMetres\": 0.24,"
                + " \"tallMetres\": 0.10 } }\n"
                + "}\n";
        try {
            Path file = configDirectory.resolve("pickup.json");
            Files.write(file, json.getBytes(StandardCharsets.UTF_8));
            return RobotConfig.load(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** That robot, with a camera rendering {@code scene}; every map it builds lands in {@code built}. */
    private static SimulatedRobot pickupShowing(SimulatedScene scene, RobotConfig pickup,
                                                List<FakeHardwareMap> built) {
        return new SimulatedRobot() {
            @Override
            public String name() {
                return "PickupBot";
            }

            @Override
            public FakeHardwareMap create() {
                SimulatedCamera camera = new SimulatedCamera("Webcam 1",
                        CameraIntrinsics.approximate(320, 240),
                        Pose3d.facingForward(new Vec3(0.0, 0.0, 0.3)));
                FakeHardwareMap hardware = FakeHardwareMap.builder()
                        .addMotor("FL").addMotor("FR").addMotor("BL").addMotor("BR")
                        .addImu("imu", ImuBehaviors.followingChassis())
                        .addWebcam("Webcam 1", new SceneFrameSource(scene, camera))
                        .withDrivetrain(pickup, FieldConfig.standard())
                        .withMechanisms(pickup)
                        .build();
                built.add(hardware);
                return hardware;
            }
        };
    }

    /** Six POLLEN dropped into red's raised CELL, which is enough to tip it. */
    private static List<GameElement> tippingLoad() {
        Pose3d cell = BioBuzzHive.cell(BioBuzzField.RED_AUDIENCE,
                BioBuzzField.HiveTip.AUDIENCE_UP, BioBuzzField.HiveTip.AUDIENCE_DOWN);
        Vec3 mouth = cell.position()
                .plus(cell.forward().scaled(BioBuzzHive.CELL_DEPTH_METRES / 2.0));
        List<GameElement> balls = new ArrayList<>(6);
        for (int ball = 0; ball < 6; ball++) {
            Vec3 at = mouth.plus(cell.left().scaled(((ball % 3) - 1) * 0.12))
                    .plus(new Vec3(0.0, 0.0, 0.10 + 0.09 * (ball / 3)));
            balls.add(BioBuzzElements.pollenAt(at.x(), at.y(), at.z()));
        }
        return balls;
    }

    private void session(SimulatedScene scene) {
        ticks = new ManualTicks();
        OpModeInfo info = new OpModeInfo(
                "Ticking TeleOp", "", "TeleOp", TickingTeleOp.class.getName());
        Supplier<com.qualcomm.robotcore.eventloop.opmode.OpMode> factory = TickingTeleOp::new;
        backend = new LocalDashboardBackend(robotShowing(scene),
                Collections.singletonList(new OpModeEntry(info, factory)), ticks);
        backend.subscribeBodies(published::add);
    }

    /** One POLLEN ball, already on the floor. */
    private static SimulatedScene restingBall() {
        return new SimulatedScene(Collections.emptyList(),
                Collections.singletonList(BioBuzzElements.pollen(0.3, -0.4)));
    }

    /** The same ball, held in the air, so gravity has something to do. */
    private static SimulatedScene droppedBall() {
        return new SimulatedScene(Collections.emptyList(),
                Arrays.asList(new GameElement("POLLEN",
                        new Vec3(0.3, -0.4, DROP_HEIGHT_METRES),
                        BioBuzzElements.POLLEN_DIAMETER_METRES, 240, 200, 30)));
    }

    /**
     * A robot with a camera rendering {@code scene} and no drivetrain.
     *
     * <p>No drivetrain on purpose: with no robot in the world, everything these tests see is the
     * field's own physics. What the chassis does to a ball is {@code RobotPushTest}'s subject, and
     * it does not need a socket to demonstrate.</p>
     */
    private static SimulatedRobot robotShowing(SimulatedScene scene) {
        return new SimulatedRobot() {
            @Override
            public String name() {
                return "BodyBot";
            }

            @Override
            public FakeHardwareMap create() {
                SimulatedCamera camera = new SimulatedCamera("Webcam 1",
                        CameraIntrinsics.approximate(320, 240),
                        Pose3d.facingForward(new Vec3(0.0, 0.0, 0.3)));
                return FakeHardwareMap.builder()
                        .addMotor("drive")
                        .addWebcam("Webcam 1", new SceneFrameSource(scene, camera))
                        .build();
            }
        };
    }
}
