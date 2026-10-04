package org.ngicollective.ftcsim.gradle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.gradle.testkit.runner.TaskOutcome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The plugin applied to a team's build: an Android app outside the simulator's repository, so the
 * simulator's modules are Maven coordinates rather than projects.
 *
 * <p>The fixture applies Android by id from the plugin-under-test classpath, which carries AGP
 * (see build-logic/build.gradle): the plugin compiles against AGP without bundling it, and under
 * TestKit it can only see classes on its own classpath.</p>
 */
class FtcSimPluginTest {

    @TempDir
    Path project;

    private static final String CONFIGURED =
            "version = '12.0.0'\n    season = 'biobuzz'\n    seasonVersion = '1.0.0'";

    @Test
    void addsTheFlavoursTheStagingAndTheDashboard() throws IOException {
        fixture(CONFIGURED);

        String out = run("tasks", "--all").getOutput();

        assertTrue(out.contains("assembleSimulatedDebug"), out);
        assertTrue(out.contains("assembleRobotDebug"), out);
        assertTrue(out.contains("stageRobotConfig"), out);
        assertTrue(out.contains("unpackFtcSimApp"), out);
        assertTrue(out.contains("unpackFtcSimVisionNatives"), out);
        assertTrue(out.contains("dashboard"), out);
    }

    @Test
    void resolvesTheCoreAndTheSeasonEachAtItsOwnVersion() throws IOException {
        fixture(CONFIGURED);

        String out = run(":app:dependencies", "--configuration", "simulatedDebugRuntimeClasspath")
                .getOutput();

        assertTrue(out.contains("org.ngi-collective.ftc-sim:core:12.0.0"), out);
        assertTrue(out.contains("org.ngi-collective.ftc-sim:season-biobuzz:1.0.0"), out);
        // The app is compiled from source into the flavour, never put on the classpath as a jar.
        assertFalse(out.contains("org.ngi-collective.ftc-sim:app"), out);
    }

    @Test
    void theAppAndTheNativesAreFetchedAtTheSimulatorsVersion() throws IOException {
        fixture(CONFIGURED);

        String app = run(":app:dependencies", "--configuration", "ftcSimApp").getOutput();
        String natives = run(":app:dependencies", "--configuration", "ftcSimVisionNatives")
                .getOutput();

        assertTrue(app.contains("org.ngi-collective.ftc-sim:app:12.0.0"), app);
        assertTrue(natives.contains("org.ngi-collective.ftc-sim:vision-natives:12.0.0"), natives);
    }

    @Test
    void theSimulatedFlavourCompilesTheUnpackedAppAndItsManifest() throws IOException {
        fixture(CONFIGURED);
        write("app/print.gradle", ""
                + "tasks.register('printSimulated') {\n"
                + "    def java = android.sourceSets.simulated.java.srcDirs\n"
                + "    def manifest = android.sourceSets.simulated.manifest.srcFile\n"
                + "    doLast { println \"java=$java\"; println \"manifest=$manifest\" }\n"
                + "}\n");
        append("app/build.gradle", "apply from: 'print.gradle'\n");

        String out = run(":app:printSimulated").getOutput();

        assertTrue(out.contains("ftc-sim/app/java"), out);
        assertTrue(out.contains("ftc-sim/app/AndroidManifest.xml"), out);
    }

    @Test
    void upstreamsCompileSdkIsRaisedForRobolectricButNothingElseChanges() throws IOException {
        // Upstream's build.common.gradle compiles against 30, which lacks a class Robolectric's
        // WifiManager shadow names; vision tests fail in a stock fork unless this is raised.
        fixture(CONFIGURED);
        Files.write(project.resolve("app/build.gradle"), new String(
                Files.readAllBytes(project.resolve("app/build.gradle")), StandardCharsets.UTF_8)
                .replace("compileSdk 34", "compileSdk 30").getBytes(StandardCharsets.UTF_8));
        write("app/print.gradle", ""
                + "android.defaultConfig { targetSdk 28 }\n"
                + "androidComponents.onVariants(androidComponents.selector().all()) { v ->\n"
                + "    if (v.name == 'simulatedDebug') {\n"
                + "        println \"minSdk=${v.minSdk.apiLevel} targetSdk=${v.targetSdk.apiLevel}\"\n"
                + "    }\n"
                + "}\n");
        append("app/build.gradle", "apply from: 'print.gradle'\n");

        String out = run("help").getOutput();

        assertTrue(out.contains("ftc-sim: compiling :app against SDK 34 instead of 30"), out);
        assertTrue(out.contains("minSdk=24 targetSdk=28"), out);
    }

    @Test
    void aBuildThatResolvesTheSimulatorMustSayWhichVersion() throws IOException {
        fixture("season = 'biobuzz'\n    seasonVersion = '1.0.0'");

        assertFailsWith("ftcSim.version is not set");
    }

    @Test
    void aMissingSeasonIsNamedWhenTheClasspathIsResolved() throws IOException {
        fixture("version = '12.0.0'");

        assertFailsWith("ftcSim.season is not set");
    }

    @Test
    void aSeasonNeedsItsOwnVersion() throws IOException {
        fixture("version = '12.0.0'\n    season = 'biobuzz'");

        assertFailsWith("ftcSim.seasonVersion is not set");
    }

    @Test
    void theUnpackTasksRunWithTheConfigurationCache() throws IOException {
        // Teams turn the configuration cache on. 12.0.0's unpack tasks held a Configuration in a
        // lambda, and storing the cache entry failed. Run both for real, against a file
        // repository holding the two jars, and check what they unpacked.
        fixture(CONFIGURED);
        String classifier = HostClassifier.current();
        publish("app", null, zip("java/org/example/App.java", "class App {}",
                "AndroidManifest.xml", "<manifest/>"));
        publish("vision-natives", classifier, zip("natives/" + classifier + "/libapriltag.so",
                "native", "natives/other-arch/libapriltag.so", "not this host's"));
        Files.write(project.resolve("settings.gradle"), new String(
                Files.readAllBytes(project.resolve("settings.gradle")), StandardCharsets.UTF_8)
                .replace("dependencyResolutionManagement { repositories { ",
                        "dependencyResolutionManagement { repositories { maven { url = file('repo') }; ")
                .getBytes(StandardCharsets.UTF_8));

        BuildResult result = run("unpackFtcSimApp", "unpackFtcSimVisionNatives",
                "--configuration-cache");

        assertTrue(result.getOutput().contains("Configuration cache entry stored"),
                result.getOutput());
        assertTrue(Files.exists(project.resolve("app/build/ftc-sim/app/java/org/example/App.java")));
        assertTrue(Files.exists(project.resolve("app/build/ftc-sim/app/AndroidManifest.xml")));
        assertTrue(Files.exists(project.resolve("app/build/ftc-sim/vision-natives/libapriltag.so")));
        assertEquals(1, Files.list(project.resolve("app/build/ftc-sim/vision-natives")).count(),
                "only this host's natives are unpacked, and flattened");
    }

    /** Puts {@code jar} into the fixture's file repository at the simulator's 12.0.0. */
    private void publish(String artifact, String classifier, byte[] jar) throws IOException {
        Path dir = project.resolve("repo/org/ngi-collective/ftc-sim/" + artifact + "/12.0.0");
        Files.createDirectories(dir);
        String base = artifact + "-12.0.0";
        Files.write(dir.resolve(base + (classifier == null ? "" : "-" + classifier) + ".jar"), jar);
        Files.write(dir.resolve(base + ".pom"), (""
                + "<project><modelVersion>4.0.0</modelVersion>"
                + "<groupId>org.ngi-collective.ftc-sim</groupId><artifactId>" + artifact
                + "</artifactId><version>12.0.0</version></project>")
                .getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] zip(String... namesAndContents) throws IOException {
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        try (java.util.zip.ZipOutputStream zip = new java.util.zip.ZipOutputStream(bytes)) {
            for (int i = 0; i < namesAndContents.length; i += 2) {
                zip.putNextEntry(new java.util.zip.ZipEntry(namesAndContents[i]));
                zip.write(namesAndContents[i + 1].getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    @Test
    void aTeamWithNoRobotConfigDirectoryStillBuilds() throws IOException {
        fixture(CONFIGURED);

        BuildResult result = run("stageRobotConfig", "stageScenarios");

        assertEquals(TaskOutcome.NO_SOURCE, result.task(":app:stageRobotConfig").getOutcome());
        assertEquals(TaskOutcome.NO_SOURCE, result.task(":app:stageScenarios").getOutcome());
    }

    private void assertFailsWith(String message) {
        BuildResult result = runner(":app:dependencies", "--configuration",
                "simulatedDebugRuntimeClasspath").buildAndFail();
        assertTrue(result.getOutput().contains(message), result.getOutput());
    }

    private BuildResult run(String... arguments) {
        return runner(arguments).build();
    }

    private GradleRunner runner(String... arguments) {
        return GradleRunner.create()
                .withProjectDir(project.toFile())
                .withPluginClasspath()
                .withArguments(arguments);
    }

    private void fixture(String ftcSim) throws IOException {
        write("settings.gradle", ""
                + "pluginManagement { repositories { google(); mavenCentral() } }\n"
                + "dependencyResolutionManagement { repositories { google(); mavenCentral() } }\n"
                + "include ':app'\n");
        write("gradle.properties", "android.useAndroidX=true\n");
        String sdk = System.getenv("ANDROID_HOME");
        if (sdk != null) {
            write("local.properties", "sdk.dir=" + sdk.replace("\\", "\\\\") + "\n");
        }
        write("app/build.gradle", ""
                + "plugins {\n"
                + "    id 'org.ngi-collective.ftc-sim'\n"
                + "    id 'com.android.application'\n"
                + "}\n"
                + "android {\n"
                + "    namespace = 'org.example.teamcode'\n"
                + "    compileSdk 34\n"
                + "    defaultConfig { minSdk 24 }\n"
                + "}\n"
                + "ftcSim {\n"
                + "    " + ftcSim + "\n"
                + "}\n");
    }

    private void append(String path, String content) throws IOException {
        Files.write(project.resolve(path), content.getBytes(StandardCharsets.UTF_8),
                java.nio.file.StandardOpenOption.APPEND);
    }

    private void write(String path, String content) throws IOException {
        Path file = project.resolve(path);
        Files.createDirectories(file.getParent());
        Files.write(file, content.getBytes(StandardCharsets.UTF_8));
    }
}
