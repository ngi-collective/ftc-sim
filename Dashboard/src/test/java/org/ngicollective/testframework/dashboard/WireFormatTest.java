package org.ngicollective.testframework.dashboard;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.ngicollective.testframework.dashboard.protocol.Alliance;
import org.ngicollective.testframework.dashboard.protocol.BodiesPayload;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The bytes on the wire, pinned against frames captured off a live session.
 *
 * <p>The browser's {@code protocol.ts} is a hand-written mirror of the records in
 * {@code dashboard.protocol}, and nothing but these fixtures connects the two. Every assertion here
 * builds a record with the values in one captured frame, renders it through the server's own Gson,
 * and compares JSON trees: renaming or retyping a field on the Java side fails here, and editing
 * the fixture to agree fails the browser's conformance test instead. Neither side can move alone.
 * </p>
 *
 * <p>Trees rather than strings, deliberately. {@link JsonObject#equals} compares structurally and
 * counts a present {@code null} as different from an absent key, which is the only way to defend
 * {@code serializeNulls} &mdash; substring-matching the rendered JSON, which is all a socket test
 * could do, cannot tell "no OpMode is selected" from "this build stopped sending the field".</p>
 *
 * <p>Two captured values are environment-dependent and are read out of the fixture rather than
 * written here: the layout directory (a checkout path) and the camera stream's port (the capture
 * ran on a spare one). Everything else is verbatim.</p>
 */
class WireFormatTest {

    @TempDir
    Path layoutDirectory;

    private final FakeDashboardBackend backend = new FakeDashboardBackend();

    private DashboardProtocol protocol() {
        return new DashboardProtocol(backend, new LayoutStore(layoutDirectory), null);
    }

    /** The frame as a client would receive and reparse it, not as Java objects. */
    private JsonObject onTheWire(Envelope frame) {
        return new JsonParser().parse(protocol().encode(frame)).getAsJsonObject();
    }

    private void assertMatchesFixture(String fixture, Envelope frame) {
        assertEquals(ProtocolFixtures.frame(fixture), onTheWire(frame));
    }

    @Test
    void opModeListFrameMatchesTheCapturedFixture() {
        backend.opModes = Arrays.asList(
                new OpModeInfo("Basic Mecanum", "Linear OpMode", "TeleOp",
                        "org.firstinspires.ftc.teamcode.BasicMecanumTeleOp"),
                new OpModeInfo("Concept: AprilTag Easy", "Concept", "TeleOp",
                        "org.firstinspires.ftc.teamcode.ConceptAprilTagEasy"),
                new OpModeInfo("Example Auto", "Examples", "Autonomous",
                        "org.firstinspires.ftc.teamcode.pedroPathing.ExampleAuto"),
                new OpModeInfo("Example Auto", "Examples", "Autonomous",
                        "org.firstinspires.ftc.teamcode.pedroPathing.TestAuto"),
                new OpModeInfo("Field Relative Mecanum (Sample)", "Linear OpMode", "TeleOp",
                        "org.firstinspires.ftc.teamcode.FieldRelativeMecanumTeleOp"),
                new OpModeInfo("I AM VERITY V2", "", "TeleOp",
                        "org.firstinspires.ftc.teamcode.iamyou"),
                new OpModeInfo("Tuning", "Pedro Pathing", "TeleOp",
                        "org.firstinspires.ftc.teamcode.pedroPathing.Tuning"));

        assertMatchesFixture("opmode-list", protocol().opModeListEnvelope());
    }

    /**
     * The state a browser connects into, where every nullable field is null at once. A build that
     * dropped them would leave the browser's {@code opMode} and {@code failure} reading as
     * "unchanged" rather than as "none", so the dashboard would keep showing the last OpMode it
     * saw after a stop.
     */
    @Test
    void opModeStatusFrameSendsANullOpModeAndFailureAsPresentNulls() {
        assertMatchesFixture("opmode-status",
                protocol().envelope("opmode", "status", OpModeStatus.stopped()));
    }

    /**
     * {@code OpModeState} in {@code protocol.ts} is the literal union
     * {@code 'STOPPED' | 'INIT' | 'RUNNING'}, so the enum's Java spelling is the wire spelling. Only
     * STOPPED appears in a capture; the other two decide whether the browser can tell a robot
     * holding in init from one that is driving.
     */
    @Test
    void opModeStateCrossesTheWireAsItsUppercaseName() {
        assertEquals(new JsonPrimitive("INIT"), stateOnTheWire(OpModeStatus.State.INIT));
        assertEquals(new JsonPrimitive("RUNNING"), stateOnTheWire(OpModeStatus.State.RUNNING));
        assertEquals(new JsonPrimitive("STOPPED"), stateOnTheWire(OpModeStatus.State.STOPPED));
    }

    private JsonElement stateOnTheWire(OpModeStatus.State state) {
        Envelope frame = protocol().envelope("opmode", "status",
                new OpModeStatus("org.firstinspires.ftc.teamcode.iamyou", state, null));
        return onTheWire(frame).getAsJsonObject("payload").get("state");
    }

    @Test
    void telemetryFrameMatchesTheCapturedFixture() {
        TelemetryFrame frame = new TelemetryFrame(1789410542331L, Arrays.asList(
                "Cardinal Direction : NORTH",
                "INIT Direction : NORTH",
                "Driver Position : NORTH  (D-pad to change)",
                "--- : ---",
                "Heading Raw (deg) : 29.39",
                "Heading Corrected (deg) : 29.39",
                "Slow Mode : ON",
                "Drive Input : -1.00",
                "Strafe Input : 0.00",
                "Twist Input : -0.40",
                "Field Drive : -0.87",
                "Field Strafe : 0.49",
                "FL | FR : -0.19 | -0.16",
                "BL | BR : -0.38 | 0.04",
                "FL Vel | FR Vel : -0 | -438",
                "BL Vel | BR Vel : -1074 | 101"));

        assertMatchesFixture("telemetry-frame", protocol().envelope("telemetry", "frame", frame));
    }

    /**
     * The whole captured snapshot: the {@code imu} row, the four motors including {@code FL} whose
     * commanded and physical power have opposite signs because it was stalled, and the webcam,
     * which the server describes as {@code kind: "unknown"} because it is neither motor, servo nor
     * IMU. Every row carries every field of the flat shape, so a rename anywhere in
     * {@link DeviceState} fails here.
     */
    @Test
    void deviceStateFrameWrapsEveryCapturedRowUnderDevices() {
        DeviceState imu = new DeviceState("imu", "imu", "default");
        imu.yawDegrees = 30.167441323937418;
        imu.yawRateDegreesPerSecond = 19.618162212358747;

        DeviceState frontLeft = new DeviceState("FL", "motor", "stalled");
        frontLeft.commandedPower = -0.18488659977977415;
        frontLeft.physicalPower = 0.18488659977977415;
        frontLeft.mode = "RUN_USING_ENCODER";

        DeviceState frontRight = new DeviceState("FR", "motor", "default");
        frontRight.commandedPower = -0.15759319012838702;
        frontRight.physicalPower = -0.15759319012838702;
        frontRight.velocityTicksPerSecond = -440.6368633265753;
        frontRight.position = -732;
        frontRight.mode = "RUN_USING_ENCODER";

        DeviceState backLeft = new DeviceState("BL", "motor", "default");
        backLeft.commandedPower = -0.3839541429185199;
        backLeft.physicalPower = 0.3839541429185199;
        backLeft.velocityTicksPerSecond = -1073.5511417658986;
        backLeft.position = -2081;
        backLeft.mode = "RUN_USING_ENCODER";

        DeviceState backRight = new DeviceState("BR", "motor", "default");
        backRight.commandedPower = 0.04147435301035873;
        backRight.physicalPower = 0.04147435301035873;
        backRight.velocityTicksPerSecond = 115.96394999108345;
        backRight.position = -190;
        backRight.mode = "RUN_USING_ENCODER";

        DeviceState webcam = new DeviceState("Webcam 1", "unknown", "default");

        assertMatchesFixture("device-state", protocol().deviceStateEnvelope(
                Arrays.asList(imu, frontLeft, frontRight, backLeft, backRight, webcam)));
    }

    @Test
    void simPoseFrameMatchesTheCapturedFixture() {
        SimPose pose = new SimPose(1789410542350L, 2.540000000000002, -0.32633466714859016,
                -0.37442199661181996, 30.167441323937418, -0.19667981378816835,
                -0.18223449155848612, 19.61816221235879, false);

        assertMatchesFixture("sim-pose", protocol().envelope("sim", "pose", pose));
    }

    @Test
    void simStatusFrameMatchesTheCapturedFixture() {
        SimStatus status = new SimStatus(1.0, false, Alliance.RED);

        assertMatchesFixture("sim-status", protocol().envelope("sim", "status", status));
    }

    /**
     * {@code Alliance} is the one enum whose wire name differs from its Java name, and the
     * {@code @SerializedName} that makes that true is a single annotation with nothing else
     * depending on it. Dropping it would send {@code "RED"} to a browser whose {@code Alliance} type
     * is {@code 'red' | 'blue'}, which typechecks on neither side but fails only at runtime.
     */
    @Test
    void allianceCrossesTheWireAsItsLowercaseName() {
        assertEquals(new JsonPrimitive("red"), allianceOnTheWire(Alliance.RED));
        assertEquals(new JsonPrimitive("blue"), allianceOnTheWire(Alliance.BLUE));
    }

    private JsonElement allianceOnTheWire(Alliance alliance) {
        Envelope frame = protocol().envelope("sim", "status", new SimStatus(1.0, false, alliance));
        return onTheWire(frame).getAsJsonObject("payload").get("alliance");
    }

    /**
     * The other direction, which no {@code @SerializedName} covers: {@code sim/alliance} arrives as
     * a string and {@link Alliance#fromWire} reads it by name, case-insensitively, so a client that
     * echoes back the uppercase Java name it saw somewhere still works.
     */
    @Test
    void allianceParsesTheWireNameWhateverItsCase() {
        assertEquals(Alliance.RED, Alliance.fromWire("red"));
        assertEquals(Alliance.BLUE, Alliance.fromWire("blue"));
        assertEquals(Alliance.BLUE, Alliance.fromWire("BLUE"));
        assertEquals(Alliance.RED, Alliance.fromWire("Red"));

        assertThrows(IllegalArgumentException.class, () -> Alliance.fromWire("green"));
        assertThrows(IllegalArgumentException.class, () -> Alliance.fromWire(null));
    }

    @Test
    void simConfigFrameMatchesTheCapturedFixture() {
        SimConfigPayload config = new SimConfigPayload(
                new SimConfigPayload.Robot("Verity",
                        new SimConfigPayload.Chassis(0.38, 0.4, 0.05, 0.105),
                        new SimConfigPayload.Drivetrain("mecanum", 0.048, 1.0, 0.32, 0.29, 0.8)),
                new SimConfigPayload.Field(3.5814, 0.312, 0.6096));

        assertMatchesFixture("sim-config", protocol().envelope("sim", "config", config));
    }

    /**
     * All seventeen captured tags, rebuilt tag by tag and corner by corner. The geometry is read
     * back out of the fixture because seventeen tags of literals would be unreadable; the field
     * <i>names</i> still come from the Java records, which is what the comparison pins.
     */
    @Test
    void simSceneFrameMatchesTheCapturedFixture() {
        assertMatchesFixture("sim-scene",
                protocol().envelope("sim", "scene", sceneInFixture()));
    }

    /**
     * The bodies frame, rebuilt from the capture body by body.
     *
     * <p>Positions and orientations are read back out of the fixture rather than written as
     * literals, for the reason the scene test gives: six balls of eight numbers each would be
     * unreadable, and it is the field <i>names</i> the comparison pins. What the capture itself
     * pins is that they are not all zero &mdash; it was taken mid-shove, with every ball rolling
     * &mdash; so a frame of resting identity quaternions could not pass as one of these.</p>
     */
    @Test
    void simBodiesFrameMatchesTheCapturedFixture() {
        JsonObject captured = ProtocolFixtures.payload("sim-bodies");

        List<BodiesPayload.Body> bodies = new ArrayList<>();
        for (JsonElement each : captured.getAsJsonArray("bodies")) {
            JsonObject body = each.getAsJsonObject();
            bodies.add(new BodiesPayload.Body(body.get("id").getAsInt(),
                    body.get("x").getAsDouble(), body.get("y").getAsDouble(),
                    body.get("z").getAsDouble(),
                    body.get("qx").getAsDouble(), body.get("qy").getAsDouble(),
                    body.get("qz").getAsDouble(), body.get("qw").getAsDouble()));
        }

        assertMatchesFixture("sim-bodies", protocol().envelope("sim", "bodies",
                new BodiesPayload(captured.get("timestampMillis").getAsLong(),
                        captured.get("elapsedSeconds").getAsDouble(), bodies)));
    }

    /**
     * One tag written out by hand, against the first tag in the capture. Corner order and cell
     * orientation are the contract {@link ScenePayload} spends a section on, and they are the pair a
     * reader has to be able to check by eye: a mirrored tag36h11 pattern is mostly not a valid
     * codeword, so getting either backwards renders a plausible-looking tag that the detector
     * cannot see.
     */
    @Test
    void aTagSpelledOutByHandMatchesTheFirstTagInTheCapture() {
        ScenePayload.Tag tag = new ScenePayload.Tag(30, "RED SCORING", 0.08255,
                Arrays.asList(
                        new ScenePayload.Corner(-0.19982180000000002, 0.25366240145879737,
                                0.9260712999999999),
                        new ScenePayload.Corner(-0.11727180000000001, 0.25366240145879737,
                                0.9260712999999999),
                        new ScenePayload.Corner(-0.11727180000000001, 0.3251527985412027,
                                0.8847963),
                        new ScenePayload.Corner(-0.19982180000000002, 0.3251527985412027,
                                0.8847963)),
                Arrays.asList(
                        "BBBBBBBB",
                        "BBWWBWWB",
                        "BWBBBBBB",
                        "BWWBBBWB",
                        "BBWWWBWB",
                        "BBWBWBBB",
                        "BWBBWWWB",
                        "BBBBBBBB"));

        Envelope frame = protocol().envelope("sim", "scene", new ScenePayload(
                Collections.singletonList(tag), Collections.<ScenePayload.Element>emptyList()));

        assertEquals(ProtocolFixtures.payload("sim-scene").getAsJsonArray("tags").get(0),
                onTheWire(frame).getAsJsonObject("payload").getAsJsonArray("tags").get(0));
    }

    /**
     * A game element, against the shape the browser's {@code SceneElement} declares.
     *
     * <p>Written out here rather than read from a fixture because the captured scene's
     * {@code elements} array is empty &mdash; the session's camera published tags and no game
     * elements &mdash; so the capture pins the key's presence and nothing about its contents. This
     * is the one payload on the wire with no captured example, and until a scene with elements in
     * it is captured, this is what keeps a rename of {@code radiusMetres} or of the colour channels
     * from reaching the browser silently.</p>
     */
    @Test
    void gameElementCrossesTheWireAsANamedColouredSphere() {
        ScenePayload.Element pollen =
                new ScenePayload.Element(2, "POLLEN", 0.3, -0.45, 0.0381, 0.0381, 255, 214, 0);

        Envelope frame = protocol().envelope("sim", "scene", new ScenePayload(
                Collections.<ScenePayload.Tag>emptyList(), Collections.singletonList(pollen)));

        assertEquals(new JsonParser().parse("{\"id\":2,\"name\":\"POLLEN\",\"x\":0.3,\"y\":-0.45,"
                        + "\"z\":0.0381,\"radiusMetres\":0.0381,"
                        + "\"red\":255,\"green\":214,\"blue\":0}"),
                onTheWire(frame).getAsJsonObject("payload").getAsJsonArray("elements").get(0));
    }

    /**
     * The URL comes out of the fixture because the capture ran on a spare port; what is being
     * pinned is that the browser is told a whole URL plus the frame size and rate, not that the
     * default port is 8766.
     */
    @Test
    void cameraStreamFrameMatchesTheCapturedFixture() {
        JsonObject captured = ProtocolFixtures.payload("camera-stream");
        CameraStreamInfo stream = new CameraStreamInfo(captured.get("url").getAsString(),
                640, 480, 15.0);

        assertMatchesFixture("camera-stream", protocol().envelope("camera", "stream", stream));
    }

    /**
     * The layout list, whose {@code directory} is this checkout's path and so is the one value the
     * fixture holds a placeholder for. It is on the wire because the UI tells the user which files
     * to commit, and a list with no directory would be a list of names nobody can find.
     */
    @Test
    void layoutListFrameNamesTheDirectoryItWroteTo() {
        LayoutStore layouts = new LayoutStore(layoutDirectory);
        layouts.save("vertical-shafts", new JsonObject());
        DashboardProtocol protocol = new DashboardProtocol(backend, layouts, null);

        JsonObject expected = ProtocolFixtures.frame("layout-list");
        expected.getAsJsonObject("payload")
                .addProperty("directory", layouts.directory().toString());

        assertEquals(expected,
                new JsonParser().parse(protocol.encode(protocol.layoutListEnvelope())));
    }

    /**
     * A failed request answers on the namespace it arrived on, because that is what the browser
     * routes on: an error from a device override has to reach the device panel, not the OpMode bar.
     * All three captured errors are the same envelope with a different namespace, which is the
     * property worth pinning.
     */
    @Test
    void errorFramesCarryTheirMessageOnTheRequestingNamespace() {
        assertMatchesFixture("opmode-error",
                Envelope.error("opmode", "IllegalArgumentException: no OpMode named does.not.Exist"));
        assertMatchesFixture("device-error", Envelope.error("device",
                "IllegalArgumentException: no simulated device named \"front_left\""));
        assertMatchesFixture("unknown-error",
                Envelope.error("nope", "unknown message nope/nope"));
    }

    private static ScenePayload sceneInFixture() {
        JsonObject captured = ProtocolFixtures.payload("sim-scene");
        List<ScenePayload.Tag> tags = new ArrayList<>();
        for (JsonElement element : captured.getAsJsonArray("tags")) {
            JsonObject tag = element.getAsJsonObject();
            List<ScenePayload.Corner> corners = new ArrayList<>();
            for (JsonElement each : tag.getAsJsonArray("corners")) {
                JsonObject corner = each.getAsJsonObject();
                corners.add(new ScenePayload.Corner(corner.get("x").getAsDouble(),
                        corner.get("y").getAsDouble(), corner.get("z").getAsDouble()));
            }
            List<String> cells = new ArrayList<>();
            for (JsonElement row : tag.getAsJsonArray("cells")) {
                cells.add(row.getAsString());
            }
            tags.add(new ScenePayload.Tag(tag.get("id").getAsInt(),
                    tag.get("cluster").getAsString(), tag.get("sizeMetres").getAsDouble(),
                    corners, cells));
        }

        List<ScenePayload.Element> elements = new ArrayList<>();
        JsonArray capturedElements = captured.getAsJsonArray("elements");
        for (JsonElement each : capturedElements) {
            JsonObject element = each.getAsJsonObject();
            elements.add(new ScenePayload.Element(element.get("id").getAsInt(),
                    element.get("name").getAsString(),
                    element.get("x").getAsDouble(), element.get("y").getAsDouble(),
                    element.get("z").getAsDouble(), element.get("radiusMetres").getAsDouble(),
                    element.get("red").getAsInt(), element.get("green").getAsInt(),
                    element.get("blue").getAsInt()));
        }
        return new ScenePayload(tags, elements);
    }
}
