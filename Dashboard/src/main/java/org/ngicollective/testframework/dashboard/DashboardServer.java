package org.ngicollective.testframework.dashboard;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;

import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;
import org.ngicollective.testframework.dashboard.protocol.BehaviorSpec;
import org.ngicollective.testframework.dashboard.protocol.Envelope;
import org.ngicollective.testframework.dashboard.protocol.GamepadState;

import java.net.InetSocketAddress;

/**
 * Serves the dashboard's single WebSocket.
 *
 * <p>Every message both ways is an {@link Envelope}. Requests a browser can send:</p>
 * <pre>
 * opmode/list    {}                                 -&gt; opmode/list   {opModes:[...]}
 * opmode/init    {className}                        -&gt; opmode/status {opMode, state}
 * opmode/start   {}                                 -&gt; opmode/status
 * opmode/stop    {}                                 -&gt; opmode/status
 * gamepad/state  {gamepad, left_stick_x, ...}       (no reply)
 * device/override{device, behavior:{type, value}}   (next device/state reflects it)
 * device/reset   {device}
 * </pre>
 * <p>Pushed without being asked: {@code opmode/status}, {@code telemetry/frame},
 * {@code device/state}. A request that fails comes back as {@code &lt;namespace&gt;/error}.</p>
 */
public class DashboardServer extends WebSocketServer {

    // serializeNulls: the protocol declares nullable fields (a stopped session has no opMode, a
    // clean run has no failure). Dropping them would make "absent" and "null" indistinguishable to
    // the browser.
    private final Gson gson = new GsonBuilder().serializeNulls().create();
    private final DashboardBackend backend;

    public DashboardServer(DashboardBackend backend, int port) {
        super(new InetSocketAddress(port));
        this.backend = backend;
        setReuseAddr(true);

        backend.subscribeStatus(status -> broadcast("opmode", "status", status));
        backend.subscribeTelemetry(frame -> broadcast("telemetry", "frame", frame));
        backend.subscribeDeviceState(devices -> {
            JsonObject payload = new JsonObject();
            payload.add("devices", gson.toJsonTree(devices));
            broadcast(new Envelope("device", "state", payload));
        });
    }

    @Override
    public void onOpen(WebSocket connection, ClientHandshake handshake) {
        // A fresh browser needs the whole picture before it can render anything.
        send(connection, opModeListEnvelope());
        send(connection, envelope("opmode", "status", backend.status()));
    }

    @Override
    public void onClose(WebSocket connection, int code, String reason, boolean remote) {
        // Sessions outlive browsers on purpose: a page reload must not stop a running OpMode.
    }

    @Override
    public void onMessage(WebSocket connection, String message) {
        Envelope request;
        try {
            request = gson.fromJson(message, Envelope.class);
        } catch (JsonParseException e) {
            send(connection, Envelope.error("opmode", "unparseable message: " + e.getMessage()));
            return;
        }
        if (request == null || request.namespace == null || request.type == null) {
            send(connection, Envelope.error("opmode", "message needs a namespace and a type"));
            return;
        }

        try {
            dispatch(connection, request);
        } catch (RuntimeException e) {
            send(connection, Envelope.error(request.namespace,
                    e.getClass().getSimpleName() + ": " + e.getMessage()));
        }
    }

    @Override
    public void onError(WebSocket connection, Exception e) {
        System.err.println("[dashboard] websocket error: " + e);
    }

    @Override
    public void onStart() {
        System.out.println("[dashboard] listening on ws://localhost:" + getPort());
    }

    private void dispatch(WebSocket connection, Envelope request) {
        JsonObject payload = request.payload != null && request.payload.isJsonObject()
                ? request.payload.getAsJsonObject()
                : new JsonObject();

        switch (request.namespace + "/" + request.type) {
            case "opmode/list":
                send(connection, opModeListEnvelope());
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
            default:
                send(connection, Envelope.error(request.namespace, "unknown message " + request));
        }
    }

    private static String requireString(JsonObject payload, String field) {
        if (!payload.has(field) || payload.get(field).isJsonNull()) {
            throw new IllegalArgumentException("missing \"" + field + "\"");
        }
        return payload.get(field).getAsString();
    }

    private Envelope opModeListEnvelope() {
        JsonObject payload = new JsonObject();
        payload.add("opModes", gson.toJsonTree(backend.listOpModes()));
        return new Envelope("opmode", "list", payload);
    }

    private Envelope envelope(String namespace, String type, Object payload) {
        return new Envelope(namespace, type, gson.toJsonTree(payload));
    }

    private void send(WebSocket connection, Envelope envelope) {
        if (connection.isOpen()) {
            connection.send(gson.toJson(envelope));
        }
    }

    private void broadcast(String namespace, String type, Object payload) {
        broadcast(envelope(namespace, type, payload));
    }

    private void broadcast(Envelope envelope) {
        broadcast(gson.toJson(envelope));
    }
}
