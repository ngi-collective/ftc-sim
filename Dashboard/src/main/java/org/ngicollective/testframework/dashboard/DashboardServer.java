package org.ngicollective.testframework.dashboard;

import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;
import org.ngicollective.testframework.dashboard.protocol.CameraStreamInfo;
import org.ngicollective.testframework.dashboard.protocol.Envelope;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;

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
 * sim/scenarios  {}                                 -&gt; sim/scenarios {scenarios, directory, active}
 * sim/scenario   {name}                             -&gt; sim/scenarios, broadcast; null name = no scenario
 * sim/camera     {forwardMetres, ... rollDegrees}   -&gt; sim/camera, broadcast; aims the live view
 * sim/camera-save{}                                 -&gt; sim/camera-saved {path}, and sim/camera
 * sim/camera-revert {}                              -&gt; sim/camera, broadcast
 * </pre>
 * <p>Pushed without being asked: {@code opmode/status}, {@code telemetry/frame},
 * {@code device/state}, {@code sim/pose} every control cycle, {@code sim/config} and
 * {@code sim/scene} on connect and on every OpMode init, {@code sim/score} and
 * {@code sim/scenarios} on connect and whenever either changes, {@code camera/stream} and
 * {@code sim/camera} on connect, and {@code sim/camera} again whenever the mount changes. A
 * request that fails comes back as {@code &lt;namespace&gt;/error}.</p>
 *
 * <p>What each of those messages <i>means</i> is {@link DashboardProtocol}'s; this class is the
 * socket around it, and holds nothing but the mapping from a connection to the two ways of
 * answering one.</p>
 */
public class DashboardServer extends WebSocketServer {

    private final DashboardProtocol protocol;

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
     *                     a session that serves no stream. Held rather than derived because the
     *                     stream is a separate server on a separate port: the WebSocket has no way
     *                     to know where it ended up, and a browser that guessed would break the
     *                     moment either port moved.
     */
    public DashboardServer(DashboardBackend backend, LayoutStore layouts, String host, int port,
            CameraStreamInfo cameraStream) {
        super(new InetSocketAddress(resolve(host), port));
        this.protocol = new DashboardProtocol(backend, layouts, cameraStream);
        setReuseAddr(true);

        protocol.subscribe(this::broadcast);
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
        for (Envelope greeting : protocol.greeting()) {
            send(connection, greeting);
        }
    }

    @Override
    public void onClose(WebSocket connection, int code, String reason, boolean remote) {
        // Sessions outlive browsers on purpose: a page reload must not stop a running OpMode.
    }

    @Override
    public void onMessage(WebSocket connection, String message) {
        protocol.receive(message, repliesTo(connection));
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

    private DashboardProtocol.Replies repliesTo(WebSocket connection) {
        return new DashboardProtocol.Replies() {

            @Override
            public void reply(Envelope envelope) {
                send(connection, envelope);
            }

            @Override
            public void broadcast(Envelope envelope) {
                DashboardServer.this.broadcast(envelope);
            }
        };
    }

    private void send(WebSocket connection, Envelope envelope) {
        if (connection.isOpen()) {
            connection.send(protocol.encode(envelope));
        }
    }

    private void broadcast(Envelope envelope) {
        broadcast(protocol.encode(envelope));
    }
}
