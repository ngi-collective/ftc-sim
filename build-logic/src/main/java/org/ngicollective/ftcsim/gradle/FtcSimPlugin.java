package org.ngicollective.ftcsim.gradle;

import com.android.build.api.dsl.ApplicationExtension;
import com.android.build.api.dsl.ApplicationProductFlavor;
import com.android.build.api.dsl.ProductFlavor;

import org.gradle.api.GradleException;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.artifacts.dsl.DependencyHandler;
import org.gradle.api.file.Directory;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.Copy;
import org.gradle.api.tasks.JavaExec;
import org.gradle.api.tasks.testing.Test;

import java.io.File;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Locale;

/**
 * {@code org.ngicollective.ftc-sim}: the simulator, applied to a team's TeamCode with one id.
 *
 * <p>What it adds to a module that also applies {@code com.android.application}:</p>
 * <ul>
 *   <li>a {@code target} flavor dimension, with {@code robot} (the default, what goes to a
 *       competition) and {@code simulated} (fake hardware, its own application id);</li>
 *   <li>the simulator's modules on the {@code simulated} flavour and on the unit tests, the
 *       season named in {@code ftcSim.season} among them;</li>
 *   <li>unit tests on the JUnit Platform with the vision natives on {@code java.library.path},
 *       and Robolectric's view of the merged Android resources;</li>
 *   <li>{@code robot-config/} and {@code scenarios/} staged as Java resources of the simulated
 *       APK, which has no checkout to read them from;</li>
 *   <li>a {@code dashboard} task serving the Driver Hub against the module's OpModes.</li>
 * </ul>
 *
 * <p>Order matters in one place. Two copies of {@code org.opencv} reach the test classpath: the
 * SDK's Android build and openpnp's desktop build, and the Java classes have to match the natives
 * that load, which off a device are openpnp's. So openpnp is declared the moment Android is
 * applied, ahead of the SDK dependencies a team's build declares after it.
 * {@code OpenCvClasspathOrderTest} in TeamCode fails if that ever flips.</p>
 *
 * <p>In the simulator's own repository its modules are projects of the same build, and the
 * natives and UI come from that build's {@code :buildVisionNatives} and {@code :buildDashboardUi}.
 * A team's build resolves the modules from a Maven repository instead; packaging the natives and
 * the UI for that case is #31's.</p>
 */
public final class FtcSimPlugin implements Plugin<Project> {

    /** Maven group for a build that resolves the simulator. Placeholder until #30. */
    static final String GROUP = "org.ngicollective.ftcsim";

    static final String DIMENSION = "target";

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

    private static void configure(Project project, FtcSimExtension extension) {
        boolean inRepository = project.getRootProject().findProject(":TestFramework") != null;
        DependencyHandler dependencies = project.getDependencies();

        // First, before the SDK: see the class comment.
        dependencies.add("testImplementation", "org.openpnp:opencv:4.9.0-0");
        dependencies.add("testImplementation", "org.robolectric:robolectric:4.12.2");
        dependencies.add("testImplementation", "junit:junit:4.13.2");
        dependencies.add("testRuntimeOnly", "org.junit.vintage:junit-vintage-engine:5.10.0");

        ApplicationExtension android =
                project.getExtensions().getByType(ApplicationExtension.class);
        flavors(android);
        android.getDefaultConfig().setTestInstrumentationRunner(
                "androidx.test.runner.AndroidJUnitRunner");
        android.getTestOptions().getUnitTests().setIncludeAndroidResources(true);

        Provider<Directory> staged =
                project.getLayout().getBuildDirectory().dir("generated/robotConfig");
        android.getSourceSets().maybeCreate("simulated").getResources().srcDir(staged);
        stageResources(project, extension);

        // Lazy, all of them: this runs while Android is being applied, before the build script's
        // ftcSim block has set the season or the version.
        Provider<String> season = extension.getSeason()
                .orElse(project.getProviders().provider(() -> {
                    throw new GradleException("ftcSim.season is not set; name the season this"
                            + " robot practises, e.g. ftcSim { season = 'Season-BioBuzz' }");
                }));
        for (String module : new String[] {"TestFramework", "SimulatedApp"}) {
            dependencies.addProvider("simulatedImplementation",
                    lazyModule(project, module, extension, inRepository));
        }
        dependencies.addProvider("simulatedImplementation",
                season.map(name -> module(project, name, extension, inRepository)));
        for (String module : new String[] {"AndroidShims", "TestFramework", "Dashboard",
                "SimulatedApp-Testing"}) {
            dependencies.addProvider("testImplementation",
                    lazyModule(project, module, extension, inRepository));
        }
        dependencies.addProvider("testImplementation",
                season.map(name -> module(project, name, extension, inRepository)));

        for (String library : new String[] {"androidx.test.ext:junit:1.1.5",
                "androidx.test:runner:1.5.2", "androidx.test:rules:1.5.0",
                "androidx.test:core:1.5.0"}) {
            dependencies.add("androidTestImplementation", library);
        }

        File natives = project.getRootProject().getLayout().getBuildDirectory()
                .dir("vision-natives").get().getAsFile();
        unitTests(project, natives, inRepository);
        dashboard(project, natives, inRepository);
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

    private static void unitTests(Project project, File natives, boolean inRepository) {
        project.getTasks().withType(Test.class).configureEach(test -> {
            if (!test.getName().endsWith("UnitTest")) {
                return;
            }
            test.useJUnitPlatform();
            // AprilTagDetectorJNI and EasyOpenCV load their natives in a static initializer,
            // through System.loadLibrary, which reads this path once, at JVM start.
            test.systemProperty("java.library.path", natives.getAbsolutePath());
            if (inRepository) {
                test.dependsOn(":buildVisionNatives");
            }
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
            }
            // A session runs inside a Robolectric sandbox so vision OpModes work, and a sandbox
            // comes from a JUnit runner; see DashboardHost.
            task.getMainClass().set("org.ngicollective.ftcsim.app.DashboardLauncher");
            task.setClasspath(project.files(
                    project.getTasks().named("testSimulatedDebugUnitTest", Test.class)
                            .map(Test::getClasspath),
                    project.getRootProject().getLayout().getBuildDirectory().dir("dashboard-ui")));
            task.systemProperty("java.library.path", natives.getAbsolutePath());
            // Ctrl-C has to reach the JVM.
            task.setStandardInput(System.in);
        });
    }

    private static Provider<Object> lazyModule(Project project, String name,
                                               FtcSimExtension extension, boolean inRepository) {
        return project.getProviders().provider(
                () -> module(project, name, extension, inRepository));
    }

    /** A simulator module: the project in the simulator's own build, the artifact elsewhere. */
    static Object module(Project project, String name, FtcSimExtension extension,
                         boolean inRepository) {
        if (inRepository) {
            if (project.getRootProject().findProject(":" + name) == null) {
                throw new GradleException("ftcSim: no project :" + name + " in this build");
            }
            return project.getDependencies().project(Collections.singletonMap("path", ":" + name));
        }
        if (!extension.getVersion().isPresent()) {
            throw new GradleException("ftcSim.version is not set; it names the simulator release"
                    + " to resolve " + name + " from");
        }
        return GROUP + ":" + artifactId(name) + ":" + extension.getVersion().get();
    }

    /** {@code Season-BioBuzz} becomes {@code season-biobuzz}. */
    static String artifactId(String name) {
        return name.toLowerCase(Locale.ROOT);
    }
}
