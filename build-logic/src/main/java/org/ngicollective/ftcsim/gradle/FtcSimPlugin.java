package org.ngicollective.ftcsim.gradle;

import com.android.build.api.dsl.AndroidSourceSet;
import com.android.build.api.dsl.ApplicationExtension;
import com.android.build.api.dsl.ApplicationProductFlavor;
import com.android.build.api.dsl.ProductFlavor;
import com.android.build.api.variant.ApplicationAndroidComponentsExtension;

import org.gradle.api.GradleException;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.dsl.DependencyHandler;
import org.gradle.api.file.ArchiveOperations;
import org.gradle.api.file.Directory;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.Copy;
import org.gradle.api.tasks.JavaExec;
import org.gradle.api.tasks.Sync;
import org.gradle.api.tasks.testing.Test;

import java.io.File;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import javax.inject.Inject;

/**
 * {@code org.ngi-collective.ftc-sim}: the simulator, applied to a team's TeamCode with one id.
 *
 * <p>What it adds to a module that also applies {@code com.android.application}:</p>
 * <ul>
 *   <li>a {@code target} flavor dimension, with {@code robot} (the default, what goes to a
 *       competition) and {@code simulated} (fake hardware, its own application id);</li>
 *   <li>the simulator on the {@code simulated} flavour and on the unit tests, with the season
 *       named in {@code ftcSim.season};</li>
 *   <li>the simulated app's own code, compiled from source into the {@code simulated} flavour
 *       against the team's {@code FtcRobotController} (ADR 0009): FIRST publishes no artifact for
 *       the activity it extends;</li>
 *   <li>unit tests on the JUnit Platform with the vision natives on {@code java.library.path},
 *       and Robolectric's view of the merged Android resources;</li>
 *   <li>{@code robot-config/} and {@code scenarios/} staged as Java resources of the simulated
 *       APK, which has no checkout to read them from;</li>
 *   <li>a {@code dashboard} task serving the Driver Hub against the module's OpModes.</li>
 * </ul>
 *
 * <p>Where the simulator comes from is one decision, made once: the projects of this build when
 * it is the simulator's own repository, or {@code org.ngi-collective.ftc-sim} artifacts at
 * {@code ftcSim.version} otherwise. Everything else reads that decision rather than repeating
 * it.</p>
 *
 * <p>Order matters in one place. Two copies of {@code org.opencv} reach the test classpath: the
 * SDK's Android build and openpnp's desktop build, and the Java classes have to match the natives
 * that load, which off a device are openpnp's. So openpnp is declared the moment Android is
 * applied, ahead of the SDK dependencies a team's build declares after it.
 * {@code OpenCvClasspathOrderTest} in TeamCode fails if that ever flips.</p>
 */
public abstract class FtcSimPlugin implements Plugin<Project> {

    /**
     * For unpacking resolved jars without touching {@code Project} at execution time, which the
     * configuration cache forbids.
     */
    @Inject
    protected abstract ArchiveOperations getArchives();

    /** The Maven group every simulator artifact is published under (ADR 0009). */
    static final String GROUP = "org.ngi-collective.ftc-sim";

    static final String DIMENSION = "target";

    /**
     * The lowest compile SDK the simulator's tests work against.
     *
     * <p>Robolectric's {@code WifiManager} shadow names an API 33 class, and the SDK's camera
     * stream builds a {@code WifiManager} as soon as an OpenCV camera is constructed. AGP puts
     * the compile SDK's {@code android.jar} on the unit-test classpath, and Robolectric finds the
     * class there. Upstream's {@code build.common.gradle} compiles against 30, which lacks it, so
     * every vision test in a stock team fork failed with {@code NoClassDefFoundError} until this
     * was raised.</p>
     */
    static final int MIN_COMPILE_SDK = 34;

    /** Each published artifact and the project that builds it in the simulator's repository. */
    static final Map<String, String> PROJECTS = projects();

    private static Map<String, String> projects() {
        Map<String, String> projects = new LinkedHashMap<>();
        projects.put("core", ":TestFramework");
        projects.put("dashboard", ":Dashboard");
        projects.put("camera-stream", ":CameraStream");
        projects.put("android-shims", ":AndroidShims");
        projects.put("app", ":SimulatedApp");
        projects.put("testing", ":SimulatedApp-Testing");
        return Collections.unmodifiableMap(projects);
    }

    @Override
    public void apply(Project project) {
        FtcSimExtension extension =
                project.getExtensions().create("ftcSim", FtcSimExtension.class);
        extension.getRobotConfigDir().convention(project.getLayout().getProjectDirectory()
                .dir("robot-config"));
        extension.getScenariosDir().convention(project.getLayout().getProjectDirectory()
                .dir("scenarios"));

        project.getPluginManager().withPlugin("com.android.application",
                applied -> configure(project, extension));
    }

    private void configure(Project project, FtcSimExtension extension) {
        boolean inRepository = project.getRootProject().findProject(":TestFramework") != null;
        DependencyHandler dependencies = project.getDependencies();

        // First, before the SDK: see the class comment.
        dependencies.add("testImplementation", "org.openpnp:opencv:4.9.0-0");
        dependencies.add("testImplementation", "org.robolectric:robolectric:4.12.2");
        dependencies.add("testImplementation", "junit:junit:4.13.2");
        dependencies.add("testRuntimeOnly", "org.junit.vintage:junit-vintage-engine:5.10.0");
        // JUnit 5 itself: upstream's build.dependencies.gradle declares no test framework, and
        // Gradle 9 no longer puts the platform launcher on a test runtime classpath unasked.
        dependencies.add("testImplementation", "org.junit.jupiter:junit-jupiter-api:5.10.0");
        dependencies.add("testRuntimeOnly", "org.junit.jupiter:junit-jupiter-engine:5.10.0");
        dependencies.add("testRuntimeOnly", "org.junit.platform:junit-platform-launcher:1.10.0");

        ApplicationExtension android =
                project.getExtensions().getByType(ApplicationExtension.class);
        raiseCompileSdk(project);
        flavors(android);
        android.getDefaultConfig().setTestInstrumentationRunner(
                "androidx.test.runner.AndroidJUnitRunner");
        android.getTestOptions().getUnitTests().setIncludeAndroidResources(true);

        AndroidSourceSet simulated = android.getSourceSets().maybeCreate("simulated");
        Provider<Directory> staged =
                project.getLayout().getBuildDirectory().dir("generated/robotConfig");
        simulated.getResources().srcDir(staged);
        stageResources(project, extension);

        // Lazy, all of them: this runs while Android is being applied, before the build script's
        // ftcSim block has set the season or the versions.
        for (String artifact : new String[] {"core"}) {
            dependencies.addProvider("simulatedImplementation",
                    lazy(project, () -> module(project, artifact, extension, inRepository)));
        }
        dependencies.addProvider("simulatedImplementation",
                lazy(project, () -> season(project, extension, inRepository)));
        for (String artifact : new String[] {"android-shims", "core", "dashboard", "testing"}) {
            dependencies.addProvider("testImplementation",
                    lazy(project, () -> module(project, artifact, extension, inRepository)));
        }
        dependencies.addProvider("testImplementation",
                lazy(project, () -> season(project, extension, inRepository)));

        for (String library : new String[] {"androidx.test.ext:junit:1.1.5",
                "androidx.test:runner:1.5.2", "androidx.test:rules:1.5.0",
                "androidx.test:core:1.5.0"}) {
            dependencies.add("androidTestImplementation", library);
        }

        appSources(project, extension, simulated, inRepository);
        File natives = visionNatives(project, extension, inRepository);
        unitTests(project, natives, inRepository);
        dashboard(project, natives, inRepository);
    }

    /**
     * Raises this module's compile SDK to {@link #MIN_COMPILE_SDK} if it is lower. Only the
     * compile SDK: minimum and target SDK are untouched, so the APK runs on the same Control Hub.
     * The team's {@code build.common.gradle}, which FIRST asks teams not to edit, stays as it is.
     *
     * <p>In {@code finalizeDsl}, AGP's hook for changing the DSL after the build script has run:
     * {@code build.common.gradle} sets the compile SDK after the plugin is applied.</p>
     */
    private static void raiseCompileSdk(Project project) {
        project.getExtensions().getByType(ApplicationAndroidComponentsExtension.class)
                .finalizeDsl(android -> {
            Integer compileSdk = android.getCompileSdk();
            if (compileSdk != null && compileSdk < MIN_COMPILE_SDK) {
                project.getLogger().lifecycle("ftc-sim: compiling " + project.getPath()
                        + " against SDK " + MIN_COMPILE_SDK + " instead of " + compileSdk
                        + "; Robolectric needs it for vision tests (min and target SDK are"
                        + " unchanged)");
                android.setCompileSdk(MIN_COMPILE_SDK);
            }
        });
    }

    private static void flavors(ApplicationExtension android) {
        if (!android.getFlavorDimensions().contains(DIMENSION)) {
            android.getFlavorDimensions().add(DIMENSION);
        }
        ApplicationProductFlavor robot = android.getProductFlavors().maybeCreate("robot");
        setDimension(robot, DIMENSION);
        robot.setDefault(true);
        ApplicationProductFlavor simulated = android.getProductFlavors().maybeCreate("simulated");
        setDimension(simulated, DIMENSION);
        // A distinct package, so both can sit on one device at once.
        simulated.setApplicationIdSuffix(".simulated");
        simulated.setVersionNameSuffix("-simulated");
    }

    /**
     * {@code flavor.setDimension(dimension)}, which javac cannot write.
     *
     * <p>AGP 8's {@code ProductFlavor} declares {@code setDimension(String)} twice, differing only
     * in return type: the property setter, returning {@code void}, and a deprecated Kotlin
     * function returning {@code Void}. Legal in bytecode, ambiguous in Java source, so the
     * setter is called by name.</p>
     */
    private static void setDimension(ProductFlavor flavor, String dimension) {
        for (Method method : ProductFlavor.class.getMethods()) {
            if (method.getName().equals("setDimension") && method.getReturnType() == void.class) {
                try {
                    method.invoke(flavor, dimension);
                    return;
                } catch (ReflectiveOperationException e) {
                    throw new GradleException("ftcSim: could not set the flavor dimension", e);
                }
            }
        }
        throw new GradleException("ftcSim: this AGP has no ProductFlavor.setDimension(String)");
    }

    private static void stageResources(Project project, FtcSimExtension extension) {
        project.getTasks().register("stageRobotConfig", Copy.class, task -> {
            task.setGroup("ftc");
            task.setDescription("Stage robot-config as Java resources for the simulated APK");
            task.from(extension.getRobotConfigDir());
            task.into(project.getLayout().getBuildDirectory()
                    .dir("generated/robotConfig/robot-config"));
        });
        project.getTasks().register("stageScenarios", Copy.class, task -> {
            task.setGroup("ftc");
            task.setDescription("Stage scenarios as Java resources for the simulated APK");
            task.from(extension.getScenariosDir());
            task.into(project.getLayout().getBuildDirectory()
                    .dir("generated/robotConfig/scenarios"));
        });
        project.getTasks().named("preBuild",
                task -> task.dependsOn("stageRobotConfig", "stageScenarios"));
    }

    /**
     * The simulated app's activity, robot start and hardware factory, compiled as part of the
     * {@code simulated} flavour, with the manifest that declares them.
     *
     * <p>From {@code :SimulatedApp}'s own sources in the simulator's repository, and from the
     * unpacked {@code app} artifact anywhere else: a jar holding {@code java/} and
     * {@code AndroidManifest.xml}. Either way the code is compiled against the team's
     * {@code FtcRobotController}, so an SDK change that breaks it fails in their build, on the
     * line that broke.</p>
     */
    private void appSources(Project project, FtcSimExtension extension,
                                   AndroidSourceSet simulated, boolean inRepository) {
        File root;
        if (inRepository) {
            root = new File(project.getRootProject().findProject(PROJECTS.get("app"))
                    .getProjectDir(), "src/main");
        } else {
            Configuration app = project.getConfigurations().create("ftcSimApp", configuration -> {
                configuration.setCanBeConsumed(false);
                configuration.setTransitive(false);
            });
            project.getDependencies().addProvider(app.getName(),
                    lazy(project, () -> module(project, "app", extension, false)));
            root = project.getLayout().getBuildDirectory().dir("ftc-sim/app").get().getAsFile();
            project.getTasks().register("unpackFtcSimApp", Sync.class, task -> {
                task.setDescription("Unpack the simulated app's sources into build/ftc-sim/app");
                task.from(getArchives().zipTree(singleFile(app)));
                task.into(root);
            });
            project.getTasks().named("preBuild", task -> task.dependsOn("unpackFtcSimApp"));
        }
        simulated.getJava().srcDir(new File(root, "java"));
        simulated.getManifest().srcFile(new File(root, "AndroidManifest.xml"));
    }

    /**
     * Where the vision natives are for unit tests and the dashboard: unpacked from the
     * {@code vision-natives} jar for this host outside the simulator's repository, built by
     * {@code :buildVisionNatives} inside it.
     */
    private File visionNatives(Project project, FtcSimExtension extension,
                                      boolean inRepository) {
        if (inRepository) {
            return project.getRootProject().getLayout().getBuildDirectory()
                    .dir("vision-natives").get().getAsFile();
        }
        String classifier = HostClassifier.current();
        Configuration natives = project.getConfigurations().create("ftcSimVisionNatives",
                configuration -> {
                    configuration.setCanBeConsumed(false);
                    configuration.setTransitive(false);
                });
        project.getDependencies().addProvider(natives.getName(), lazy(project,
                () -> module(project, "vision-natives", extension, false) + ":" + classifier));
        File out = project.getLayout().getBuildDirectory().dir("ftc-sim/vision-natives").get()
                .getAsFile();
        project.getTasks().register("unpackFtcSimVisionNatives", Sync.class, task -> {
            task.setDescription("Unpack the " + classifier + " vision natives");
            task.from(getArchives().zipTree(singleFile(natives)), spec -> {
                spec.include("natives/" + classifier + "/*");
                spec.eachFile(file -> file.setPath(file.getName()));
                spec.setIncludeEmptyDirs(false);
            });
            task.into(out);
        });
        return out;
    }

    /**
     * The one jar a non-transitive configuration resolves to, read when the task runs.
     *
     * <p>The lambda captures the configuration's name, never the configuration: the configuration
     * cache cannot store a {@code Configuration}, and a build with it enabled failed to store
     * its entry in 12.0.0.</p>
     */
    private static Provider<File> singleFile(Configuration configuration) {
        String name = configuration.getName();
        return configuration.getElements().map(files -> {
            if (files.size() != 1) {
                throw new GradleException(name + " resolved to " + files.size()
                        + " files; expected exactly one jar");
            }
            return files.iterator().next().getAsFile();
        });
    }

    private static void unitTests(Project project, File natives, boolean inRepository) {
        String provider = inRepository ? ":buildVisionNatives" : "unpackFtcSimVisionNatives";
        project.getTasks().withType(Test.class).configureEach(test -> {
            if (!test.getName().endsWith("UnitTest")) {
                return;
            }
            test.useJUnitPlatform();
            // AprilTagDetectorJNI and EasyOpenCV load their natives in a static initializer,
            // through System.loadLibrary, which reads this path once, at JVM start.
            test.systemProperty("java.library.path", natives.getAbsolutePath());
            test.dependsOn(provider);
            // An input as well as a dependency: a rebuilt detector is a different detector.
            test.getInputs().dir(natives).withPropertyName("visionNatives").optional();
        });
    }

    private static void dashboard(Project project, File natives, boolean inRepository) {
        project.getTasks().register("dashboard", JavaExec.class, task -> {
            task.setGroup("ftc");
            task.setDescription("Run the local Driver Hub dashboard against this module's OpModes");
            task.dependsOn("compileSimulatedDebugUnitTestJavaWithJavac");
            if (inRepository) {
                task.dependsOn(":buildVisionNatives", ":buildDashboardUi");
            } else {
                task.dependsOn("unpackFtcSimVisionNatives");
            }
            // A session runs inside a Robolectric sandbox so vision OpModes work, and a sandbox
            // comes from a JUnit runner; see DashboardHost.
            task.getMainClass().set("org.ngicollective.ftcsim.app.DashboardLauncher");
            // In this repository the UI is staged under the root build directory; a published
            // dashboard artifact carries it as resources of its own.
            task.setClasspath(project.files(
                    project.getTasks().named("testSimulatedDebugUnitTest", Test.class)
                            .map(Test::getClasspath),
                    inRepository
                            ? project.getRootProject().getLayout().getBuildDirectory()
                                    .dir("dashboard-ui")
                            : Collections.emptyList()));
            task.systemProperty("java.library.path", natives.getAbsolutePath());
            // Ctrl-C has to reach the JVM.
            task.setStandardInput(System.in);
        });
    }

    private interface Notation {
        Object get();
    }

    private static Provider<Object> lazy(Project project, Notation notation) {
        return project.getProviders().provider(notation::get);
    }

    /** A simulator artifact: its project in the simulator's own build, its coordinates elsewhere. */
    static Object module(Project project, String artifact, FtcSimExtension extension,
                         boolean inRepository) {
        if (inRepository) {
            return project(project, PROJECTS.get(artifact));
        }
        return GROUP + ":" + artifact + ":"
                + required(extension.getVersion().getOrNull(), "ftcSim.version",
                        "the simulator release to resolve " + artifact + " from, e.g. '12.0.0'");
    }

    /** The season module, released on its own version (ADR 0009). */
    static Object season(Project project, FtcSimExtension extension, boolean inRepository) {
        String season = required(extension.getSeason().getOrNull(), "ftcSim.season",
                "the game this robot practises, e.g. 'biobuzz'").toLowerCase(Locale.ROOT);
        String artifact = "season-" + season;
        if (inRepository) {
            for (Project candidate : project.getRootProject().getAllprojects()) {
                if (candidate.getName().toLowerCase(Locale.ROOT).equals(artifact)) {
                    return project(project, candidate.getPath());
                }
            }
            throw new GradleException("ftcSim: no season project for '" + season + "' in this"
                    + " build; expected one named like Season-" + season);
        }
        return GROUP + ":" + artifact + ":"
                + required(extension.getSeasonVersion().getOrNull(), "ftcSim.seasonVersion",
                        "the " + season + " release to use, e.g. '1.0.0'");
    }

    private static Object project(Project project, String path) {
        if (path == null || project.getRootProject().findProject(path) == null) {
            throw new GradleException("ftcSim: no project " + path + " in this build");
        }
        return project.getDependencies().project(Collections.singletonMap("path", path));
    }

    private static String required(String value, String property, String meaning) {
        if (value == null || value.trim().isEmpty()) {
            throw new GradleException(property + " is not set; it names " + meaning);
        }
        return value;
    }
}
