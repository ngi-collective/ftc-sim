package org.ngicollective.testframework.dashboard;

import org.ngicollective.camerastream.MjpegServer;
import org.ngicollective.testframework.dashboard.protocol.CameraStreamInfo;
import org.ngicollective.testframework.hardware.SimulatedRobot;

import java.nio.file.Paths;
import java.util.List;

/**
 * Starts a dashboard session from the command line: discover the team's OpModes and simulated robot
 * on the classpath, then serve them.
 *
 * <pre>
 * mise run dashboard                          # default host, package, ports and layout directory
 * mise run dashboard --args "--port 9000"
 * mise run dashboard --args "--camera-port 9100"
 * mise run dashboard --args "--host 192.168.1.50"   # reachable from another machine
 * mise run dashboard --args "--layouts ../shared-layouts"
 * mise run dashboard --args "--scenario practice-balls"  # an arrangement from TeamCode/scenarios
 * </pre>
 */
public final class DashboardMain {

    private static final String DEFAULT_PACKAGE = "org.firstinspires.ftc.teamcode";
    private static final int DEFAULT_PORT = 8765;

    /**
     * The camera stream's port, next to the socket's.
     *
     * <p>Its own port because it is its own protocol: plain HTTP that a browser's {@code <img>}
     * consumes directly, rather than frames smuggled through the JSON socket.</p>
     */
    private static final int DEFAULT_CAMERA_PORT = 8766;

    /** The path the stream answers on, which reads like what it is in a browser's address bar. */
    private static final String CAMERA_PATH = "/camera";

    /**
     * Camera frame rate.
     *
     * <p>Half the simulated camera's 30 fps: at 15 the view reads as live, and a frame costs a full
     * render plus a JPEG encode. This makes the panel a sampled view of what the camera sees, not a
     * record of every frame a detector was handed.</p>
     */
    private static final double CAMERA_FRAMES_PER_SECOND = 15;

    /**
     * Relative to the module the task runs in, which is TeamCode: saved layouts land beside the
     * OpModes and the robot configuration they describe, and get committed with them.
     */
    private static final String DEFAULT_LAYOUT_DIRECTORY = "robot-layouts";

    // Loopback, and a concrete address rather than the wildcard: see DashboardServer's constructor
    // for what a dual-stack bind does to this WebSocket library on macOS.
    private static final String DEFAULT_HOST = "127.0.0.1";

    private DashboardMain() {
    }

    /**
     * Starts a session and blocks, which is what a dashboard started from a shell wants.
     *
     * <p>{@link #start} is the same session without the blocking, for a caller that has to own
     * the thread it runs on &mdash; see {@code DashboardHost}, which has an Android main looper
     * to pump.</p>
     */
    public static void main(String[] args) throws Exception {
        start(args);
        Thread.currentThread().join();
    }

    /** Everything a session is, started and serving, with nothing blocked. */
    public static void start(String[] args) throws Exception {
        String packagePrefix = argument(args, "--package", DEFAULT_PACKAGE);
        String host = argument(args, "--host", DEFAULT_HOST);
        int port = Integer.parseInt(argument(args, "--port", String.valueOf(DEFAULT_PORT)));
        LayoutStore layouts =
                new LayoutStore(Paths.get(argument(args, "--layouts", DEFAULT_LAYOUT_DIRECTORY)));
        String robotName = argument(args, "--robot", null);
        String scenarioName = argument(args, "--scenario", null);

        OpModeDiscovery discovery = new OpModeDiscovery(packagePrefix);
        List<OpModeEntry> opModes = discovery.discoverOpModes();
        List<SimulatedRobot> robots = discovery.discoverRobots();

        if (robots.isEmpty()) {
            throw new IllegalStateException(
                    "No SimulatedRobot implementation found under " + packagePrefix + ". The "
                            + "dashboard needs one to know what hardware the robot has; see "
                            + "org.ngicollective.testframework.hardware.SimulatedRobot.");
        }
        SimulatedRobot robot = select(robots, robotName);

        System.out.println("[dashboard] robot: " + robot.name()
                + " (" + robots.size() + " configuration(s) found)");
        System.out.println("[dashboard] layouts: " + layouts.directory());
        System.out.println("[dashboard] OpModes: " + opModes.size());
        for (OpModeEntry entry : opModes) {
            System.out.println("[dashboard]   " + entry.info.name + "  (" + entry.info.flavor + ")");
        }

        LocalDashboardBackend backend = new LocalDashboardBackend(robot, opModes);

        // Applied to the session rather than to the robot, so it survives an init: see
        // LocalDashboardBackend.loadScene. By name rather than by a scene this method loads
        // itself, so that the one place which remembers what is in force is the one the browser's
        // picker reads back. A misspelled name throws here, naming both places it looked, rather
        // than starting a dashboard that quietly shows the official field.
        if (scenarioName != null) {
            backend.loadScenario(scenarioName);
            System.out.println("[dashboard] scenario: " + scenarioName + "  ("
                    + backend.scene().elements.size() + " game element(s))");
        }

        // Started before the socket, because the socket advertises where it landed. A robot with no
        // camera gets no stream and no advertisement, and the panel says so.
        MjpegServer camera = null;
        CameraStreamInfo cameraStream = null;
        if (backend.cameraFrames() != null) {
            int cameraPort = Integer.parseInt(
                    argument(args, "--camera-port", String.valueOf(DEFAULT_CAMERA_PORT)));
            camera = new MjpegServer(host, cameraPort, CAMERA_PATH, CAMERA_FRAMES_PER_SECOND,
                    new CameraViewFrames(backend));
            camera.start();
            cameraStream = new CameraStreamInfo(camera.url(), CameraViewFrames.width(),
                    CameraViewFrames.height(), CAMERA_FRAMES_PER_SECOND);
            System.out.println("[dashboard] camera: " + camera.url() + "  ("
                    + CameraViewFrames.width() + "x" + CameraViewFrames.height() + " at "
                    + (int) CAMERA_FRAMES_PER_SECOND + " fps)");
        } else {
            System.out.println("[dashboard] camera: none (" + robot.name()
                    + " declares no webcam)");
        }

        DashboardServer server = new DashboardServer(backend, layouts, host, port, cameraStream);
        final MjpegServer cameraToClose = camera;
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            backend.close();
            if (cameraToClose != null) {
                cameraToClose.close();
            }
            try {
                server.stop(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }));
        server.start();
    }

    private static SimulatedRobot select(List<SimulatedRobot> robots, String name) {
        if (name == null) {
            return robots.get(0);
        }
        for (SimulatedRobot robot : robots) {
            if (robot.name().equals(name)) {
                return robot;
            }
        }
        throw new IllegalArgumentException("no simulated robot named \"" + name + "\"");
    }

    private static String argument(String[] args, String flag, String fallback) {
        for (int i = 0; i < args.length - 1; i++) {
            if (args[i].equals(flag)) {
                return args[i + 1];
            }
        }
        return fallback;
    }
}
