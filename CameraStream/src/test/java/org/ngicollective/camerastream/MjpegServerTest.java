package org.ngicollective.camerastream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.nio.charset.Charset;
import java.util.concurrent.atomic.AtomicInteger;

import javax.imageio.ImageIO;

/**
 * The camera stream's contract with the browser: an {@code <img>} pointed at this endpoint has to
 * decode what comes back, with no JavaScript involved, so the part headers and the JPEG payload
 * both have to be right.
 */
class MjpegServerTest {

    private static final Charset ASCII = Charset.forName("US-ASCII");

    @Test
    void streamsJpegPartsAnImageElementCanDecode() throws Exception {
        JpegEncoder encoder = new JpegEncoder();
        byte[] red = encoder.encode(solid(32, 24, 255, 0, 0), 32, 24);

        MjpegServer server = new MjpegServer("127.0.0.1", 0, "/camera", 30, () -> red);
        server.start();
        try {
            HttpURLConnection connection = (HttpURLConnection) java.net.URI.create(server.url()).toURL().openConnection();
            connection.setConnectTimeout(2000);
            connection.setReadTimeout(2000);
            assertEquals(200, connection.getResponseCode());
            assertTrue(connection.getHeaderField("Content-Type")
                            .startsWith("multipart/x-mixed-replace; boundary="),
                    "content type was " + connection.getHeaderField("Content-Type"));

            try (InputStream body = connection.getInputStream()) {
                String boundary = readLine(body);
                assertTrue(boundary.startsWith("--"), "first line was \"" + boundary + "\"");
                assertEquals("Content-Type: image/jpeg", readLine(body));
                String length = readLine(body);
                assertEquals("", readLine(body));

                int declared =
                        Integer.parseInt(length.substring(length.indexOf(':') + 1).trim());
                byte[] part = new byte[declared];
                int read = 0;
                while (read < declared) {
                    int count = body.read(part, read, declared - read);
                    assertTrue(count > 0, "stream ended after " + read + " of " + declared);
                    read += count;
                }

                BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(part));
                assertNotNull(decoded, "the part was not a decodable image");
                assertEquals(32, decoded.getWidth());
                assertEquals(24, decoded.getHeight());
            }
            connection.disconnect();
        } finally {
            server.close();
        }
    }

    /**
     * Red must come back red. RGBA in, packed RGB out, and getting those two the wrong way round is
     * a mistake nothing else in the stack would catch: the frame would still stream, still decode,
     * and still be the right size, with the colours swapped.
     */
    @Test
    void keepsChannelsInOrderThroughTheEncoder() throws IOException {
        byte[] jpeg = new JpegEncoder().encode(solid(16, 16, 200, 40, 10), 16, 16);

        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(jpeg));
        int pixel = decoded.getRGB(8, 8);
        int red = pixel >> 16 & 0xFF;
        int green = pixel >> 8 & 0xFF;
        int blue = pixel & 0xFF;
        assertTrue(Math.abs(red - 200) < 12, "red was " + red);
        assertTrue(Math.abs(green - 40) < 12, "green was " + green);
        assertTrue(Math.abs(blue - 10) < 12, "blue was " + blue);
    }

    /**
     * A simulation with no camera says so. The alternative - a 200 with an empty body - looks
     * identical to a broken camera in the browser and leaves the reason nowhere to be read.
     */
    @Test
    void refusesToStreamWhenThereIsNoCamera() throws Exception {
        MjpegServer server = new MjpegServer("127.0.0.1", 0, "/camera", 15, () -> null);
        server.start();
        try {
            HttpURLConnection connection = (HttpURLConnection) java.net.URI.create(server.url()).toURL().openConnection();
            connection.setConnectTimeout(2000);
            connection.setReadTimeout(2000);
            assertEquals(503, connection.getResponseCode());
            connection.disconnect();
        } finally {
            server.close();
        }
    }

    /**
     * Rendering is the expensive half of a frame, so two watchers must not double it. This is what
     * makes opening the dashboard in a second tab free.
     */
    @Test
    void sharesOneRenderBetweenClients() throws Exception {
        AtomicInteger renders = new AtomicInteger();
        JpegEncoder encoder = new JpegEncoder();
        byte[] frame = encoder.encode(solid(16, 16, 10, 20, 30), 16, 16);

        // One frame per second: both clients connect well inside the first interval, so a shared
        // frame means exactly one render between them.
        MjpegServer server = new MjpegServer("127.0.0.1", 0, "/camera", 1, () -> {
            renders.incrementAndGet();
            return frame;
        });
        server.start();
        try {
            HttpURLConnection first = open(server.url());
            HttpURLConnection second = open(server.url());
            assertEquals(200, first.getResponseCode());
            assertEquals(200, second.getResponseCode());
            assertEquals(1, renders.get());
            first.disconnect();
            second.disconnect();
        } finally {
            server.close();
        }
    }

    private static HttpURLConnection open(String url) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) java.net.URI.create(url).toURL().openConnection();
        connection.setConnectTimeout(2000);
        connection.setReadTimeout(2000);
        return connection;
    }

    private static byte[] solid(int width, int height, int red, int green, int blue) {
        byte[] rgba = new byte[width * height * 4];
        for (int i = 0; i < rgba.length; i += 4) {
            rgba[i] = (byte) red;
            rgba[i + 1] = (byte) green;
            rgba[i + 2] = (byte) blue;
            rgba[i + 3] = (byte) 255;
        }
        return rgba;
    }

    /** One CRLF-terminated header line, read a byte at a time because the body is binary. */
    private static String readLine(InputStream stream) throws IOException {
        StringBuilder line = new StringBuilder();
        int value;
        while ((value = stream.read()) != -1) {
            if (value == '\r') {
                stream.read();
                return line.toString();
            }
            line.append((char) value);
        }
        return line.toString();
    }
}
