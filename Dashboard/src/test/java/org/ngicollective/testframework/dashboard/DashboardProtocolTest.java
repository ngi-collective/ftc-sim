package org.ngicollective.testframework.dashboard;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.ngicollective.testframework.dashboard.protocol.Alliance;
import org.ngicollective.testframework.dashboard.protocol.CameraMountPayload;
import org.ngicollective.testframework.dashboard.protocol.CameraStreamInfo;
import org.ngicollective.testframework.dashboard.protocol.DeviceState;
import org.ngicollective.testframework.dashboard.protocol.Envelope;
import org.ngicollective.testframework.dashboard.protocol.OpModeInfo;
import org.ngicollective.testframework.dashboard.protocol.OpModeStatus;
import org.ngicollective.testframework.dashboard.protocol.ScenePayload;
import org.ngicollective.testframework.dashboard.protocol.SimConfigPayload;
import org.ngicollective.testframework.dashboard.protocol.SimPose;
import org.ngicollective.testframework.dashboard.protocol.SimStatus;
import org.ngicollective.testframework.dashboard.protocol.TelemetryFrame;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What each message means: which backend call it becomes, what comes back, and who hears it.
 *
 * <p>Over the protocol seam rather than a socket, which is the point of the seam. A socket test can
 * only see what one client happened to receive, so the two facts that matter most here &mdash;
 * that a reply goes to the asker while the list goes to everyone, and that a failed request is
 * answered on the namespace it arrived on &mdash; were previously unobservable.</p>
 */
class DashboardProtocolTest {

    @TempDir
    Path layoutDirectory;

    private final FakeDashboardBackend backend = new FakeDashboardBackend();
    private final RecordedReplies replies = new RecordedReplies();

    private static final CameraStreamInfo CAMERA =
            new CameraStreamInfo("http://127.0.0.1:8766/camera", 640, 480, 15.0);

    private DashboardProtocol protocol(CameraStreamInfo cameraStream) {
        return new DashboardProtocol(backend, new LayoutStore(layoutDirectory), cameraStream);
    }

    private DashboardProtocol protocol() {
        return protocol(null);
    }

    /** Sends a request the way a browser does: as text. */
    private void receive(String message) {
        protocol().receive(message, replies);
    }

    // The connect burst.

    /**
     * The whole picture, in the order {@code protocol-fixtures/handshake.json} recorded a live
     * session sending it. Order is load-bearing: the browser sizes its field view from
     * {@code sim/config} and draws into it from {@code sim/scene}, so a scene that arrived first
     * would be dropped by a view that did not exist yet.
     */
    @Test
    void greetsAFreshClientWithEveryFrameTheHandshakeFixtureRecords() {
        backend.simConfig = someSimConfig();
        backend.scene = someScene();

        assertEquals(handshakeRoutes("onOpen"), routes(protocol(CAMERA).greeting()));
    }

    /** A robot whose configuration declares no drivetrain has no geometry to describe. */
    @Test
    void greetingSkipsSimConfigWhenTheRobotHasNoDrivetrain() {
        backend.scene = someScene();

        assertEquals(Arrays.asList("opmode/list", "opmode/status", "sim/status", "sim/scene",
                "camera/stream"), routes(protocol(CAMERA).greeting()));
    }

    /**
     * A robot with no camera has no scene. Sending an empty one instead would tell the browser the
     * field is bare, which is a different and false statement.
     */
    @Test
    void greetingSkipsSimSceneWhenTheSessionHasNoScene() {
        backend.simConfig = someSimConfig();

        assertEquals(Arrays.asList("opmode/list", "opmode/status", "sim/status", "sim/config",
                "camera/stream"), routes(protocol(CAMERA).greeting()));
    }

    /**
     * Only a robot with a camera has a mount, and the mount sliders are drawn from this frame.
     * Sent after the stream because it belongs to the same panel.
     */
    @Test
    void greetingCarriesTheCameraMountOnlyWhenTheSessionHasACamera() {
        backend.simConfig = someSimConfig();
        backend.scene = someScene();

        assertFalse(routes(protocol(CAMERA).greeting()).contains("sim/camera"),
                "a robot with no camera has no mount to aim");

        backend.cameraMount = someCameraMount(false);

        assertEquals(Arrays.asList("opmode/list", "opmode/status", "sim/status", "sim/config",
                "sim/scene", "camera/stream", "sim/camera"), routes(protocol(CAMERA).greeting()));
    }

    /** No stream means the camera panel says so, rather than showing a broken image. */
    @Test
    void greetingSkipsCameraStreamWhenTheSessionServesNoStream() {
        backend.simConfig = someSimConfig();
        backend.scene = someScene();

        assertEquals(Arrays.asList("opmode/list", "opmode/status", "sim/status", "sim/config",
                "sim/scene"), routes(protocol().greeting()));
    }

    /**
     * Each stream the backend pushes arrives on its own namespace and type, because that is what
     * the browser routes on: a telemetry frame delivered as {@code sim/pose} is not a slower
     * dashboard, it is a silent one.
     */
    @Test
    void pushesEachBackendStreamOnItsOwnNamespaceAndType() {
        List<Envelope> pushed = new ArrayList<>();
        protocol().subscribe(pushed::add);

        backend.emitStatus(OpModeStatus.stopped());
        backend.emitTelemetry(new TelemetryFrame(1L, Collections.singletonList("x : 1")));
        backend.emitDeviceState(
                Collections.singletonList(new DeviceState("FL", "motor", "default")));
        backend.emitSimPose(new SimPose(1L, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, false));
        backend.emitSimConfig(someSimConfig());
        backend.emitScene(someScene());
        backend.emitSimStatus(new SimStatus(1.0, false, Alliance.BLUE));

        assertEquals(Arrays.asList("opmode/status", "telemetry/frame", "device/state", "sim/pose",
                "sim/config", "sim/scene", "sim/status"), routes(pushed));
    }

    // Requests, in the order the message table on DashboardServer lists them.

    @Test
    void opModeListRequestAnswersWithTheOpModesTheBackendKnows() {
        backend.opModes = Collections.singletonList(new OpModeInfo("I AM VERITY V2", "", "TeleOp",
                "org.firstinspires.ftc.teamcode.iamyou"));

        receive("{\"namespace\":\"opmode\",\"type\":\"list\",\"payload\":{}}");

        assertEquals(Collections.singletonList("opmode/list"), routes(replies.replied));
        assertEquals("org.firstinspires.ftc.teamcode.iamyou",
                payloadOf(replies.replied.get(0)).getAsJsonArray("opModes").get(0)
                        .getAsJsonObject().get("className").getAsString());
    }

    @Test
    void opModeInitSelectsTheClassNameItWasGiven() {
        receive("{\"namespace\":\"opmode\",\"type\":\"init\",\"payload\":"
                + "{\"className\":\"org.firstinspires.ftc.teamcode.iamyou\"}}");

        assertEquals("org.firstinspires.ftc.teamcode.iamyou", backend.initializedOpMode);
        assertEquals(Collections.emptyList(), routes(replies.replied));
    }

    @Test
    void opModeStartAndStopPressTheirButtons() {
        receive("{\"namespace\":\"opmode\",\"type\":\"start\"}");
        assertTrue(backend.started);
        assertFalse(backend.stopped);

        receive("{\"namespace\":\"opmode\",\"type\":\"stop\"}");
        assertTrue(backend.stopped);
    }

    /** Two gamepads, and driver two's input must not land on driver one's. */
    @Test
    void gamepadStateGoesToTheSlotTheRequestNames() {
        receive("{\"namespace\":\"gamepad\",\"type\":\"state\",\"payload\":"
                + "{\"gamepad\":2,\"left_stick_y\":-1.0,\"right_bumper\":true}}");

        assertEquals(Integer.valueOf(2), backend.gamepadSlot);
        assertEquals(-1.0f, backend.gamepadState.left_stick_y, 0.0);
        assertTrue(backend.gamepadState.right_bumper);
        assertFalse(backend.gamepadState.a);
    }

    /** A single-driver UI does not have to say which gamepad it is. */
    @Test
    void gamepadStateWithoutASlotGoesToTheFirstGamepad() {
        receive("{\"namespace\":\"gamepad\",\"type\":\"state\",\"payload\":"
                + "{\"left_trigger\":0.5}}");

        assertEquals(Integer.valueOf(1), backend.gamepadSlot);
        assertEquals(0.5f, backend.gamepadState.left_trigger, 0.0);
    }

    @Test
    void deviceOverrideNamesTheDeviceAndTheBehaviorToGiveIt() {
        receive("{\"namespace\":\"device\",\"type\":\"override\",\"payload\":"
                + "{\"device\":\"FL\",\"behavior\":{\"type\":\"ramping\",\"value\":0.25}}}");

        assertEquals("FL", backend.overriddenDevice);
        assertEquals("ramping", backend.overriddenBehavior.type);
        assertEquals(0.25, backend.overriddenBehavior.value, 0.0);
    }

    @Test
    void deviceResetNamesTheDeviceToRestore() {
        receive("{\"namespace\":\"device\",\"type\":\"reset\",\"payload\":{\"device\":\"FL\"}}");

        assertEquals("FL", backend.resetDevice);
    }

    @Test
    void simTimeSetsBothTheMultiplierAndThePause() {
        receive("{\"namespace\":\"sim\",\"type\":\"time\",\"payload\":"
                + "{\"multiplier\":0.25,\"paused\":true}}");

        assertEquals(Double.valueOf(0.25), backend.simTimeMultiplier);
        assertEquals(Boolean.TRUE, backend.simTimePaused);
    }

    /**
     * One client moving the speed slider must not un-pause a simulation somebody else just paused.
     * Whichever field the request leaves out is filled in from the backend's current state, not
     * from a default.
     */
    @Test
    void simTimeKeepsTheCurrentPauseWhenOnlyTheMultiplierIsSent() {
        backend.simStatus = new SimStatus(1.0, true, Alliance.RED);

        receive("{\"namespace\":\"sim\",\"type\":\"time\",\"payload\":{\"multiplier\":0.5}}");

        assertEquals(Double.valueOf(0.5), backend.simTimeMultiplier);
        assertEquals(Boolean.TRUE, backend.simTimePaused);
    }

    @Test
    void simTimeKeepsTheCurrentMultiplierWhenOnlyThePauseIsSent() {
        backend.simStatus = new SimStatus(0.25, false, Alliance.RED);

        receive("{\"namespace\":\"sim\",\"type\":\"time\",\"payload\":{\"paused\":true}}");

        assertEquals(Double.valueOf(0.25), backend.simTimeMultiplier);
        assertEquals(Boolean.TRUE, backend.simTimePaused);
    }

    @Test
    void simStepAdvancesTheNumberOfTicksItWasAsked() {
        receive("{\"namespace\":\"sim\",\"type\":\"step\",\"payload\":{\"ticks\":12}}");

        assertEquals(Integer.valueOf(12), backend.steppedTicks);
    }

    /** The step button sends no count, and one control cycle is what a step means. */
    @Test
    void simStepWithoutACountAdvancesOneTick() {
        receive("{\"namespace\":\"sim\",\"type\":\"step\",\"payload\":{}}");

        assertEquals(Integer.valueOf(1), backend.steppedTicks);
    }

    @Test
    void simPoseTeleportsTheRobotToTheCoordinatesItWasGiven() {
        receive("{\"namespace\":\"sim\",\"type\":\"pose\",\"payload\":"
                + "{\"x\":-0.9,\"y\":1.2,\"headingDegrees\":135.0}}");

        assertEquals(Double.valueOf(-0.9), backend.placedX);
        assertEquals(Double.valueOf(1.2), backend.placedY);
        assertEquals(Double.valueOf(135.0), backend.placedHeadingDegrees);
    }

    @Test
    void simAllianceReadsTheAllianceNameOffTheWire() {
        receive("{\"namespace\":\"sim\",\"type\":\"alliance\","
                + "\"payload\":{\"alliance\":\"blue\"}}");

        assertEquals(Alliance.BLUE, backend.alliance);
    }

    /**
     * All six numbers, each to its own parameter. A mount assembled in the wrong order aims the
     * camera somewhere plausible and wrong, which is the one failure the view cannot show you.
     */
    @Test
    void simCameraAimsTheCameraWithEverySixOfItsNumbers() {
        receive("{\"namespace\":\"sim\",\"type\":\"camera\",\"payload\":"
                + "{\"forwardMetres\":0.21,\"leftMetres\":-0.05,\"heightMetres\":0.3,"
                + "\"yawDegrees\":25.0,\"pitchDegrees\":-30.0,\"rollDegrees\":3.5}}");

        assertArrayEquals(new double[] {0.21, -0.05, 0.3, 25.0, -30.0, 3.5},
                backend.aimedCameraMount, 0.0);
        assertEquals(Collections.emptyList(), routes(replies.replied));
    }

    /**
     * The saver needs the path to commit, and every browser needs to stop warning that the mount
     * is about to be lost. A reply-only save would leave a second browser warning about a loss
     * that has already been prevented.
     */
    @Test
    void simCameraSaveTellsTheSaverThePathAndTellsEveryoneTheMountIsOnFile() {
        backend.cameraMount = someCameraMount(false);

        receive("{\"namespace\":\"sim\",\"type\":\"camera-save\",\"payload\":{}}");

        assertTrue(backend.savedCameraMount);
        assertEquals(Collections.singletonList("sim/camera-saved"), routes(replies.replied));
        assertEquals(backend.cameraMountFile.toAbsolutePath().toString(),
                payloadOf(replies.replied.get(0)).get("path").getAsString());

        assertEquals(Collections.singletonList("sim/camera"), routes(replies.broadcast));
        assertFalse(payloadOf(replies.broadcast.get(0)).get("unsaved").getAsBoolean());
    }

    @Test
    void simCameraRevertDropsTheSessionsMount() {
        receive("{\"namespace\":\"sim\",\"type\":\"camera-revert\",\"payload\":{}}");

        assertTrue(backend.revertedCameraMount);
    }

    // Layouts, where one request has two audiences.

    /**
     * Two people editing the same robot is the point: the saver needs the path to commit, and
     * everybody else needs to see that the file now exists. A broadcast-only reply would leave the
     * saver without the path; a reply-only one would leave the second browser stale until it
     * reloaded.
     */
    @Test
    void layoutSaveTellsTheSaverThePathAndTellsEveryoneTheNewList() {
        receive("{\"namespace\":\"layout\",\"type\":\"save\",\"payload\":"
                + "{\"name\":\"Verity comp\",\"layout\":{\"version\":1}}}");

        assertEquals(Collections.singletonList("layout/saved"), routes(replies.replied));
        assertEquals("Verity comp", payloadOf(replies.replied.get(0)).get("name").getAsString());
        assertTrue(payloadOf(replies.replied.get(0)).get("path").getAsString()
                        .endsWith("Verity comp.json"),
                "the saver is told which file to commit");

        assertEquals(Collections.singletonList("layout/list"), routes(replies.broadcast));
        assertEquals(new JsonParser().parse("[\"Verity comp\"]"),
                payloadOf(replies.broadcast.get(0)).getAsJsonArray("layouts"));
    }

    @Test
    void layoutLoadAnswersWithTheLayoutThatWasSavedUnderThatName() {
        receive("{\"namespace\":\"layout\",\"type\":\"save\",\"payload\":"
                + "{\"name\":\"Verity\",\"layout\":{\"devices\":{\"FL\":{\"x\":-0.22}}}}}");
        replies.forget();

        receive("{\"namespace\":\"layout\",\"type\":\"load\","
                + "\"payload\":{\"name\":\"Verity\"}}");

        assertEquals(Collections.singletonList("layout/data"), routes(replies.replied));
        assertEquals(new JsonParser().parse("{\"devices\":{\"FL\":{\"x\":-0.22}}}"),
                payloadOf(replies.replied.get(0)).get("layout"));
    }

    @Test
    void layoutDeleteTellsEveryoneTheFileIsGone() {
        receive("{\"namespace\":\"layout\",\"type\":\"save\",\"payload\":"
                + "{\"name\":\"Verity\",\"layout\":{}}}");
        replies.forget();

        receive("{\"namespace\":\"layout\",\"type\":\"delete\","
                + "\"payload\":{\"name\":\"Verity\"}}");

        assertEquals(Collections.singletonList("layout/list"), routes(replies.broadcast));
        assertEquals(new JsonParser().parse("[]"),
                payloadOf(replies.broadcast.get(0)).getAsJsonArray("layouts"));
    }

    // Errors. Every one of these used to be a dropped request or a closed connection.

    /**
     * A frame that is not JSON has no namespace to answer on, so it is answered on the one a fresh
     * client is already listening to. Answering at all is the contract: a browser that sent a
     * request and hears nothing waits forever.
     */
    @Test
    void answersAnUnparseableMessageOnTheOpModeNamespace() {
        receive("{\"namespace\": \"opmode\", oops");

        assertEquals(Collections.singletonList("opmode/error"), routes(replies.replied));
        assertTrue(messageOf(replies.replied.get(0)).startsWith("unparseable message: "),
                "says the frame was unreadable, not what it would have done");
    }

    @Test
    void answersAMessageMissingItsNamespaceOrItsTypeOnTheOpModeNamespace() {
        receive("{\"type\":\"start\",\"payload\":{}}");
        receive("{\"namespace\":\"opmode\",\"payload\":{}}");
        receive("null");

        assertEquals(Arrays.asList("opmode/error", "opmode/error", "opmode/error"),
                routes(replies.replied));
        assertEquals("message needs a namespace and a type", messageOf(replies.replied.get(0)));
        assertEquals("message needs a namespace and a type", messageOf(replies.replied.get(1)));
        assertEquals("message needs a namespace and a type", messageOf(replies.replied.get(2)));
    }

    /**
     * An unknown message is answered where it was sent from, not on a catch-all namespace: a
     * browser talking to an older server needs the failure to surface in the panel that tried.
     */
    @Test
    void answersAnUnknownMessageOnTheNamespaceItArrivedOn() {
        receive("{\"namespace\":\"nope\",\"type\":\"nope\",\"payload\":{}}");

        assertEquals(Collections.singletonList("nope/error"), routes(replies.replied));
        assertEquals("unknown message nope/nope", messageOf(replies.replied.get(0)));
    }

    /** A present {@code null} is as missing as an absent key, and says so the same way. */
    @Test
    void answersAMissingOrNullRequiredStringOnTheRequestingNamespace() {
        receive("{\"namespace\":\"opmode\",\"type\":\"init\",\"payload\":{}}");
        receive("{\"namespace\":\"opmode\",\"type\":\"init\","
                + "\"payload\":{\"className\":null}}");

        assertEquals(Arrays.asList("opmode/error", "opmode/error"), routes(replies.replied));
        assertEquals("IllegalArgumentException: missing \"className\"",
                messageOf(replies.replied.get(0)));
        assertEquals("IllegalArgumentException: missing \"className\"",
                messageOf(replies.replied.get(1)));
        assertNull(backend.initializedOpMode);
    }

    @Test
    void answersAMissingOrNullRequiredNumberOnTheRequestingNamespace() {
        receive("{\"namespace\":\"sim\",\"type\":\"pose\","
                + "\"payload\":{\"x\":1.0,\"headingDegrees\":0.0}}");
        receive("{\"namespace\":\"sim\",\"type\":\"pose\","
                + "\"payload\":{\"x\":1.0,\"y\":null,\"headingDegrees\":0.0}}");

        assertEquals(Arrays.asList("sim/error", "sim/error"), routes(replies.replied));
        assertEquals("IllegalArgumentException: missing \"y\"",
                messageOf(replies.replied.get(0)));
        assertEquals("IllegalArgumentException: missing \"y\"",
                messageOf(replies.replied.get(1)));
        assertNull(backend.placedX);
    }

    /**
     * The behavior catalog is closed, so an override with nothing to look up is refused before the
     * backend is touched &mdash; leaving the device on whatever behavior it already had rather than
     * on none.
     */
    @Test
    void answersADeviceOverrideWithNoUsableBehaviorOnTheDeviceNamespace() {
        receive("{\"namespace\":\"device\",\"type\":\"override\","
                + "\"payload\":{\"device\":\"FL\"}}");
        receive("{\"namespace\":\"device\",\"type\":\"override\","
                + "\"payload\":{\"device\":\"FL\",\"behavior\":{\"value\":0.25}}}");

        assertEquals(Arrays.asList("device/error", "device/error"), routes(replies.replied));
        assertEquals("IllegalArgumentException: override needs a behavior with a type",
                messageOf(replies.replied.get(0)));
        assertEquals("IllegalArgumentException: override needs a behavior with a type",
                messageOf(replies.replied.get(1)));
        assertNull(backend.overriddenDevice);
    }

    // Helpers.

    private static SimConfigPayload someSimConfig() {
        return new SimConfigPayload(
                new SimConfigPayload.Robot("Verity",
                        new SimConfigPayload.Chassis(0.38, 0.4, 0.05, 0.105),
                        new SimConfigPayload.Drivetrain("mecanum", 0.048, 1.0, 0.32, 0.29, 0.8)),
                new SimConfigPayload.Field(3.5814, 0.312, 0.6096));
    }

    private static ScenePayload someScene() {
        return new ScenePayload(Collections.<ScenePayload.Tag>emptyList(),
                Collections.<ScenePayload.Element>emptyList());
    }

    private static CameraMountPayload someCameraMount(boolean unsaved) {
        return new CameraMountPayload("Webcam 1", 0.16, 0.0, 0.105, 0.0, 35.0, 0.0,
                60.0, 46.8, unsaved);
    }

    private static List<String> routes(List<Envelope> envelopes) {
        List<String> routes = new ArrayList<>();
        for (Envelope envelope : envelopes) {
            routes.add(envelope.namespace + "/" + envelope.type);
        }
        return routes;
    }

    private static List<String> handshakeRoutes(String field) {
        List<String> routes = new ArrayList<>();
        for (JsonElement each : ProtocolFixtures.frame("handshake").getAsJsonArray(field)) {
            routes.add(each.getAsString());
        }
        return routes;
    }

    private static JsonObject payloadOf(Envelope envelope) {
        return envelope.payload.getAsJsonObject();
    }

    private static String messageOf(Envelope envelope) {
        return payloadOf(envelope).get("message").getAsString();
    }

    /** Both audiences for one request, kept apart so a test can say which heard what. */
    private static final class RecordedReplies implements DashboardProtocol.Replies {

        final List<Envelope> replied = new ArrayList<>();
        final List<Envelope> broadcast = new ArrayList<>();

        @Override
        public void reply(Envelope envelope) {
            replied.add(envelope);
        }

        @Override
        public void broadcast(Envelope envelope) {
            broadcast.add(envelope);
        }

        /** Drops what a set-up request produced, so the assertions are about the one under test. */
        void forget() {
            replied.clear();
            broadcast.clear();
        }
    }
}
