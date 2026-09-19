package org.ngicollective.testframework.dashboard;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;

import org.ngicollective.testframework.dashboard.protocol.Alliance;
import org.ngicollective.testframework.dashboard.protocol.BehaviorSpec;
import org.ngicollective.testframework.dashboard.protocol.CameraMountPayload;
import org.ngicollective.testframework.dashboard.protocol.CameraStreamInfo;
import org.ngicollective.testframework.dashboard.protocol.DeviceState;
import org.ngicollective.testframework.dashboard.protocol.Envelope;
import org.ngicollective.testframework.dashboard.protocol.GamepadState;
import org.ngicollective.testframework.dashboard.protocol.ScenariosPayload;
import org.ngicollective.testframework.dashboard.protocol.ScenePayload;
import org.ngicollective.testframework.dashboard.protocol.ScorePayload;
import org.ngicollective.testframework.dashboard.protocol.SimConfigPayload;
import org.ngicollective.testframework.dashboard.protocol.SimStatus;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * The dashboard's protocol: which message means which backend call, and what goes back.
 *
 * <p>Split out of {@link DashboardServer} so the two halves can be checked separately, because
 * they fail for unrelated reasons and used to only be reachable together. The protocol is where a
 * renamed field, a reply on the wrong namespace or a greeting missing {@code sim/config} lives, and
 * none of those need a socket to demonstrate &mdash; testing them through one meant binding a port
 * and substring-matching JSON, which cannot tell a present {@code null} from an absent key and so
 * could not defend {@code serializeNulls} at all. What is left in the server is the socket
 * &mdash; the bind address, accept behaviour and connection lifetime &mdash; which is worth
 * exercising over a real port and does not need a simulated robot to do it.</p>
 *
 * <p>Nothing here mentions {@code WebSocket}. Delivery arrives as {@link Replies}: one client for a
 * reply, every client for a broadcast.</p>
 *
 * <p>The message table both ways is documented on {@link DashboardServer}.</p>
 */
final class DashboardProtocol {

    /**
     * Where a message too broken to have a namespace is answered.
     *
     * <p>An unparseable frame has no namespace to reply on, and dropping it silently would leave a
     * browser waiting forever on a request it thinks it sent. {@code opmode} is the namespace a
     * fresh client is already listening to.</p>
     */
    private static final String FALLBACK_NAMESPACE = "opmode";

    // serializeNulls: the protocol declares nullable fields (a stopped session has no opMode, a
    // clean run has no failure). Dropping them would make "absent" and "null" indistinguishable to
    // the browser.
    private final Gson gson = new GsonBuilder().serializeNulls().create();
    private final DashboardBackend backend;
    private final LayoutStore layouts;
    private final CameraStreamInfo cameraStream;

    DashboardProtocol(DashboardBackend backend, LayoutStore layouts,
            CameraStreamInfo cameraStream) {
        this.backend = backend;
        this.layouts = layouts;
        this.cameraStream = cameraStream;
    }

    /** Where one request's answers go: {@code reply} to whoever asked, {@code broadcast} to all. */
    interface Replies {

        void reply(Envelope envelope);

        void broadcast(Envelope envelope);
    }

    /** A message that cannot be dispatched at all, because it did not survive being read. */
    static final class MalformedMessage extends RuntimeException {

        MalformedMessage(String message) {
            super(message);
        }
    }

    /**
     * Points every stream the backend pushes at {@code broadcasts}, already enveloped.
     *
     * <p>Shaping happens here and delivery happens in the caller, so what the browser receives on
     * each stream is checkable without a client to receive it.</p>
     */
    void subscribe(Consumer<Envelope> broadcasts) {
        backend.subscribeStatus(status -> broadcasts.accept(envelope("opmode", "status", status)));
        backend.subscribeTelemetry(
                frame -> broadcasts.accept(envelope("telemetry", "frame", frame)));
        backend.subscribeDeviceState(devices -> broadcasts.accept(deviceStateEnvelope(devices)));
        backend.subscribeSimPose(pose -> broadcasts.accept(envelope("sim", "pose", pose)));
        backend.subscribeSimConfig(config -> broadcasts.accept(envelope("sim", "config", config)));
        backend.subscribeScene(scene -> broadcasts.accept(envelope("sim", "scene", scene)));
        // Not in the greeting: sim/scene already carries every element's current position, so a
        // browser that has just connected is up to date until the next thing moves.
        backend.subscribeBodies(bodies -> broadcasts.accept(envelope("sim", "bodies", bodies)));
        // In the greeting, unlike the bodies: a browser cannot derive the score from anything else
        // on this socket. See ScorePayload.
        backend.subscribeScore(score -> broadcasts.accept(envelope("sim", "score", score)));
        backend.subscribeSimStatus(status -> broadcasts.accept(envelope("sim", "status", status)));
        // The camera's mount, because a slider that aims it has to move on every browser watching:
        // two people looking at one view must not disagree about where the camera is.
        backend.subscribeCameraMount(
                mount -> broadcasts.accept(envelope("sim", "camera", mount)));
    }

    /** What a fresh browser needs before it can render anything, in the order it is sent. */
    List<Envelope> greeting() {
        List<Envelope> greeting = new ArrayList<>();
        greeting.add(opModeListEnvelope());
        greeting.add(envelope("opmode", "status", backend.status()));
        greeting.add(envelope("sim", "status", backend.simStatus()));
        // Geometry exists as soon as the session has a robot that can drive, which is before any
        // OpMode runs; only a robot with no drivetrain has none.
        SimConfigPayload simConfig = backend.simConfig();
        if (simConfig != null) {
            greeting.add(envelope("sim", "config", simConfig));
        }
        // The field's contents come from the camera's scene, so only a scene-backed camera has any.
        ScenePayload scene = backend.scene();
        if (scene != null) {
            greeting.add(envelope("sim", "scene", scene));
        }
        ScorePayload score = backend.score();
        if (score != null) {
            greeting.add(envelope("sim", "score", score));
        }
        // Beside the score and for the same reason: the alternatives are files on this machine's
        // disk, which a browser has no other way of hearing about.
        ScenariosPayload scenarios = backend.scenarios();
        if (scenarios != null) {
            greeting.add(envelope("sim", "scenarios", scenarios));
        }
        if (cameraStream != null) {
            greeting.add(envelope("camera", "stream", cameraStream));
        }
        // Beside the stream, because the browser draws the mount controls onto the camera panel:
        // only a robot with a camera has a mount to aim.
        CameraMountPayload mount = backend.cameraMount();
        if (mount != null) {
            greeting.add(envelope("sim", "camera", mount));
        }
        return greeting;
    }

    /**
     * Reads one message off the wire and answers it, including answering that it was unreadable.
     *
     * <p>Every failure a request can produce becomes an {@code error} on the requesting namespace,
     * which is what the browser routes on, rather than an exception that would take the connection
     * down with it.</p>
     */
    void receive(String message, Replies replies) {
        Envelope request;
        try {
            request = decode(message);
        } catch (MalformedMessage e) {
            replies.reply(Envelope.error(FALLBACK_NAMESPACE, e.getMessage()));
            return;
        }

        try {
            handle(request, replies);
        } catch (RuntimeException e) {
            replies.reply(Envelope.error(request.namespace,
                    e.getClass().getSimpleName() + ": " + e.getMessage()));
        }
    }

    /**
     * Parses a browser's message.
     *
     * @throws MalformedMessage if it is not JSON, or is JSON that could address nothing
     */
    Envelope decode(String message) {
        Envelope request;
        try {
            request = gson.fromJson(message, Envelope.class);
        } catch (JsonParseException e) {
            throw new MalformedMessage("unparseable message: " + e.getMessage());
        }
        if (request == null || request.namespace == null || request.type == null) {
            throw new MalformedMessage("message needs a namespace and a type");
        }
        return request;
    }

    /** Renders an envelope for the wire, nulls and all. */
    String encode(Envelope envelope) {
        return gson.toJson(envelope);
    }

    /** Runs one decoded request. Anything it throws is the caller's to turn into an error reply. */
    void handle(Envelope request, Replies replies) {
        JsonObject payload = request.payload != null && request.payload.isJsonObject()
                ? request.payload.getAsJsonObject()
                : new JsonObject();

        switch (request.namespace + "/" + request.type) {
            case "opmode/list":
                replies.reply(opModeListEnvelope());
                break;
            case "opmode/init":
                backend.initOpMode(requireString(payload, "className"));
                break;
            case "opmode/start":
                backend.start();
                break;
            case "opmode/stop":
                backend.stop();
                break;
            case "gamepad/state": {
                int which = payload.has("gamepad") ? payload.get("gamepad").getAsInt() : 1;
                backend.injectGamepadState(which, gson.fromJson(payload, GamepadState.class));
                break;
            }
            case "device/override": {
                BehaviorSpec spec = gson.fromJson(payload.get("behavior"), BehaviorSpec.class);
                if (spec == null || spec.type == null) {
                    throw new IllegalArgumentException("override needs a behavior with a type");
                }
                backend.overrideDeviceBehavior(requireString(payload, "device"), spec);
                break;
            }
            case "device/reset":
                backend.resetDeviceBehavior(requireString(payload, "device"));
                break;
            case "layout/list":
                replies.reply(layoutListEnvelope());
                break;
            case "layout/save": {
                String name = requireString(payload, "name");
                Path file = layouts.save(name, payload.get("layout"));
                JsonObject saved = new JsonObject();
                saved.addProperty("name", name);
                saved.addProperty("path", file.toString());
                replies.reply(new Envelope("layout", "saved", saved));
                // Every client sees the new file: two people editing the same robot is the point.
                replies.broadcast(layoutListEnvelope());
                break;
            }
            case "layout/load": {
                String name = requireString(payload, "name");
                JsonObject loaded = new JsonObject();
                loaded.addProperty("name", name);
                loaded.add("layout", layouts.read(name));
                replies.reply(new Envelope("layout", "data", loaded));
                break;
            }
            case "layout/delete":
                layouts.delete(requireString(payload, "name"));
                replies.broadcast(layoutListEnvelope());
                break;
            case "sim/time": {
                // Either field may be omitted, so a client that only moves the speed slider does
                // not have to know whether someone else just paused.
                SimStatus current = backend.simStatus();
                backend.setSimTime(
                        optionalDouble(payload, "multiplier", current.multiplier),
                        optionalBoolean(payload, "paused", current.paused));
                break;
            }
            case "sim/step":
                backend.stepSim((int) optionalDouble(payload, "ticks", 1));
                break;
            case "sim/pose":
                backend.placeRobot(requireDouble(payload, "x"), requireDouble(payload, "y"),
                        requireDouble(payload, "headingDegrees"));
                break;
            case "sim/alliance":
                backend.setAlliance(Alliance.fromWire(requireString(payload, "alliance")));
                break;
            case "sim/scenarios":
                replies.reply(scenariosEnvelope());
                break;
            case "sim/scenario": {
                // Null is a value here, not an omission: it is how the browser asks for the field
                // without a scenario on it, so this is the one sim request that does not require
                // its field.
                backend.loadScenario(optionalString(payload, "name", null));
                // Broadcast rather than replied to, and not through a subscription: an arrangement
                // only ever changes because somebody asked for this, so there is nothing for the
                // backend to notify anyone about -- but two browsers watching one field must not
                // disagree about which arrangement they are looking at.
                replies.broadcast(scenariosEnvelope());
                break;
            }
            case "sim/camera":
                // Every number required: a mount with a missing angle is not a camera aimed
                // somewhere sensible, it is five numbers and a guess.
                backend.setCameraMount(
                        requireDouble(payload, "forwardMetres"),
                        requireDouble(payload, "leftMetres"),
                        requireDouble(payload, "heightMetres"),
                        requireDouble(payload, "yawDegrees"),
                        requireDouble(payload, "pitchDegrees"),
                        requireDouble(payload, "rollDegrees"));
                break;
            case "sim/camera-save": {
                JsonObject saved = new JsonObject();
                saved.addProperty("path",
                        backend.saveCameraMount().toAbsolutePath().toString());
                replies.reply(new Envelope("sim", "camera-saved", saved));
                // The saver is told which file to commit; everyone watching is told the mount is
                // no longer unsaved, so a second browser stops warning about a loss that has
                // already been prevented.
                CameraMountPayload onFile = backend.cameraMount();
                if (onFile != null) {
                    replies.broadcast(envelope("sim", "camera", onFile));
                }
                break;
            }
            case "sim/camera-revert":
                backend.revertCameraMount();
                break;
            default:
                replies.reply(Envelope.error(request.namespace, "unknown message " + request));
        }
    }

    Envelope opModeListEnvelope() {
        JsonObject payload = new JsonObject();
        payload.add("opModes", gson.toJsonTree(backend.listOpModes()));
        return new Envelope("opmode", "list", payload);
    }

    Envelope layoutListEnvelope() {
        JsonObject payload = new JsonObject();
        payload.add("layouts", gson.toJsonTree(layouts.list()));
        payload.addProperty("directory", layouts.directory().toString());
        return new Envelope("layout", "list", payload);
    }

    /**
     * What the field can be arranged as, and what it is arranged as now.
     *
     * <p>One frame for both, because they are one answer: a picker needs the options and the
     * selection together, and splitting them across two messages would let a browser draw a list
     * with nothing selected in the window between them.</p>
     */
    Envelope scenariosEnvelope() {
        return envelope("sim", "scenarios", backend.scenarios());
    }

    /**
     * The device snapshot, wrapped.
     *
     * <p>A bare array would leave the browser no place to put anything it later learns about the
     * snapshot as a whole, and changing the shape afterwards breaks every client at once.</p>
     */
    Envelope deviceStateEnvelope(List<DeviceState> devices) {
        JsonObject payload = new JsonObject();
        payload.add("devices", gson.toJsonTree(devices));
        return new Envelope("device", "state", payload);
    }

    Envelope envelope(String namespace, String type, Object payload) {
        return new Envelope(namespace, type, gson.toJsonTree(payload));
    }

    private static String requireString(JsonObject payload, String field) {
        if (!payload.has(field) || payload.get(field).isJsonNull()) {
            throw new IllegalArgumentException("missing \"" + field + "\"");
        }
        return payload.get(field).getAsString();
    }

    private static double requireDouble(JsonObject payload, String field) {
        if (!payload.has(field) || payload.get(field).isJsonNull()) {
            throw new IllegalArgumentException("missing \"" + field + "\"");
        }
        return payload.get(field).getAsDouble();
    }

    private static double optionalDouble(JsonObject payload, String field, double fallback) {
        return payload.has(field) && !payload.get(field).isJsonNull()
                ? payload.get(field).getAsDouble()
                : fallback;
    }

    private static boolean optionalBoolean(JsonObject payload, String field, boolean fallback) {
        return payload.has(field) && !payload.get(field).isJsonNull()
                ? payload.get(field).getAsBoolean()
                : fallback;
    }

    private static String optionalString(JsonObject payload, String field, String fallback) {
        return payload.has(field) && !payload.get(field).isJsonNull()
                ? payload.get(field).getAsString()
                : fallback;
    }
}
