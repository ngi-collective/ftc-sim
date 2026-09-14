package org.ngicollective.testframework.sim;

import org.ngicollective.testframework.camera.GameElement;
import org.ngicollective.testframework.camera.SimulatedScene;
import org.ngicollective.testframework.camera.TagCluster;
import org.ngicollective.testframework.season.BioBuzzField;

import java.net.URL;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * An arrangement of the field to run against: how the HIVEs are tipped, and what is lying around.
 *
 * <p>The official field needs no file &mdash; {@link BioBuzzField#official()} is the truth, the
 * same way {@link FieldConfig#standard()} is. A scenario is for everything that is a
 * <em>choice</em>: the HIVE tipped the other way, a practice field with three balls on it, the
 * arrangement that broke autonomous last weekend. Committing those beside the OpModes makes a bug
 * report reproducible by name.</p>
 *
 * <p>Loading is strict, with one deliberate exception: the HIVEs default to the official tip
 * states, because a scenario that only wants to place a ball should not have to restate the
 * field's geometry to do it. Everything else follows {@link ConfigJson}'s rule that a missing
 * value is an error rather than a zero.</p>
 */
public final class ScenarioConfig {

    /** The only schema this build understands; bumping it is how a breaking change announces itself. */
    private static final int VERSION = 1;

    private final String name;
    private final BioBuzzField.HiveTip redHive;
    private final BioBuzzField.HiveTip blueHive;
    private final List<GameElement> elements;

    private ScenarioConfig(String name, BioBuzzField.HiveTip redHive,
                           BioBuzzField.HiveTip blueHive, List<GameElement> elements) {
        this.name = name;
        this.redHive = redHive;
        this.blueHive = blueHive;
        this.elements = elements;
    }

    /** Reads a scenario, failing with the file and field name if anything is wrong. */
    public static ScenarioConfig load(Path file) {
        return from(ConfigJson.read(file, VERSION));
    }

    /** The same, from a classpath resource, for a build that runs from inside an APK. */
    static ScenarioConfig load(URL resource) {
        return from(ConfigJson.read(resource, VERSION));
    }

    private static ScenarioConfig from(ConfigJson json) {
        BioBuzzField.HiveTip red = BioBuzzField.HiveTip.AUDIENCE_UP;
        BioBuzzField.HiveTip blue = BioBuzzField.HiveTip.AUDIENCE_DOWN;
        if (json.names().contains("hives")) {
            ConfigJson hives = json.child("hives");
            red = tip(hives, "red", red);
            blue = tip(hives, "blue", blue);
        }

        List<GameElement> elements = new ArrayList<>();
        if (json.names().contains("elements")) {
            ConfigJson all = json.child("elements");
            for (String label : all.names()) {
                elements.add(element(label, all.child(label)));
            }
        }

        return new ScenarioConfig(json.string("name"), red, blue, elements);
    }

    private static BioBuzzField.HiveTip tip(ConfigJson hives, String alliance,
                                            BioBuzzField.HiveTip fallback) {
        if (!hives.names().contains(alliance)) {
            return fallback;
        }
        String value = hives.string(alliance);
        for (BioBuzzField.HiveTip candidate : BioBuzzField.HiveTip.values()) {
            if (candidate.name().equalsIgnoreCase(value)) {
                return candidate;
            }
        }
        throw new IllegalArgumentException(hives.source() + ": \"" + alliance + "\" is \"" + value
                + "\", which is not a HIVE tip state; use AUDIENCE_UP or AUDIENCE_DOWN");
    }

    /**
     * One ball, by kind and position on the floor.
     *
     * <p>The kind decides the size and colour, so a scenario cannot describe a five-inch yellow
     * NECTAR that no match will ever contain.</p>
     */
    private static GameElement element(String label, ConfigJson json) {
        String kind = json.string("kind");
        double x = json.number("xMetres");
        double y = json.number("yMetres");
        if ("POLLEN".equalsIgnoreCase(kind)) {
            return GameElement.pollen(x, y);
        }
        if ("RED_NECTAR".equalsIgnoreCase(kind)) {
            return GameElement.redNectar(x, y);
        }
        if ("BLUE_NECTAR".equalsIgnoreCase(kind)) {
            return GameElement.blueNectar(x, y);
        }
        throw new IllegalArgumentException(json.source() + ": element \"" + label + "\" is a \""
                + kind + "\", which is not a BioBuzz scoring element; use POLLEN, RED_NECTAR or"
                + " BLUE_NECTAR");
    }

    /** The scenario's name, as a failure message or a dashboard would show it. */
    public String name() {
        return name;
    }

    public BioBuzzField.HiveTip redHive() {
        return redHive;
    }

    public BioBuzzField.HiveTip blueHive() {
        return blueHive;
    }

    /** The balls on the field, in file order. */
    public List<GameElement> elements() {
        return elements;
    }

    /** The field this scenario describes, ready to render. */
    public SimulatedScene scene() {
        List<TagCluster> clusters = BioBuzzField.clusters(redHive, blueHive);
        return new SimulatedScene(clusters, elements);
    }
}
