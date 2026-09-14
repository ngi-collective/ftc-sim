package org.ngicollective.camerastream;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.nio.charset.Charset;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Serves frames as MJPEG over HTTP: {@code multipart/x-mixed-replace}, one JPEG per part, until the
 * client goes away.
 *
 * <p>The oldest streaming format on the web, chosen for exactly that reason. A browser renders it
 * with {@code <img src="http://host:port/camera">} and decodes every frame itself, so the dashboard
 * needs no video element, no codec, no signalling and no per-frame JavaScript. It is also what a
 * Limelight serves, which means anyone who has debugged vision before already knows what this URL
 * is. The JSON socket stays a JSON socket, and a browser reload reconnects the two independently.
 *
 * <p>Frames are shared, not per-client: the source is asked for a picture at most once per frame
 * interval no matter how many tabs are open, because rendering is the expensive half and two tabs
 * showing the same simulation want the same picture anyway.</p>
 */
public final class MjpegServer implements Closeable {

    /** Arbitrary, but it must not appear in JPEG data; a JPEG cannot contain this text. */
    private static final String BOUNDARY = "ngicollective-frame";

    private static final Charset ASCII = Charset.forName("US-ASCII");

    /**
     * How long a client with nothing to show waits before asking again. Long enough not to spin,
     * short enough that a camera appearing mid-stream shows up without a reload.
     */
    private static final long IDLE_POLL_MILLIS = 200;

    /** How early a client may ask for the next frame and still get a fresh one. */
    private static final long CACHE_SLACK_MILLIS = 4;

    private final HttpServer http;
    private final JpegFrameSource frames;
    private final String path;
    private final long frameIntervalMillis;

    private final Object frameLock = new Object();
    private byte[] latest;
    private long latestAtMillis;

    /**
     * @param host             interface to bind, as the WebSocket server does: a concrete address
     *                         rather than the wildcard
     * @param port             TCP port; 0 asks the OS for a free one, which is what tests want
     * @param path             request path, such as {@code /camera}
     * @param framesPerSecond  how often the source is asked for a new picture
     */
    public MjpegServer(String host, int port, String path, double framesPerSecond,
            JpegFrameSource frames) throws IOException {
        if (framesPerSecond <= 0) {
            throw new IllegalArgumentException(
                    "a camera stream needs a positive frame rate, not " + framesPerSecond);
        }
        this.frames = frames;
        this.path = path;
        this.frameIntervalMillis = Math.max(1, Math.round(1000.0 / framesPerSecond));
        this.http = HttpServer.create(new InetSocketAddress(resolve(host), port), 0);
        // One thread per watching client, held for the life of its stream, so a cached pool rather
        // than a fixed one: a second tab must not wait for the first to close.
        final AtomicInteger counter = new AtomicInteger();
        http.setExecutor(Executors.newCachedThreadPool(new ThreadFactory() {
            @Override
            public Thread newThread(Runnable runnable) {
                Thread thread = new Thread(runnable, "camera-stream-" + counter.incrementAndGet());
                thread.setDaemon(true);
                return thread;
            }
        }));
        http.createContext(path, this::serve);
    }

    private static InetAddress resolve(String host) {
        try {
            return InetAddress.getByName(host);
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException("cannot resolve camera stream host \"" + host + "\"",
                    e);
        }
    }

    public void start() {
        http.start();
    }

    /** The port actually bound, which is the interesting one when 0 was asked for. */
    public int port() {
        return http.getAddress().getPort();
    }

    public String path() {
        return path;
    }

    /** The URL a browser should point an image at. */
    public String url() {
        return "http://" + http.getAddress().getHostString() + ":" + port() + path;
    }

    @Override
    public void close() {
        http.stop(0);
    }

    private void serve(HttpExchange exchange) throws IOException {
        try {
            if (!"GET".equals(exchange.getRequestMethod())) {
                respond(exchange, 405, "the camera stream is read-only");
                return;
            }
            byte[] first = currentFrame();
            if (first == null) {
                // Honest 503 rather than an empty stream: a browser shows a broken image either
                // way, and this way the reason is readable in the network tab.
                respond(exchange, 503,
                        "no camera to stream: this simulation has no webcam configured");
                return;
            }
            stream(exchange, first);
        } finally {
            exchange.close();
        }
    }

    private void stream(HttpExchange exchange, byte[] first) throws IOException {
        exchange.getResponseHeaders().set("Content-Type",
                "multipart/x-mixed-replace; boundary=" + BOUNDARY);
        // A live view must never be served from a cache, and must never be buffered into one.
        exchange.getResponseHeaders().set("Cache-Control", "no-store, no-cache, must-revalidate");
        exchange.getResponseHeaders().set("Pragma", "no-cache");
        // Length 0 means "chunked, until I stop": the stream has no length.
        exchange.sendResponseHeaders(200, 0);

        OutputStream out = exchange.getResponseBody();
        byte[] frame = first;
        // Paced against a deadline rather than by sleeping the interval, so the rate the browser
        // is told it is getting is the rate it gets: rendering and encoding a frame is not free,
        // and sleeping a full interval on top of that work put the stream a fifth under the
        // advertised figure.
        long due = System.currentTimeMillis();
        try {
            while (true) {
                if (frame != null) {
                    writePart(out, frame);
                    due += frameIntervalMillis;
                } else {
                    due = System.currentTimeMillis() + IDLE_POLL_MILLIS;
                }
                long wait = due - System.currentTimeMillis();
                if (wait > 0) {
                    Thread.sleep(wait);
                } else {
                    // Behind: the renderer cannot keep up. Give up the lost time rather than
                    // accumulating a debt that would then be worked off as a burst.
                    due = System.currentTimeMillis();
                }
                frame = currentFrame();
            }
        } catch (IOException e) {
            // The client closed the tab, navigated away, or reloaded. Not a fault.
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void writePart(OutputStream out, byte[] jpeg) throws IOException {
        out.write(("--" + BOUNDARY + "\r\n"
                + "Content-Type: image/jpeg\r\n"
                + "Content-Length: " + jpeg.length + "\r\n\r\n").getBytes(ASCII));
        out.write(jpeg);
        out.write("\r\n".getBytes(ASCII));
        // Without this the server holds frames back to fill a buffer, which on a 40 kB frame means
        // the view runs several frames behind for no reason.
        out.flush();
    }

    /**
     * The current frame, rendered at most once per frame interval however many clients ask.
     *
     * <p>Clients are not synchronised with each other, so a second tab joining mid-interval shares
     * the frame already encoded instead of forcing a render of its own.</p>
     *
     * <p>The slack matters: a client that wakes a millisecond early would otherwise be handed the
     * frame it has already sent and have to wait a whole further interval, halving the rate for
     * the sake of sub-millisecond timer jitter.</p>
     */
    private byte[] currentFrame() {
        synchronized (frameLock) {
            long now = System.currentTimeMillis();
            if (latest == null || now - latestAtMillis >= frameIntervalMillis - CACHE_SLACK_MILLIS) {
                byte[] next = frames.nextFrame();
                if (next != null) {
                    latest = next;
                    latestAtMillis = now;
                }
            }
            return latest;
        }
    }

    private static void respond(HttpExchange exchange, int status, String message)
            throws IOException {
        byte[] body = message.getBytes(ASCII);
        exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=us-ascii");
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
    }
}
