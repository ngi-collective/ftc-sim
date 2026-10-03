package org.ngicollective.testframework.app;

import org.ngicollective.testframework.hardware.SimulatedRobot;

import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;

/**
 * Finds the team's simulated robot from inside an APK.
 *
 * <p>Through {@link ServiceLoader}: the app lists its robot in
 * {@code src/simulated/resources/META-INF/services/org.ngicollective.testframework.hardware.SimulatedRobot},
 * one class name per line. An APK has no classpath to walk, which is how the plain-JVM dashboard
 * finds robots instead, and a registration file is the one mechanism both runtimes read.</p>
 *
 * <p>Exactly one, on a device. A Control Hub runs one robot, and choosing between several would
 * need a setting this app has no screen for, so two is reported as the mistake it almost always
 * is: a copied robot class that kept its registration.</p>
 */
public final class SimulatedRobots {

    /** Where the registration lives, relative to the module's {@code src/simulated}. */
    static final String SERVICE_FILE =
            "resources/META-INF/services/" + SimulatedRobot.class.getName();

    private SimulatedRobots() {
    }

    /** The one robot registered with {@code loader}. */
    public static SimulatedRobot registered(ClassLoader loader) {
        return single(ServiceLoader.load(SimulatedRobot.class, loader));
    }

    /** The only robot in {@code robots}, with a message naming the file to fix otherwise. */
    static SimulatedRobot single(Iterable<SimulatedRobot> robots) {
        List<SimulatedRobot> found = new ArrayList<>();
        for (SimulatedRobot robot : robots) {
            found.add(robot);
        }
        if (found.isEmpty()) {
            throw new IllegalStateException("no SimulatedRobot is registered; list the team's"
                    + " robot class in " + SERVICE_FILE);
        }
        if (found.size() > 1) {
            List<String> names = new ArrayList<>(found.size());
            for (SimulatedRobot robot : found) {
                names.add(robot.getClass().getName());
            }
            throw new IllegalStateException(found.size() + " SimulatedRobots are registered "
                    + names + "; a simulated app runs exactly one, so keep one line in "
                    + SERVICE_FILE);
        }
        return found.get(0);
    }
}
