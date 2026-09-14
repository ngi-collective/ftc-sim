package org.ngicollective.testframework.sim;

import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Finds the robot and field descriptions, on disk or inside the APK.
 *
 * <p>Two places, tried in that order, because the same configuration has to serve two very
 * different processes:</p>
 * <ol>
 *   <li><b>A file</b> under {@link #directory()} &mdash; a plain relative name resolved against the
 *       process working directory, exactly as the dashboard resolves its saved layouts. For
 *       {@code mise run dashboard} and the unit tests that directory is the TeamCode module, which
 *       puts the configuration beside the OpModes it describes, committed and reviewed with
 *       them.</li>
 *   <li><b>A classpath resource</b> {@code robot-config/<name>.json} &mdash; what the simulated
 *       APK sees. On a Control Hub or an emulator there is no source tree and no useful working
 *       directory, so the same files are packaged as resources and read from there.</li>
 * </ol>
 *
 * <p>The file wins when both exist, so editing the source tree changes what the next run does
 * without a rebuild. Both paths parse the same bytes through the same loader, so a robot that
 * drives correctly in a test drives identically on the emulator.</p>
 */
public final class SimConfigFiles {

    /** Relative to the module the process runs in, which is TeamCode. See {@code DashboardMain}. */
    private static final String DIRECTORY = "robot-config";

    private static final String FIELD_FILE = "field.json";

    private static final String EXTENSION = ".json";

    private SimConfigFiles() {
    }

    /** Where the configuration files live on disk, absolute, for an error message to point at. */
    public static Path directory() {
        return Paths.get(DIRECTORY).toAbsolutePath().normalize();
    }

    /**
     * The robot described by {@code <directory>/<name>.json}, or by the packaged resource of the
     * same name.
     *
     * <p>Unlike the field, a robot that nobody described has no safe default: guessing a wheel
     * radius would produce a simulator that runs happily and lies about every distance it reports.
     * When neither lookup finds anything, this says so and names both places it looked.</p>
     */
    public static RobotConfig robot(String name) {
        String fileName = name + EXTENSION;
        Path file = directory().resolve(fileName);
        if (Files.isRegularFile(file)) {
            return RobotConfig.load(file);
        }
        URL resource = resource(fileName);
        if (resource != null) {
            return RobotConfig.load(resource);
        }
        throw new IllegalArgumentException("no configuration for robot \"" + name + "\": there is"
                + " no file at " + file + " and no classpath resource \"" + resourcePath(fileName)
                + "\"");
    }

    /**
     * The field, or the competition field when nothing describes one.
     *
     * <p>A default is honest here and nowhere else in this class: a competition field has published
     * dimensions, so {@link FieldConfig#standard()} is the truth rather than a guess. A practice
     * field that is not that size is exactly what the file is for.</p>
     */
    public static FieldConfig field() {
        Path file = directory().resolve(FIELD_FILE);
        if (Files.isRegularFile(file)) {
            return FieldConfig.load(file);
        }
        URL resource = resource(FIELD_FILE);
        return resource == null ? FieldConfig.standard() : FieldConfig.load(resource);
    }

    /**
     * The packaged copy of a configuration file, or null when this build has none.
     *
     * <p>Loaded through this class's own loader rather than the thread's context loader: under an
     * APK they are the same, and on a plain JVM the context loader is whatever the test runner or
     * Gradle worker happened to leave behind.</p>
     */
    private static URL resource(String fileName) {
        ClassLoader loader = SimConfigFiles.class.getClassLoader();
        return loader == null
                ? ClassLoader.getSystemResource(resourcePath(fileName))
                : loader.getResource(resourcePath(fileName));
    }

    private static String resourcePath(String fileName) {
        return DIRECTORY + "/" + fileName;
    }
}
