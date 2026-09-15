package org.ngicollective.testframework.dashboard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.ngicollective.testframework.camera.CameraIntrinsics;
import org.ngicollective.testframework.camera.GameElement;
import org.ngicollective.testframework.camera.Pose3d;
import org.ngicollective.testframework.camera.SceneFrameSource;
import org.ngicollective.testframework.camera.SimulatedCamera;
import org.ngicollective.testframework.camera.SimulatedScene;
import org.ngicollective.testframework.camera.Vec3;
import org.ngicollective.testframework.dashboard.protocol.BodiesPayload;
import org.ngicollective.testframework.dashboard.protocol.OpModeInfo;
import org.ngicollective.testframework.dashboard.protocol.ScenePayload;
import org.ngicollective.testframework.hardware.FakeHardwareMap;
import org.ngicollective.testframework.hardware.SimulatedRobot;

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
                Collections.singletonList(GameElement.pollen(0.3, -0.4)));
    }

    /** The same ball, held in the air, so gravity has something to do. */
    private static SimulatedScene droppedBall() {
        return new SimulatedScene(Collections.emptyList(),
                Arrays.asList(new GameElement("POLLEN",
                        new Vec3(0.3, -0.4, DROP_HEIGHT_METRES),
                        GameElement.POLLEN_DIAMETER_METRES, 240, 200, 30)));
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
