package org.ngicollective.testframework.sim;

import org.ngicollective.testframework.camera.GameElement;
import org.ngicollective.testframework.camera.Pose3d;
import org.ngicollective.testframework.camera.SimulatedScene;
import org.ngicollective.testframework.camera.Vec3;
import org.ngicollective.testframework.season.BioBuzzField;
import org.ngicollective.testframework.season.BioBuzzHive;

import java.net.URL;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
            // How many are already staged in each CELL, so the next one is put beside them rather
            // than inside them: see stagedIn.
            Map<String, Integer> perCell = new LinkedHashMap<>();
            for (String label : all.names()) {
                elements.add(element(label, all.child(label), red, blue, perCell));
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
     * One ball: what kind it is, and where it starts.
     *
     * <p>The kind decides the size and colour, so a scenario cannot describe a five-inch yellow
     * NECTAR that no match will ever contain.</p>
     *
     * <p>Two ways to say where. {@code xMetres} and {@code yMetres} put it on the tiles, which is
     * where most of a match's elements start. {@code cell} puts it inside a named CELL, which is
     * where the manual stages three NECTAR per alliance (&sect;10.3.1) &mdash; four and a half feet
     * up, on a basket whose position depends on how the HIVE is tipped. A scenario naming
     * coordinates for that would be restating CAD this repository already holds, and would be
     * wrong for the other tip state.</p>
     *
     * <p>{@code cell} names one of the four CELLs, not "whichever of red's is up", and a scenario
     * that tips a HIVE and then stages into the CELL that has become the low one gets exactly
     * that: three NECTAR that the solver spills onto the tiles on its first step. Visibly wrong
     * rather than silently wrong, which is the better failure of the two, and the reason this does
     * not quietly redirect to the raised one &mdash; a scenario exists to describe a situation,
     * including a situation nobody would set up on purpose.</p>
     */
    private static GameElement element(String label, ConfigJson json, BioBuzzField.HiveTip red,
                                       BioBuzzField.HiveTip blue, Map<String, Integer> perCell) {
        String kind = json.string("kind");
        boolean inCell = json.names().contains("cell");
        if (inCell && (json.names().contains("xMetres") || json.names().contains("yMetres"))) {
            throw new IllegalArgumentException(json.source() + ": element \"" + label + "\" gives"
                    + " both a CELL and coordinates; a ball starts in one place, and which of the"
                    + " two won would be whichever this code read first");
        }

        // A ball on the tiles rests on them, so its centre is one radius up, and the radius comes
        // from the kind. That is why the height is settled inside each branch rather than beside
        // the coordinates: the scenario says which tile to put it on, not how big it is.
        Vec3 at = inCell
                ? stagedIn(json, json.string("cell"), red, blue, perCell)
                : new Vec3(json.number("xMetres"), json.number("yMetres"), 0.0);

        if ("POLLEN".equalsIgnoreCase(kind)) {
            return GameElement.pollenAt(at.x(), at.y(),
                    inCell ? at.z() : GameElement.POLLEN_DIAMETER_METRES / 2.0);
        }
        if ("RED_NECTAR".equalsIgnoreCase(kind)) {
            return GameElement.redNectarAt(at.x(), at.y(),
                    inCell ? at.z() : GameElement.NECTAR_DIAMETER_METRES / 2.0);
        }
        if ("BLUE_NECTAR".equalsIgnoreCase(kind)) {
            return GameElement.blueNectarAt(at.x(), at.y(),
                    inCell ? at.z() : GameElement.NECTAR_DIAMETER_METRES / 2.0);
        }
        throw new IllegalArgumentException(json.source() + ": element \"" + label + "\" is a \""
                + kind + "\", which is not a BioBuzz scoring element; use POLLEN, RED_NECTAR or"
                + " BLUE_NECTAR");
    }

    /**
     * Where in a CELL to put the next ball staged in it.
     *
     * <p>Along the CELL's own width, working outward from the middle: the first sits at the
     * centre, the second and third a ball's width either side of it. Three NECTAR at one point
     * would start interpenetrating, and the solver's first act would be to fire them apart, which
     * is not a match setup &mdash; it is an explosion that empties the basket. Spread rather than
     * stacked because the manual's three sit side by side, and because a column would be a test of
     * spheres balancing on one another instead of a test of the CELL.</p>
     *
     * <p>The height is the interior's centre rather than the floor panel, so nothing here has to
     * assume a ball's radius or where gravity will settle it: the solver does that on its first
     * step, and it is already the thing under test.</p>
     */
    private static Vec3 stagedIn(ConfigJson json, String cellName, BioBuzzField.HiveTip red,
                                 BioBuzzField.HiveTip blue, Map<String, Integer> perCell) {
        Pose3d cell;
        try {
            cell = BioBuzzHive.cell(cellName, red, blue);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(json.source() + ": " + e.getMessage(), e);
        }
        Integer staged = perCell.get(cellName);
        int index = staged == null ? 0 : staged;
        perCell.put(cellName, index + 1);

        // 0, +1, -1, +2, -2 ... in units of a NECTAR's width, which is the widest thing that can
        // be staged. Five fit across the manual's 20 in opening before this runs out of CELL.
        int step = (index + 1) / 2 * (index % 2 == 0 ? -1 : 1);
        return cell.position().plus(
                cell.left().scaled(step * GameElement.NECTAR_DIAMETER_METRES));
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

    /**
     * The field this scenario describes, ready to render.
     *
     * <p>Built by asking {@link BioBuzzField} for the field and then putting this scenario's balls
     * on it, rather than assembling a scene here. There is one place that knows what the official
     * field contains &mdash; its tag clusters and its four FLOWERs &mdash; and a scenario chooses
     * only what is loose on it. Assembling a scene here instead is how a scenario came to render a
     * field with no FLOWERs in it while the default field had them.</p>
     */
    public SimulatedScene scene() {
        return BioBuzzField.scene(redHive, blueHive).withElements(elements);
    }
}
