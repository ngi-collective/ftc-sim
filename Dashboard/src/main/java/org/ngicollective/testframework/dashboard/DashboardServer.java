package org.ngicollective.testframework.dashboard;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;

import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;
import org.ngicollective.testframework.dashboard.protocol.Alliance;
import org.ngicollective.testframework.dashboard.protocol.BehaviorSpec;
import org.ngicollective.testframework.dashboard.protocol.CameraStreamInfo;
import org.ngicollective.testframework.dashboard.protocol.Envelope;
import org.ngicollective.testframework.dashboard.protocol.GamepadState;
import org.ngicollective.testframework.dashboard.protocol.SimConfigPayload;
import org.ngicollective.testframework.dashboard.protocol.SimStatus;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.nio.file.Path;

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
 * layout/list    {}                                 -&gt; layout/list   {layouts:[...], directory}
 * layout/save    {name, layout}                     -&gt; layout/list, broadcast to every client
 * layout/load    {name}                             -&gt; layout/data   {name, layout}
 * layout/delete  {name}                             -&gt; layout/list, broadcast to every client
 * sim/time       {multiplier, paused}               -&gt; sim/status, broadcast on change
 * sim/step       {ticks}                            (advances a paused simulation)
 * sim/pose       {x, y, headingDegrees}             (next sim/pose reflects it)
 * sim/alliance   {alliance}                         -&gt; sim/status, broadcast on change
 * </pre>
 * <p>Pushed without being asked: {@code opmode/status}, {@code telemetry/frame},
 * {@code device/state}, {@code sim/pose} every control cycle, {@code sim/config} on connect and on
 * every OpMode init, and {@code camera/stream} on connect. A request that fails comes back as
 * {@code &lt;namespace&gt;/error}.</p>
 */
public class DashboardServer extends WebSocketServer {

    // serializeNulls: the protocol declares nullable fields (a stopped session has no opMode, a
    // clean run has no failure). Dropping them would make "absent" and "null" indistinguishable to
    // the browser.
    private final Gson gson = new GsonBuilder().serializeNulls().create();
    private final DashboardBackend backend;
    private final LayoutStore layouts;

    /**
     * Where the camera view is served, or null when this session serves no stream.
     *
     * <p>Held rather than derived because the stream is a separate server on a separate port: the
     * WebSocket has no way to know where it ended up, and a browser that guessed would break the
     * moment either port moved.</p>
     */
    private final CameraStreamInfo cameraStream;

    /**
     * @param host address to bind. It must be a concrete address, not the unspecified wildcard:
     *             the JDK gives a wildcard bind a dual-stack IPv6 socket, and an IPv4 client
     *             arriving on it (which is what {@code ws://localhost:...} resolves to) produces an
     *             accepted socket whose {@code setTcpNoDelay} fails with {@code SocketException:
     *             Invalid argument} on macOS. Java-WebSocket does that call unguarded while
     *             handling the accept, and its error path cancels the key and closes the channel it
     *             came from &mdash; the listening socket. The server then stays alive, serving the
     *             one connection it already had, while every later connection, including the
     *             browser's reconnect after a reload, is refused. Binding a concrete address keeps
     *             accepted sockets in one family and never reaches that call.
     */
    public DashboardServer(DashboardBackend backend, LayoutStore layouts, String host, int port) {
        this(backend, layouts, host, port, null);
    }

    /**
     * The same, serving a browser that can also watch the camera.
     *
     * @param cameraStream where the Dashboard Camera View's MJPEG stream is listening, or null for
     *                     a session that serves no stream
     */
    public DashboardServer(DashboardBackend backend, LayoutStore layouts, String host, int port,
            CameraStreamInfo cameraStream) {
        super(new InetSocketAddress(resolve(host), port));
        this.backend = backend;
        this.layouts = layouts;
        this.cameraStream = cameraStream;
        setReuseAddr(true);

        backend.subscribeStatus(status -> broadcast("opmode", "status", status));
        backend.subscribeTelemetry(frame -> broadcast("telemetry", "frame", frame));
        backend.subscribeDeviceState(devices -> {
            JsonObject payload = new JsonObject();
            payload.add("devices", gson.toJsonTree(devices));
            broadcast(new Envelope("device", "state", payload));
        });
        backend.subscribeSimPose(pose -> broadcast("sim", "pose", pose));
        backend.subscribeSimConfig(config -> broadcast("sim", "config", config));
        backend.subscribeSimStatus(status -> broadcast("sim", "status", status));
    }

    private static InetAddress resolve(String host) {
        InetAddress address;
        try {
            address = InetAddress.getByName(host);
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException("cannot resolve dashboard host \"" + host + "\"", e);
        }
        if (address.isAnyLocalAddress()) {
            throw new IllegalArgumentException(
                    "the dashboard cannot bind the wildcard address \"" + host + "\"; name the "
                            + "interface to serve, such as 127.0.0.1 for this machine only");
        }
        return address;
    }

    @Override
    public void onOpen(WebSocket connection, ClientHandshake handshake) {
        // A fresh browser needs the whole picture before it can render anything.
        send(connection, opModeListEnvelope());
        send(connection, envelope("opmode", "status", backend.status()));
        send(connection, envelope("sim", "status", backend.simStatus()));
        // Geometry exists as soon as the session has a robot that can drive, which is before any
        // OpMode runs; only a robot with no drivetrain has none.
        SimConfigPayload simConfig = backend.simConfig();
        if (simConfig != null) {
            send(connection, envelope("sim", "config", simConfig));
        }
        if (cameraStream != null) {
            send(connection, envelope("camera", "stream", cameraStream));
        }
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
        if (connection != null) {
            System.err.println("[dashboard] websocket error: " + e);
            return;
        }
        // A server-level error (a port already in use, most often) has already shut the listener
        // down inside the library. Exiting says so, instead of leaving a process that looks alive
        // and answers nothing.
        System.err.println("[dashboard] fatal: " + e);
        System.exit(1);
    }

    @Override
    public void onStart() {
        System.out.println("[dashboard] listening on ws://" + getAddress().getHostString()
                + ":" + getPort());
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
            case "layout/list":
                send(connection, layoutListEnvelope());
                break;
            case "layout/save": {
                String name = requireString(payload, "name");
                Path file = layouts.save(name, payload.get("layout"));
                JsonObject saved = new JsonObject();
                saved.addProperty("name", name);
                saved.addProperty("path", file.toString());
                send(connection, new Envelope("layout", "saved", saved));
                // Every client sees the new file: two people editing the same robot is the point.
                broadcast(layoutListEnvelope());
                break;
            }
            case "layout/load": {
                String name = requireString(payload, "name");
                JsonObject loaded = new JsonObject();
                loaded.addProperty("name", name);
                loaded.add("layout", layouts.read(name));
                send(connection, new Envelope("layout", "data", loaded));
                break;
            }
            case "layout/delete":
                layouts.delete(requireString(payload, "name"));
                broadcast(layoutListEnvelope());
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
            default:
                send(connection, Envelope.error(request.namespace, "unknown message " + request));
        }
    }

    private Envelope layoutListEnvelope() {
        JsonObject payload = new JsonObject();
        payload.add("layouts", gson.toJsonTree(layouts.list()));
        payload.addProperty("directory", layouts.directory().toString());
        return new Envelope("layout", "list", payload);
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
