package org.ngicollective.ftcsim.dashboard;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;
import java.util.function.IntSupplier;

/**
 * The dashboard's one port: the browser UI and the simulation socket, served from one origin.
 *
 * <p>A request carrying {@code Upgrade: websocket} is passed byte for byte to the
 * {@link DashboardServer} on its loopback port, whatever its path, so a page connecting to
 * {@code /ws} and a script connecting to the bare port both reach it. Anything else is a file
 * from the built UI. The page therefore never names a port, and there is no second process whose
 * port could disagree with this one's: the bug that put a green indicator on a page connected to
 * whatever else was listening on 8765.</p>
 *
 * <p>Two servers rather than one because neither library on the classpath does both jobs.
 * Java-WebSocket, which the SDK ships, cannot answer a plain GET; the JDK's {@code HttpServer},
 * which can, cannot hand a connection over to a WebSocket. A third library would be a new
 * dependency for a localhost tool. What is here is a request head reader and a byte pump.</p>
 *
 * <p>{@link #route} and {@link #respond} are pure and carry all of the decisions; the rest is
 * sockets.</p>
 */
final class DashboardFront implements Closeable {

    /** Where the built UI sits on the classpath. */
    static final String UI_RESOURCE_ROOT = "dashboard-ui/";

    /** A request head bigger than this is not a browser asking for a file. */
    private static final int MAX_HEAD_BYTES = 16 * 1024;

    private final ServerSocket listener;
    private final IntSupplier socketPort;
    private final Function<String, byte[]> files;
    private final ExecutorService connections = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "dashboard-front");
        thread.setDaemon(true);
        return thread;
    });

    /**
     * @param address where the browser connects; a concrete address, for the reason
     *     {@link DashboardServer} gives
     * @param socketPort the simulation socket's loopback port, read per connection
     * @param files a UI file's bytes by path under the UI root, or null when there is no such file
     */
    DashboardFront(InetAddress address, int port, IntSupplier socketPort,
                   Function<String, byte[]> files) throws IOException {
        this.listener = new ServerSocket();
        this.listener.setReuseAddress(true);
        this.listener.bind(new InetSocketAddress(address, port));
        this.socketPort = socketPort;
        this.files = files;
    }

    /** The UI files packaged on the classpath, or null when this build has none. */
    static Function<String, byte[]> classpathUi(ClassLoader loader) {
        if (loader.getResource(UI_RESOURCE_ROOT + "index.html") == null) {
            return null;
        }
        return path -> {
            try (InputStream stream = loader.getResourceAsStream(UI_RESOURCE_ROOT + path)) {
                return stream == null ? null : readAll(stream);
            } catch (IOException e) {
                return null;
            }
        };
    }

    /** The port the browser connects to, which is the one asked for unless that was 0. */
    int port() {
        return listener.getLocalPort();
    }

    void start() {
        Thread acceptor = new Thread(this::acceptUntilClosed, "dashboard-front-accept");
        acceptor.setDaemon(true);
        acceptor.start();
    }

    @Override
    public void close() throws IOException {
        listener.close();
        connections.shutdownNow();
    }

    private void acceptUntilClosed() {
        while (!listener.isClosed()) {
            try {
                Socket client = listener.accept();
                connections.execute(() -> serve(client));
            } catch (SocketException closed) {
                return;
            } catch (IOException e) {
                System.err.println("[dashboard] front: accept failed: " + e);
            }
        }
    }

    private void serve(Socket client) {
        try {
            client.setTcpNoDelay(true);
            byte[] head = readHead(client.getInputStream());
            if (head == null) {
                client.close();
                return;
            }
            Route route = route(new String(head, StandardCharsets.ISO_8859_1));
            if (route.upgrade) {
                splice(client, head);
            } else {
                Response response = respond(route, files);
                OutputStream out = client.getOutputStream();
                out.write(response.bytes(!"HEAD".equals(route.method)));
                out.flush();
                client.close();
            }
        } catch (IOException e) {
            closeQuietly(client);
        }
    }

    /** Hands the connection to the simulation socket, head included, and pumps both ways. */
    private void splice(Socket client, byte[] head) throws IOException {
        Socket upstream = new Socket(InetAddress.getLoopbackAddress(), socketPort.getAsInt());
        upstream.setTcpNoDelay(true);
        upstream.getOutputStream().write(head);
        upstream.getOutputStream().flush();
        connections.execute(() -> pump(upstream, client));
        pump(client, upstream);
    }

    private static void pump(Socket from, Socket to) {
        byte[] buffer = new byte[8192];
        try {
            InputStream in = from.getInputStream();
            OutputStream out = to.getOutputStream();
            for (int read = in.read(buffer); read >= 0; read = in.read(buffer)) {
                out.write(buffer, 0, read);
                out.flush();
            }
        } catch (IOException ended) {
            // Either side going away ends the conversation; closing both below says so.
        } finally {
            closeQuietly(from);
            closeQuietly(to);
        }
    }

    /**
     * The request head, up to and including the blank line, or null when the client sent
     * something that is not one.
     *
     * <p>Read a byte at a time, so nothing after the head is consumed. A WebSocket client may
     * pipeline its first frame behind the handshake, and those bytes belong to the server the
     * connection is handed to.</p>
     */
    static byte[] readHead(InputStream in) throws IOException {
        ByteArrayOutputStream head = new ByteArrayOutputStream(512);
        int matched = 0;
        while (head.size() < MAX_HEAD_BYTES) {
            int next = in.read();
            if (next < 0) {
                return null;
            }
            head.write(next);
            matched = (next == (matched % 2 == 0 ? '\r' : '\n')) ? matched + 1
                    : (next == '\r' ? 1 : 0);
            if (matched == 4) {
                return head.toByteArray();
            }
        }
        return null;
    }

    /** What a request head asks for. */
    static Route route(String head) {
        String[] lines = head.split("\r\n");
        String[] request = lines[0].split(" ");
        String method = request.length > 0 ? request[0] : "";
        String target = request.length > 1 ? request[1] : "/";
        boolean upgrade = false;
        for (int index = 1; index < lines.length; index++) {
            int colon = lines[index].indexOf(':');
            if (colon < 0) {
                continue;
            }
            String name = lines[index].substring(0, colon).trim();
            String value = lines[index].substring(colon + 1).trim();
            if (name.equalsIgnoreCase("Upgrade") && value.equalsIgnoreCase("websocket")) {
                upgrade = true;
            }
        }
        int query = target.indexOf('?');
        String path = query < 0 ? target : target.substring(0, query);
        return new Route(method, path, upgrade);
    }

    /**
     * The answer to a request for a file.
     *
     * <p>A path with no extension is the page itself, so a reload of any address the page can
     * reach lands on it. A path with one is a file, and a missing file is a 404 rather than the
     * page, because a script tag handed HTML fails with an error about the HTML rather than about
     * the file that is not there.</p>
     */
    static Response respond(Route route, Function<String, byte[]> files) {
        if (!route.method.equals("GET") && !route.method.equals("HEAD")) {
            return Response.text(405, "Method Not Allowed", "the dashboard only serves GET\n");
        }
        if (files == null) {
            return Response.text(404, "Not Found", "this dashboard has no UI built into it;"
                    + " connect a WebSocket to this port, or start it with `mise run dashboard`\n");
        }
        String path = route.path.startsWith("/") ? route.path.substring(1) : route.path;
        if (path.contains("..") || path.contains("\\")) {
            return Response.text(404, "Not Found", "no such file\n");
        }
        String name = path.substring(path.lastIndexOf('/') + 1);
        if (path.isEmpty() || !name.contains(".")) {
            path = "index.html";
        }
        byte[] body = files.apply(path);
        if (body == null) {
            return Response.text(404, "Not Found", "no such file: /" + path + "\n");
        }
        return new Response(200, "OK", contentType(path), body);
    }

    static String contentType(String path) {
        String extension = path.substring(path.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
        switch (extension) {
            case "html":
                return "text/html; charset=utf-8";
            case "js":
            case "mjs":
                return "text/javascript; charset=utf-8";
            case "css":
                return "text/css; charset=utf-8";
            case "json":
            case "map":
                return "application/json";
            case "svg":
                return "image/svg+xml";
            case "png":
                return "image/png";
            case "jpg":
            case "jpeg":
                return "image/jpeg";
            case "ico":
                return "image/x-icon";
            case "woff2":
                return "font/woff2";
            case "glb":
                return "model/gltf-binary";
            case "wasm":
                return "application/wasm";
            default:
                return "application/octet-stream";
        }
    }

    private static byte[] readAll(InputStream stream) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        for (int read = stream.read(buffer); read >= 0; read = stream.read(buffer)) {
            bytes.write(buffer, 0, read);
        }
        return bytes.toByteArray();
    }

    private static void closeQuietly(Socket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
            // Already gone.
        }
    }

    /** A request's method, path without its query, and whether it asks for a WebSocket. */
    static final class Route {

        final String method;
        final String path;
        final boolean upgrade;

        Route(String method, String path, boolean upgrade) {
            this.method = method;
            this.path = path;
            this.upgrade = upgrade;
        }
    }

    /** A complete HTTP/1.1 response, closed after sending. */
    static final class Response {

        final int status;
        final String reason;
        final String contentType;
        final byte[] body;

        Response(int status, String reason, String contentType, byte[] body) {
            this.status = status;
            this.reason = reason;
            this.contentType = contentType;
            this.body = body;
        }

        static Response text(int status, String reason, String message) {
            return new Response(status, reason, "text/plain; charset=utf-8",
                    message.getBytes(StandardCharsets.UTF_8));
        }

        /**
         * The response on the wire. {@code no-cache} because the UI is rebuilt in place while
         * a session is running, and a browser holding last build's script is a page that lies.
         */
        byte[] bytes(boolean withBody) {
            String head = "HTTP/1.1 " + status + " " + reason + "\r\n"
                    + "Content-Type: " + contentType + "\r\n"
                    + "Content-Length: " + body.length + "\r\n"
                    + "Cache-Control: no-cache\r\n"
                    + "Connection: close\r\n\r\n";
            byte[] headBytes = head.getBytes(StandardCharsets.ISO_8859_1);
            if (!withBody) {
                return headBytes;
            }
            byte[] all = new byte[headBytes.length + body.length];
            System.arraycopy(headBytes, 0, all, 0, headBytes.length);
            System.arraycopy(body, 0, all, headBytes.length, body.length);
            return all;
        }
    }
}
