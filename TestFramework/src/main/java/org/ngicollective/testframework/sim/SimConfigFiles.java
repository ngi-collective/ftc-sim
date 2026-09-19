package org.ngicollective.testframework.sim;

import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;

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

    /**
     * Where field arrangements live, beside the robot description rather than inside it.
     *
     * <p>Separate because they change for different reasons: a robot configuration describes
     * hardware that stays put for a season, while a scenario is the arrangement of one run.</p>
     */
    private static final String SCENARIO_DIRECTORY = "scenarios";

    private static final String FIELD_FILE = "field.json";

    private static final String EXTENSION = ".json";

    private SimConfigFiles() {
    }

    /** Where the configuration files live on disk, absolute, for an error message to point at. */
    public static Path directory() {
        return Paths.get(DIRECTORY).toAbsolutePath().normalize();
    }

    /** Where scenarios live on disk, absolute, for an error message to point at. */
    public static Path scenarioDirectory() {
        return Paths.get(SCENARIO_DIRECTORY).toAbsolutePath().normalize();
    }

    /**
     * The scenario described by {@code scenarios/<name>.json}, or by the packaged resource.
     *
     * <p>No default, deliberately. A caller that wants the competition field asks
     * {@code BioBuzzField.official()} for it and gets the published geometry; a caller that named
     * a scenario meant that scenario, and quietly substituting the official field for a
     * misspelled name would make a test pass against the wrong arrangement.</p>
     */
    public static ScenarioConfig scenario(String name) {
        String fileName = name + EXTENSION;
        Path file = scenarioDirectory().resolve(fileName);
        if (Files.isRegularFile(file)) {
            return ScenarioConfig.load(file);
        }
        URL resource = resource(SCENARIO_DIRECTORY, fileName);
        if (resource != null) {
            return ScenarioConfig.load(resource);
        }
        throw new IllegalArgumentException("no scenario \"" + name + "\": there is no file at "
                + file + " and no classpath resource \"" + SCENARIO_DIRECTORY + "/" + fileName
                + "\"");
    }

    /**
     * Every scenario on disk, by the name {@link #scenario(String)} takes, sorted.
     *
     * <p>Files only, unlike every other read here, and the asymmetry is deliberate: a classpath
     * cannot be enumerated portably &mdash; a jar can be walked, a directory can be listed, an
     * APK is neither &mdash; so a packaged build would answer with a plausible-looking short list
     * rather than an honest one. The one caller that needs a list is the dashboard, which always
     * runs from a source tree; a packaged build still <em>loads</em> any scenario by name.</p>
     *
     * <p>Empty when the directory is missing, because a team that has written no scenarios has
     * none rather than having a broken installation.</p>
     */
    public static List<String> scenarios() {
        Path directory = scenarioDirectory();
        if (!Files.isDirectory(directory)) {
            return Collections.emptyList();
        }
        List<String> names = new ArrayList<>();
        try (Stream<Path> files = Files.list(directory)) {
            for (Path file : (Iterable<Path>) files.sorted()::iterator) {
                String fileName = file.getFileName().toString();
                if (Files.isRegularFile(file) && fileName.endsWith(EXTENSION)) {
                    names.add(fileName.substring(0, fileName.length() - EXTENSION.length()));
                }
            }
        } catch (IOException unreadable) {
            throw new IllegalStateException("cannot list scenarios in " + directory, unreadable);
        }
        return Collections.unmodifiableList(names);
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
     * The robot described by {@code <directory>/<name>.json}, with no packaged fallback.
     *
     * <p>For a caller that has a particular directory in mind &mdash; a test that writes a
     * configuration file, edits it, and expects the next read to see the edit, without writing
     * into the one the team drives. A caller that named a place meant that place, so a missing
     * file is an error here rather than a quiet fall back to whatever this build happens to have
     * packaged.</p>
     */
    public static RobotConfig robot(Path directory, String name) {
        Path file = directory.resolve(name + EXTENSION);
        if (!Files.isRegularFile(file)) {
            throw new IllegalArgumentException("no configuration for robot \"" + name
                    + "\": there is no file at " + file);
        }
        return RobotConfig.load(file);
    }

    /**
     * The file {@link #robot(String)} reads, or null when this build has only the packaged copy.
     *
     * <p>For a caller that needs to write a number back into the description it loaded &mdash;
     * the dashboard saving a camera mount someone aimed by hand. Null because a resource inside
     * an APK is not a file and cannot be written to, and handing out a path that nothing is at
     * would turn that into a mysterious write failure instead of an honest refusal.</p>
     */
    public static Path robotFile(String name) {
        Path file = directory().resolve(name + EXTENSION);
        return Files.isRegularFile(file) ? file : null;
    }

    /**
     * The file {@link #robot(Path, String)} reads, which that method insists exists.
     */
    public static Path robotFile(Path directory, String name) {
        return directory.resolve(name + EXTENSION);
    }

    /**
     * The field described by {@code <directory>/field.json}, or the competition field when that
     * directory describes none.
     *
     * <p>The default is as honest here as in {@link #field()}, and it is what lets a test write
     * only the robot file it cares about.</p>
     */
    public static FieldConfig field(Path directory) {
        Path file = directory.resolve(FIELD_FILE);
        return Files.isRegularFile(file) ? FieldConfig.load(file) : FieldConfig.standard();
    }

    /**
     * The packaged copy of a configuration file, or null when this build has none.
     *
     * <p>Loaded through this class's own loader rather than the thread's context loader: under an
     * APK they are the same, and on a plain JVM the context loader is whatever the test runner or
     * Gradle worker happened to leave behind.</p>
     */
    private static URL resource(String fileName) {
        return resource(DIRECTORY, fileName);
    }

    private static URL resource(String directory, String fileName) {
        String path = directory + "/" + fileName;
        ClassLoader loader = SimConfigFiles.class.getClassLoader();
        return loader == null
                ? ClassLoader.getSystemResource(path)
                : loader.getResource(path);
    }

    private static String resourcePath(String fileName) {
        return DIRECTORY + "/" + fileName;
    }
}
