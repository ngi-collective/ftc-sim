package org.ngicollective.testframework.dashboard;

import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.ngicollective.testframework.hardware.FakeHardwareMap;
import org.ngicollective.testframework.hardware.SimulatedRobot;

import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
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

    @TempDir
    Path layoutDirectory;

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
        server = new DashboardServer(backend, new LayoutStore(layoutDirectory), "127.0.0.1", port);
        server.start();

        // Connect by name, the way the browser does: that resolution is what produced an accepted
        // socket the WebSocket library choked on.
        connectAndClose("ws://localhost:" + port);
        connectAndClose("ws://localhost:" + port);
        connectAndClose("ws://127.0.0.1:" + port);
    }

    /**
     * A layout saved from one browser has to be on disk for git, and readable by the next browser
     * that connects &mdash; that is the whole reason it does not live in browser storage.
     */
    @Test
    void savesLayoutsToDiskAndServesThemBackToAnotherClient() throws Exception {
        int port = freePort();
        backend = new LocalDashboardBackend(ROBOT, Collections.<OpModeEntry>emptyList());
        server = new DashboardServer(backend, new LayoutStore(layoutDirectory), "127.0.0.1", port);
        server.start();

        RecordingClient writer = connect("ws://127.0.0.1:" + port);
        writer.send("{\"namespace\":\"layout\",\"type\":\"save\",\"payload\":{\"name\":\"Verity\","
                + "\"layout\":{\"version\":1,\"devices\":{\"FL\":{\"ratio\":-1}}}}}");
        writer.awaitMessage("\"type\":\"saved\"");
        writer.closeBlocking();

        assertTrue(Files.isRegularFile(layoutDirectory.resolve("Verity.json")),
                "the layout was not written to disk");

        RecordingClient reader = connect("ws://127.0.0.1:" + port);
        reader.send("{\"namespace\":\"layout\",\"type\":\"list\",\"payload\":{}}");
        assertTrue(reader.awaitMessage("\"layouts\"").contains("Verity"));

        reader.send("{\"namespace\":\"layout\",\"type\":\"load\",\"payload\":{\"name\":\"Verity\"}}");
        assertTrue(reader.awaitMessage("\"type\":\"data\"").contains("\"ratio\":-1"));
        reader.closeBlocking();
    }

    @Test
    void reportsALayoutNameItRefusesInsteadOfDroppingTheRequest() throws Exception {
        int port = freePort();
        backend = new LocalDashboardBackend(ROBOT, Collections.<OpModeEntry>emptyList());
        server = new DashboardServer(backend, new LayoutStore(layoutDirectory), "127.0.0.1", port);
        server.start();

        RecordingClient client = connect("ws://127.0.0.1:" + port);
        client.send("{\"namespace\":\"layout\",\"type\":\"save\",\"payload\":"
                + "{\"name\":\"../escape\",\"layout\":{}}}");

        assertTrue(client.awaitMessage("\"type\":\"error\"").contains("layout name"));
        assertFalse(Files.exists(layoutDirectory.getParent().resolve("escape.json")));
        client.closeBlocking();
    }

    @Test
    void refusesToBindTheWildcardAddress() {
        backend = new LocalDashboardBackend(ROBOT, Collections.<OpModeEntry>emptyList());

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> new DashboardServer(
                        backend, new LayoutStore(layoutDirectory), "0.0.0.0", freePort()));
        assertTrue(thrown.getMessage().contains("127.0.0.1"), thrown.getMessage());
    }

    /** A client that keeps what the server said, so a test can wait for one specific reply. */
    private static final class RecordingClient extends WebSocketClient {

        private final BlockingQueue<String> messages = new LinkedBlockingQueue<>();

        RecordingClient(String url) {
            super(URI.create(url));
        }

        @Override public void onOpen(ServerHandshake handshake) { }

        @Override public void onMessage(String message) {
            messages.add(message);
        }

        @Override public void onClose(int code, String reason, boolean remote) { }

        @Override public void onError(Exception e) { }

        /** The first message containing {@code fragment}, ignoring the pushed status traffic. */
        String awaitMessage(String fragment) throws InterruptedException {
            long deadline = System.nanoTime() + 5_000_000_000L;
            while (System.nanoTime() < deadline) {
                String message = messages.poll(5, TimeUnit.SECONDS);
                if (message == null) {
                    break;
                }
                if (message.contains(fragment)) {
                    return message;
                }
            }
            throw new AssertionError("no message containing " + fragment);
        }
    }

    private static RecordingClient connect(String url) throws Exception {
        RecordingClient client = new RecordingClient(url);
        assertTrue(client.connectBlocking(5, TimeUnit.SECONDS), "could not connect to " + url);
        return client;
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
