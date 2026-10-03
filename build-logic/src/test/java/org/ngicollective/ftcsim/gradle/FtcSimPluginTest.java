package org.ngicollective.ftcsim.gradle;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

    @Test
    void addsTheFlavoursTheStagingAndTheDashboard() throws IOException {
        fixture("season = 'Season-BioBuzz'\n    version = '0.1.0'");

        BuildResult result = run("tasks", "--all");

        String out = result.getOutput();
        assertTrue(out.contains("assembleSimulatedDebug"), out);
        assertTrue(out.contains("assembleRobotDebug"), out);
        assertTrue(out.contains("stageRobotConfig"), out);
        assertTrue(out.contains("dashboard"), out);
    }

    @Test
    void resolvesTheSimulatorAndTheSeasonFromMavenOutsideItsOwnRepository() throws IOException {
        fixture("season = 'Season-BioBuzz'\n    version = '0.1.0'");

        String out = run(":app:dependencies", "--configuration", "simulatedDebugRuntimeClasspath")
                .getOutput();

        assertTrue(out.contains("org.ngicollective.ftcsim:testframework:0.1.0"), out);
        assertTrue(out.contains("org.ngicollective.ftcsim:simulatedapp:0.1.0"), out);
        assertTrue(out.contains("org.ngicollective.ftcsim:season-biobuzz:0.1.0"), out);
    }

    @Test
    void aBuildThatResolvesTheSimulatorMustSayWhichVersion() throws IOException {
        fixture("season = 'Season-BioBuzz'");

        BuildResult result = runner(":app:dependencies", "--configuration",
                "simulatedDebugRuntimeClasspath").buildAndFail();

        assertTrue(result.getOutput().contains("ftcSim.version is not set"), result.getOutput());
    }

    @Test
    void aMissingSeasonIsNamedWhenTheClasspathIsResolved() throws IOException {
        fixture("version = '0.1.0'");

        BuildResult result = runner(":app:dependencies", "--configuration",
                "simulatedDebugRuntimeClasspath").buildAndFail();

        assertTrue(result.getOutput().contains("ftcSim.season is not set"), result.getOutput());
    }

    @Test
    void aTeamWithNoRobotConfigDirectoryStillBuilds() throws IOException {
        fixture("season = 'Season-BioBuzz'\n    version = '0.1.0'");

        BuildResult result = run("stageRobotConfig", "stageScenarios");

        assertEquals(TaskOutcome.NO_SOURCE, result.task(":app:stageRobotConfig").getOutcome());
        assertEquals(TaskOutcome.NO_SOURCE, result.task(":app:stageScenarios").getOutcome());
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
                + "    id 'org.ngicollective.ftc-sim'\n"
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

    private void write(String path, String content) throws IOException {
        Path file = project.resolve(path);
        Files.createDirectories(file.getParent());
        Files.write(file, content.getBytes(StandardCharsets.UTF_8));
    }
}
