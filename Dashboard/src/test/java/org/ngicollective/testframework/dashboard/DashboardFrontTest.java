package org.ngicollective.testframework.dashboard;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

class DashboardFrontTest {

    private static final Map<String, byte[]> UI = new HashMap<>();

    static {
        UI.put("index.html", "<html>the page</html>".getBytes(StandardCharsets.UTF_8));
        UI.put("assets/index-abc123.js", "console.log(1)".getBytes(StandardCharsets.UTF_8));
    }

    private static final Function<String, byte[]> FILES = UI::get;

    private DashboardFront front;
    private ServerSocket upstream;

    @AfterEach
    void close() throws IOException {
        if (front != null) {
            front.close();
        }
        if (upstream != null) {
            upstream.close();
        }
    }

    @Test
    void anUpgradeIsTheSocketsWhateverItsPath() {
        // The page asks for /ws and a script for the bare port; both mean the simulation.
        assertTrue(DashboardFront.route(head("/ws", true)).upgrade);
        assertTrue(DashboardFront.route(head("/", true)).upgrade);
        assertFalse(DashboardFront.route(head("/ws", false)).upgrade);
    }

    @Test
    void theQueryIsNotPartOfThePath() {
        assertEquals("/", DashboardFront.route(head("/?probe", false)).path);
    }

    @Test
    void anAddressWithNoExtensionIsThePage() {
        assertBody("<html>the page</html>", get("/"));
        assertBody("<html>the page</html>", get("/scene"));
        assertEquals("text/html; charset=utf-8", get("/").contentType);
    }

    @Test
    void aFileIsServedWithItsType() {
        DashboardFront.Response script = get("/assets/index-abc123.js");

        assertEquals(200, script.status);
        assertEquals("text/javascript; charset=utf-8", script.contentType);
    }

    @Test
    void aMissingFileIsA404RatherThanThePage() {
        // A script tag handed HTML fails with a syntax error that points at the wrong thing.
        assertEquals(404, get("/assets/gone.js").status);
    }

    @Test
    void aPathOutOfTheUiIsRefused() {
        assertEquals(404, get("/../secrets.txt").status);
    }

    @Test
    void onlyGetAndHeadAreServed() {
        DashboardFront.Response posted = DashboardFront.respond(
                new DashboardFront.Route("POST", "/", false), FILES);

        assertEquals(405, posted.status);
    }

    @Test
    void aBuildWithNoUiSaysSoRatherThanServingNothing() {
        DashboardFront.Response none = DashboardFront.respond(
                new DashboardFront.Route("GET", "/", false), null);

        assertEquals(404, none.status);
        assertTrue(new String(none.body, StandardCharsets.UTF_8).contains("no UI built"));
    }

    @Test
    void theHeadIsReadUpToTheBlankLineAndNoFurther() throws IOException {
        // A WebSocket client may send its first frame right behind the handshake; those bytes are
        // the socket's, and the front must leave them in the stream.
        String request = head("/ws", true);
        InputStream in = new ByteArrayInputStream(
                (request + "FRAME").getBytes(StandardCharsets.ISO_8859_1));

        assertEquals(request, new String(DashboardFront.readHead(in), StandardCharsets.ISO_8859_1));
        assertEquals('F', in.read());
    }

    @Test
    void aConnectionThatEndsBeforeItsHeadIsNotARequest() throws IOException {
        InputStream in = new ByteArrayInputStream(
                "GET / HTTP/1.1\r\nHost: x\r\n".getBytes(StandardCharsets.ISO_8859_1));

        assertNull(DashboardFront.readHead(in));
    }

    @Test
    void aBrowserGetsThePageOverHttp() throws IOException {
        front = new DashboardFront(InetAddress.getLoopbackAddress(), 0, () -> 1, FILES);
        front.start();

        HttpURLConnection connection = (HttpURLConnection) new URL(
                "http://127.0.0.1:" + front.port() + "/?probe").openConnection();

        assertEquals(200, connection.getResponseCode());
        assertEquals("<html>the page</html>", new String(readAll(connection.getInputStream()),
                StandardCharsets.UTF_8));
    }

    @Test
    void anUpgradeIsSplicedToTheSocketHeadAndAll() throws Exception {
        // A stand-in for the simulation socket that echoes what arrives, so the test sees exactly
        // the bytes the front handed over: the head it read, then everything after it.
        upstream = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
        Thread echo = new Thread(() -> {
            try (Socket accepted = upstream.accept()) {
                InputStream in = accepted.getInputStream();
                OutputStream out = accepted.getOutputStream();
                byte[] buffer = new byte[1024];
                for (int read = in.read(buffer); read >= 0; read = in.read(buffer)) {
                    out.write(buffer, 0, read);
                    out.flush();
                }
            } catch (IOException ignored) {
                // The test closed it.
            }
        });
        echo.setDaemon(true);
        echo.start();
        front = new DashboardFront(InetAddress.getLoopbackAddress(), 0,
                upstream::getLocalPort, FILES);
        front.start();

        byte[] sent = (head("/ws", true) + "hello").getBytes(StandardCharsets.ISO_8859_1);
        try (Socket client = new Socket(InetAddress.getLoopbackAddress(), front.port())) {
            client.setSoTimeout(5000);
            client.getOutputStream().write(sent);
            client.getOutputStream().flush();
            byte[] received = new byte[sent.length];
            int offset = 0;
            while (offset < received.length) {
                int read = client.getInputStream().read(received, offset, received.length - offset);
                if (read < 0) {
                    break;
                }
                offset += read;
            }
            assertArrayEquals(sent, received);
        }
    }

    private static DashboardFront.Response get(String path) {
        return DashboardFront.respond(DashboardFront.route(head(path, false)), FILES);
    }

    private static void assertBody(String expected, DashboardFront.Response response) {
        assertEquals(200, response.status);
        assertEquals(expected, new String(response.body, StandardCharsets.UTF_8));
    }

    private static String head(String path, boolean upgrade) {
        return "GET " + path + " HTTP/1.1\r\n"
                + "Host: 127.0.0.1\r\n"
                + (upgrade ? "Upgrade: websocket\r\nConnection: Upgrade\r\n" : "")
                + "\r\n";
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        for (int read = in.read(buffer); read >= 0; read = in.read(buffer)) {
            bytes.write(buffer, 0, read);
        }
        return bytes.toByteArray();
    }
}
