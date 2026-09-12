package org.ngicollective.testframework.dashboard;

import org.ngicollective.testframework.hardware.SimulatedRobot;

import java.util.List;

/**
 * Starts a dashboard session from the command line: discover the team's OpModes and simulated robot
 * on the classpath, then serve them.
 *
 * <pre>
 * mise run dashboard                 # default package and port
 * mise run dashboard -- --port 9000
 * </pre>
 */
public final class DashboardMain {

    private static final String DEFAULT_PACKAGE = "org.firstinspires.ftc.teamcode";
    private static final int DEFAULT_PORT = 8765;

    private DashboardMain() {
    }

    public static void main(String[] args) throws Exception {
        String packagePrefix = argument(args, "--package", DEFAULT_PACKAGE);
        int port = Integer.parseInt(argument(args, "--port", String.valueOf(DEFAULT_PORT)));
        String robotName = argument(args, "--robot", null);

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
        System.out.println("[dashboard] OpModes: " + opModes.size());
        for (OpModeEntry entry : opModes) {
            System.out.println("[dashboard]   " + entry.info.name + "  (" + entry.info.flavor + ")");
        }

        LocalDashboardBackend backend = new LocalDashboardBackend(robot, opModes);
        DashboardServer server = new DashboardServer(backend, port);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            backend.close();
            try {
                server.stop(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }));
        server.run();
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
