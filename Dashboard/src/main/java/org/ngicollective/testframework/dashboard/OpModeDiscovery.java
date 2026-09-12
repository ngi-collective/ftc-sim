package org.ngicollective.testframework.dashboard;

import com.qualcomm.robotcore.eventloop.opmode.Autonomous;
import com.qualcomm.robotcore.eventloop.opmode.Disabled;
import com.qualcomm.robotcore.eventloop.opmode.OpMode;
import com.qualcomm.robotcore.eventloop.opmode.TeleOp;

import org.ngicollective.testframework.dashboard.protocol.OpModeInfo;
import org.ngicollective.testframework.hardware.SimulatedRobot;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Finds the {@code @TeleOp}/{@code @Autonomous} classes on the classpath, the same way the Robot
 * Controller finds them on a robot: by annotation, not by registration.
 *
 * <p>Walks {@code java.class.path} rather than pulling in a classpath-scanning library. The search
 * is restricted to a package prefix, so it never touches the SDK's own thousands of classes.</p>
 */
public final class OpModeDiscovery {

    private final String packagePrefix;

    public OpModeDiscovery(String packagePrefix) {
        this.packagePrefix = packagePrefix;
    }

    /** Every enabled OpMode under the package prefix, sorted by display name. */
    public List<OpModeEntry> discoverOpModes() {
        List<OpModeEntry> found = new ArrayList<>();
        for (Class<?> candidate : classesUnderPrefix()) {
            if (!OpMode.class.isAssignableFrom(candidate)) {
                continue;
            }
            if (candidate.isAnnotationPresent(Disabled.class)) {
                continue;
            }
            OpModeInfo info = describe(candidate);
            if (info == null) {
                continue;
            }
            @SuppressWarnings("unchecked")
            Class<? extends OpMode> opModeClass = (Class<? extends OpMode>) candidate;
            found.add(new OpModeEntry(info, () -> instantiate(opModeClass)));
        }
        found.sort(Comparator.comparing(entry -> entry.info.name));
        return found;
    }

    /** The simulated robot configurations under the package prefix. */
    public List<SimulatedRobot> discoverRobots() {
        List<SimulatedRobot> found = new ArrayList<>();
        for (Class<?> candidate : classesUnderPrefix()) {
            if (!SimulatedRobot.class.isAssignableFrom(candidate)
                    || candidate.isInterface()
                    || java.lang.reflect.Modifier.isAbstract(candidate.getModifiers())) {
                continue;
            }
            found.add((SimulatedRobot) instantiateAny(candidate));
        }
        found.sort(Comparator.comparing(SimulatedRobot::name));
        return found;
    }

    private static OpModeInfo describe(Class<?> candidate) {
        TeleOp teleOp = candidate.getAnnotation(TeleOp.class);
        if (teleOp != null) {
            String name = teleOp.name().isEmpty() ? candidate.getSimpleName() : teleOp.name();
            return new OpModeInfo(name, teleOp.group(), "TeleOp", candidate.getName());
        }
        Autonomous autonomous = candidate.getAnnotation(Autonomous.class);
        if (autonomous != null) {
            String name = autonomous.name().isEmpty() ? candidate.getSimpleName() : autonomous.name();
            return new OpModeInfo(name, autonomous.group(), "Autonomous", candidate.getName());
        }
        return null;
    }

    private static OpMode instantiate(Class<? extends OpMode> opModeClass) {
        return (OpMode) instantiateAny(opModeClass);
    }

    private static Object instantiateAny(Class<?> type) {
        try {
            return type.getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(
                    type.getName() + " needs a public no-argument constructor to be run from the "
                            + "dashboard", e);
        }
    }

    private List<Class<?>> classesUnderPrefix() {
        List<Class<?>> classes = new ArrayList<>();
        String pathPrefix = packagePrefix.replace('.', '/');
        for (String entry : System.getProperty("java.class.path", "").split(File.pathSeparator)) {
            File root = new File(entry);
            if (root.isDirectory()) {
                collectFromDirectory(new File(root, pathPrefix), packagePrefix, classes);
            } else if (root.isFile() && entry.endsWith(".jar")) {
                collectFromJar(root, pathPrefix, classes);
            }
        }
        return classes;
    }

    private static void collectFromDirectory(File directory, String packageName, List<Class<?>> into) {
        File[] children = directory.listFiles();
        if (children == null) {
            return;
        }
        for (File child : children) {
            if (child.isDirectory()) {
                collectFromDirectory(child, packageName + "." + child.getName(), into);
            } else if (child.getName().endsWith(".class")) {
                String simpleName = child.getName().substring(0, child.getName().length() - 6);
                load(packageName + "." + simpleName, into);
            }
        }
    }

    private static void collectFromJar(File jar, String pathPrefix, List<Class<?>> into) {
        try (ZipFile zip = new ZipFile(jar)) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                String name = entries.nextElement().getName();
                if (name.startsWith(pathPrefix) && name.endsWith(".class")) {
                    load(name.substring(0, name.length() - 6).replace('/', '.'), into);
                }
            }
        } catch (IOException e) {
            // A classpath entry we cannot read simply contributes no OpModes.
        }
    }

    private static void load(String className, List<Class<?>> into) {
        if (className.contains("$")) {
            return;
        }
        try {
            into.add(Class.forName(className, false, OpModeDiscovery.class.getClassLoader()));
        } catch (Throwable ignored) {
            // Classes that cannot even be loaded (missing optional dependencies) are not OpModes
            // we could have run anyway.
        }
    }
}
