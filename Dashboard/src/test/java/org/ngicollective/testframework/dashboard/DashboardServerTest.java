package org.ngicollective.testframework.dashboard;

import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.ngicollective.testframework.hardware.FakeHardwareMap;
import org.ngicollective.testframework.hardware.SimulatedRobot;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.util.Collections;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The socket half of the dashboard: who it lets in, and for how long it keeps letting them in. */
class DashboardServerTest {

    private static final SimulatedRobot ROBOT = new SimulatedRobot() {
        @Override
        public String name() {
            return "TestBot";
        }

        @Override
        public FakeHardwareMap create() {
            return FakeHardwareMap.builder().addMotor("drive").build();
        }
    };

    private LocalDashboardBackend backend;
    private DashboardServer server;

    @AfterEach
    void tearDown() throws Exception {
        if (server != null) {
            server.stop(500);
        }
        if (backend != null) {
            backend.close();
        }
    }

    /**
     * The browser reconnects after every reload, so one client going away must not take the
     * listening socket with it &mdash; which is exactly what a wildcard bind used to do here.
     */
    @Test
    void keepsAcceptingConnectionsAfterAClientDisconnects() throws Exception {
        int port = freePort();
        backend = new LocalDashboardBackend(ROBOT, Collections.<OpModeEntry>emptyList());
        server = new DashboardServer(backend, "127.0.0.1", port);
        server.start();

        // Connect by name, the way the browser does: that resolution is what produced an accepted
        // socket the WebSocket library choked on.
        connectAndClose("ws://localhost:" + port);
        connectAndClose("ws://localhost:" + port);
        connectAndClose("ws://127.0.0.1:" + port);
    }

    @Test
    void refusesToBindTheWildcardAddress() {
        backend = new LocalDashboardBackend(ROBOT, Collections.<OpModeEntry>emptyList());

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> new DashboardServer(backend, "0.0.0.0", freePort()));
        assertTrue(thrown.getMessage().contains("127.0.0.1"), thrown.getMessage());
    }

    private static void connectAndClose(String url) throws Exception {
        WebSocketClient client = new WebSocketClient(URI.create(url)) {
            @Override public void onOpen(ServerHandshake handshake) { }
            @Override public void onMessage(String message) { }
            @Override public void onClose(int code, String reason, boolean remote) { }
            @Override public void onError(Exception e) { }
        };
        assertTrue(client.connectBlocking(5, TimeUnit.SECONDS), "could not connect to " + url);
        client.closeBlocking();
    }

    private static int freePort() {
        try (ServerSocket probe = new ServerSocket(0)) {
            return probe.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException("no free port to test with", e);
        }
    }
}
