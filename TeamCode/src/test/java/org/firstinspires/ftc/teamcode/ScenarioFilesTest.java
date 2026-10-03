package org.firstinspires.ftc.teamcode;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.ngicollective.ftcsim.camera.FieldTag;
import org.ngicollective.ftcsim.camera.GameElement;
import org.ngicollective.ftcsim.camera.SimulatedScene;
import org.ngicollective.ftcsim.camera.TagCluster;
import org.ngicollective.ftcsim.camera.Vec3;
import org.ngicollective.ftcsim.season.BioBuzzElements;
import org.ngicollective.ftcsim.season.BioBuzzField;
import org.ngicollective.ftcsim.season.BioBuzzScenario;
import org.ngicollective.ftcsim.season.BioBuzzScore;
import org.ngicollective.ftcsim.sim.SimConfigFiles;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * That the committed scenarios load and describe what their names claim.
 *
 * <p>A scenario is data, and data that nothing parses until an emulator boots is data that rots.
 * These load the real files, so a typo in a ball's kind or a malformed brace fails here in
 * milliseconds instead of on a device four minutes later.</p>
 */
class ScenarioFilesTest {

    @Test
    void everyCommittedScenarioStillLoads() throws IOException {
        List<Path> files = scenarioFiles();
        assertTrue(files.size() >= 2, "expected the committed scenarios, found " + files);

        for (Path file : files) {
            String name = file.getFileName().toString().replace(".json", "");
            BioBuzzScenario scenario = BioBuzzScenario.named(name);
            assertTrue(!scenario.name().isEmpty(), name + " should name itself");

            // Every scenario is still the BioBuzz field, however it is arranged.
            SimulatedScene scene = scenario.scene();
            assertEquals(4, scene.clusters().size(), name + " should have four CELLs");
            int tags = 0;
            for (TagCluster cluster : scene.clusters()) {
                tags += cluster.tags().size();
            }
            assertEquals(16, tags, name + " should have sixteen tags");
        }
    }

    /**
     * What the dashboard's picker is drawn from.
     *
     * <p>Checked against the directory rather than against a written-down list of three names, so
     * that adding a scenario does not break this and forgetting to list one cannot pass it. What
     * the picker needs is the name {@code SimConfigFiles.scenario} takes, which is the file's
     * stem: offering "practice-balls.json" would produce a request for
     * {@code practice-balls.json.json}.</p>
     */
    @Test
    void everyCommittedScenarioIsOfferedUnderTheNameThatLoadsIt() throws IOException {
        List<String> offered = SimConfigFiles.scenarios();
        List<String> onDisk = new ArrayList<>();
        for (Path file : scenarioFiles()) {
            onDisk.add(file.getFileName().toString().replace(".json", ""));
        }
        Collections.sort(onDisk);

        assertEquals(onDisk, offered, "the picker must offer exactly the committed scenarios");
        for (String name : offered) {
            // The round trip, which is the contract: every name offered is a name that loads.
            assertTrue(!BioBuzzScenario.named(name).name().isEmpty(),
                    name + " is offered but does not load");
        }
    }

    @Test
    void tippingBothHivesBackSwapsEveryCellsHeight() {
        // The point of that scenario: the arrangement the CAD did not capture.
        double officialRedAudience = heightOf(BioBuzzField.official(), BioBuzzField.RED_AUDIENCE);
        double tippedRedAudience = heightOf(
                BioBuzzScenario.named("hives-tipped-back").scene(), BioBuzzField.RED_AUDIENCE);

        assertTrue(tippedRedAudience < officialRedAudience,
                "tipped back, the red audience CELL should now be the low one: "
                        + tippedRedAudience + " vs " + officialRedAudience);
    }

    @Test
    void thePracticeScenarioPlacesBallsOfEachKind() {
        List<GameElement> elements = BioBuzzScenario.named("practice-balls").elements();

        assertEquals(6, elements.size());
        int pollen = 0;
        int nectar = 0;
        for (GameElement element : elements) {
            if ("POLLEN".equals(element.name())) {
                pollen++;
                // A scenario chooses where a ball is, never how big it is: that is the season's.
                assertEquals(BioBuzzElements.POLLEN_DIAMETER_METRES,
                        element.radiusMetres() * 2.0, 1e-9);
            } else {
                nectar++;
                assertEquals(BioBuzzElements.NECTAR_DIAMETER_METRES,
                        element.radiusMetres() * 2.0, 1e-9);
            }
        }
        assertEquals(3, pollen);
        assertEquals(3, nectar);
    }

    @Test
    void anElementThatIsNotAScoringElementIsRefused(@TempDir Path directory) throws IOException {
        // Strict loading earns its place here: a "GOLD_NECTAR" that silently became a grey ball
        // would render a field the season does not have, and every colour assertion after it
        // would be measuring fiction.
        Path file = directory.resolve("broken.json");
        Files.write(file, ("{\"version\": 1, \"name\": \"broken\", \"elements\": {"
                + "\"mystery\": {\"kind\": \"GOLD_NECTAR\", \"xMetres\": 0, \"yMetres\": 0}}}")
                .getBytes(StandardCharsets.UTF_8));

        IllegalArgumentException failure =
                assertThrows(IllegalArgumentException.class, () -> BioBuzzScenario.load(file));
        assertTrue(failure.getMessage().contains("GOLD_NECTAR"), failure.getMessage());
        assertTrue(failure.getMessage().contains("POLLEN"),
                "the failure should say what is allowed: " + failure.getMessage());
    }

    @Test
    void theMatchStagingScenarioScoresSixAllianceEach() {
        // What the manual sets up before a match starts (§10.3.1: three NECTAR in each
        // upward-facing CELL), and therefore the score a field nobody has touched already shows.
        // This is the one scenario whose elements are not on the tiles, so it is also the check
        // that a CELL-staged ball lands in the CELL: the six are placed from the CELL's own
        // geometry, and the score is computed by containment against that same geometry, so a ball
        // put a few inches out would score nothing.
        BioBuzzScenario staging = BioBuzzScenario.named("match-staging");
        SimulatedScene scene = staging.scene();

        BioBuzzScore score = BioBuzzScore.of(scene.scoringVolumes(), scene.elements(),
                Collections.<String, Integer>emptyMap());

        assertEquals(6, score.redPoints(), score.toString());
        assertEquals(6, score.bluePoints(), score.toString());
        assertEquals(3, score.cell(BioBuzzField.RED_AUDIENCE).holding().size());
    }

    @Test
    void stagedBallsAreSpreadAcrossTheCellRatherThanStacked() {
        // Three NECTAR at one point would be interpenetrating before the first step, and the
        // solver's first act would be to fire them apart -- emptying the basket this scenario
        // exists to fill. They have to start at least a diameter apart.
        List<GameElement> staged = BioBuzzScenario.named("match-staging").elements();

        for (int first = 0; first < staged.size(); first++) {
            for (int second = first + 1; second < staged.size(); second++) {
                Vec3 apart = staged.get(first).centre().minus(staged.get(second).centre());
                assertTrue(apart.length() >= BioBuzzElements.NECTAR_DIAMETER_METRES - 1e-9,
                        "elements " + first + " and " + second + " start " + apart.length()
                                + " m apart, which is inside one another");
            }
        }
    }

    @Test
    void anElementGivenBothACellAndCoordinatesIsRefused(@TempDir Path directory)
            throws IOException {
        // Two answers to "where does this start", and no rule about which wins. Taking one
        // silently would put a ball somewhere the file does not say.
        Path file = directory.resolve("both.json");
        Files.write(file, ("{\"version\": 1, \"name\": \"both\", \"elements\": {"
                + "\"confused\": {\"kind\": \"POLLEN\", \"cell\": \"RED AUDIENCE\","
                + " \"xMetres\": 0, \"yMetres\": 0}}}").getBytes(StandardCharsets.UTF_8));

        IllegalArgumentException failure =
                assertThrows(IllegalArgumentException.class, () -> BioBuzzScenario.load(file));
        assertTrue(failure.getMessage().contains("confused"), failure.getMessage());
    }

    @Test
    void anElementStagedInACellThatDoesNotExistNamesTheFourThatDo(@TempDir Path directory)
            throws IOException {
        // "RED GOAL" is the name a season-agnostic reflex produces, and a scenario that quietly
        // accepted it would stage a ball nowhere at all.
        Path file = directory.resolve("nocell.json");
        Files.write(file, ("{\"version\": 1, \"name\": \"nocell\", \"elements\": {"
                + "\"lost\": {\"kind\": \"POLLEN\", \"cell\": \"RED GOAL\"}}}")
                .getBytes(StandardCharsets.UTF_8));

        IllegalArgumentException failure =
                assertThrows(IllegalArgumentException.class, () -> BioBuzzScenario.load(file));
        assertTrue(failure.getMessage().contains("RED GOAL"), failure.getMessage());
        assertTrue(failure.getMessage().contains(BioBuzzField.RED_AUDIENCE),
                "the failure should list the CELLs that exist: " + failure.getMessage());
        assertTrue(failure.getMessage().contains("nocell.json"),
                "and say which file it was reading: " + failure.getMessage());
    }

    @Test
    void aMisspelledScenarioNameSaysWhereItLooked() {
        // Quietly falling back to the official field would let a test pass against an arrangement
        // nobody asked for.
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> BioBuzzScenario.named("practise-balls"));

        assertTrue(failure.getMessage().contains("practise-balls"), failure.getMessage());
        assertTrue(failure.getMessage().contains("scenarios"), failure.getMessage());
    }

    private static double heightOf(SimulatedScene scene, String clusterName) {
        for (TagCluster cluster : scene.clusters()) {
            if (cluster.name().equals(clusterName)) {
                List<FieldTag> tags = cluster.tags();
                return tags.get(0).pose().position().z();
            }
        }
        throw new AssertionError("no cluster named " + clusterName);
    }

    private static List<Path> scenarioFiles() throws IOException {
        try (java.util.stream.Stream<Path> files =
                     Files.list(SimConfigFiles.scenarioDirectory())) {
            return files.filter(path -> path.toString().endsWith(".json"))
                    .sorted()
                    .collect(java.util.stream.Collectors.toList());
        }
    }
}
