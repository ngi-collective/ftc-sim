package org.ngicollective.testframework.season;

import org.ngicollective.testframework.camera.GameElement;
import org.ngicollective.testframework.camera.ScoringVolume;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * What the field on the table is worth, scored as though the match ended now.
 *
 * <p>Manual &sect;10.5.1, in full: "At the end of the MATCH, any POLLEN and/or NECTAR left in an
 * upward-facing CELL will earn points for that ALLIANCE", and Table 10-2 prices that at
 * {@link #POINTS_PER_ELEMENT} apiece. Three sentences of rule, and every one of them is a way to
 * get this wrong:</p>
 *
 * <ul>
 *   <li><b>"upward-facing"</b> &mdash; a downward-facing CELL scores nothing, and in this
 *       simulator it also cannot hold anything, because its mouth points 30&deg; below level and
 *       gravity empties it. Those are two independent facts and both are tested: the rule must
 *       hold even for a scenario that stages a ball into a lowered CELL, where the solver has not
 *       yet had a chance to tip it out.</li>
 *   <li><b>"any"</b> &mdash; a blue NECTAR in a red CELL scores for <em>red</em>. Points follow
 *       the CELL, not the ball, exactly as the GARDEN and FLOWER rules do. Filtering by colour
 *       here would be a plausible-looking bug that only shows up against an opponent.</li>
 *   <li><b>"at the end of the MATCH"</b> &mdash; which this deliberately does not wait for. A
 *       running total is what a driver practising against a dashboard needs, and it is what a test
 *       asserting "autonomous scored the preloaded POLLEN" needs; both would be unanswerable from
 *       a figure that only existed after 0:00. What is reported is therefore what <em>would</em>
 *       score, and a ball knocked out of a CELL takes its two points with it.</li>
 * </ul>
 *
 * <p>Nothing here knows about the HIVE's geometry, only about which way a volume's mouth points.
 * It is handed every CELL on the field and the balls on it, so the same class scores a FLOWER or a
 * GARDEN the day those volumes exist.</p>
 */
public final class BioBuzzScore {

    /** Manual Table 10-2: two points for each POLLEN or NECTAR left in an upward-facing CELL. */
    public static final int POINTS_PER_ELEMENT = 2;

    /** Manual Table 10-2: a HIVE TIP is worth twenty, in AUTO and in TELEOP alike. */
    public static final int POINTS_PER_TIP = 20;

    private final List<Scored> scored;
    private final int redTips;
    private final int blueTips;
    private final int redPoints;
    private final int bluePoints;

    private BioBuzzScore(List<Scored> scored, int redTips, int blueTips, int redPoints,
                         int bluePoints) {
        this.scored = Collections.unmodifiableList(scored);
        this.redTips = redTips;
        this.blueTips = blueTips;
        this.redPoints = redPoints;
        this.bluePoints = bluePoints;
    }

    /**
     * Scores {@code elements} against every CELL on the field, plus the TIPs each HIVE has been
     * put through.
     *
     * <p>A volume with nothing in it is still reported, with a zero. That is the difference between
     * "this CELL is empty" and "this CELL is not in play", and a dashboard that showed them alike
     * would leave a driver unable to tell a missed shot from a HIVE tipped the wrong way.</p>
     *
     * <h2>Which way a CELL faces is read off its pose</h2>
     *
     * <p>A downward-facing CELL is dropped here rather than left out by whoever assembled the
     * list, because during a tip there is no answer to "which CELL is up" that a caller could have
     * supplied: the HIVE is halfway round. A mouth's own normal always has an answer, so the rule
     * is exactly the manual's wording &mdash; upward-facing &mdash; and it is true of a CELL
     * frozen at any angle. A CELL passing through level scores nothing for either alliance, which
     * is also the right answer: nothing can be resting in it.</p>
     *
     * @param volumes every CELL on the field, whose names must be CELL names; typically
     *     {@link BioBuzzHive#cellVolumes} or a scene's own {@code scoringVolumes()}
     * @param tipsByStructure how many completed TIPs each HIVE has, by structure name, as
     *     {@code FieldPhysics} counts them; a field nobody has tipped may pass an empty map
     */
    public static BioBuzzScore of(List<ScoringVolume> volumes, List<GameElement> elements,
                                  Map<String, Integer> tipsByStructure) {
        List<Scored> scored = new ArrayList<>(volumes.size());
        int red = 0;
        int blue = 0;
        for (ScoringVolume volume : volumes) {
            if (volume.pose().forward().z() <= 0.0) {
                continue;
            }
            List<GameElement> holding = new ArrayList<>();
            for (GameElement element : elements) {
                if (volume.contains(element.centre())) {
                    holding.add(element);
                }
            }
            boolean redAlliance = BioBuzzHive.isRedCell(volume.name());
            Scored cell = new Scored(volume.name(), redAlliance, holding);
            scored.add(cell);
            if (redAlliance) {
                red += cell.points();
            } else {
                blue += cell.points();
            }
        }

        int redTips = tips(tipsByStructure, BioBuzzHive.RED);
        int blueTips = tips(tipsByStructure, BioBuzzHive.BLUE);
        return new BioBuzzScore(scored, redTips, blueTips,
                red + redTips * POINTS_PER_TIP, blue + blueTips * POINTS_PER_TIP);
    }

    private static int tips(Map<String, Integer> tipsByStructure, String hive) {
        Integer counted = tipsByStructure.get(hive);
        return counted == null ? 0 : counted;
    }

    /** Every volume that could score, in the order given, each with what is in it. */
    public List<Scored> cells() {
        return scored;
    }

    /**
     * What one CELL is holding, by name.
     *
     * @throws IllegalArgumentException when that CELL is not one of the ones in play, because the
     *     two ways that happens &mdash; a misspelling, and asking after a CELL the HIVE has tipped
     *     face down &mdash; are both worth hearing about rather than reading as an empty basket
     */
    public Scored cell(String cellName) {
        for (Scored candidate : scored) {
            if (candidate.cellName().equals(cellName)) {
                return candidate;
            }
        }
        throw new IllegalArgumentException("\"" + cellName + "\" is not scoring; the CELLs facing"
                + " up are " + names());
    }

    public int redPoints() {
        return redPoints;
    }

    public int bluePoints() {
        return bluePoints;
    }

    /**
     * How many completed HIVE TIPs each alliance has, which is what the 20s in the totals are.
     *
     * <p>Reported beside the points because the two answer different questions: a driver watching
     * the dashboard wants to know whether the shot they just took counted, and a total that went
     * up by 20 while a CELL's contents dropped to zero is ambiguous on its own.</p>
     */
    public int redTips() {
        return redTips;
    }

    public int blueTips() {
        return blueTips;
    }

    private List<String> names() {
        List<String> names = new ArrayList<>(scored.size());
        for (Scored candidate : scored) {
            names.add(candidate.cellName());
        }
        return names;
    }

    @Override
    public String toString() {
        return "red " + redPoints + " - blue " + bluePoints + " " + scored;
    }

    /** One scoring volume and what is sitting in it. */
    public static final class Scored {

        private final String cellName;
        private final boolean red;
        private final List<GameElement> holding;

        private Scored(String cellName, boolean red, List<GameElement> holding) {
            this.cellName = cellName;
            this.red = red;
            this.holding = Collections.unmodifiableList(holding);
        }

        public String cellName() {
            return cellName;
        }

        /** Which alliance these points go to: the CELL's own, whoever put the balls in it. */
        public boolean isRed() {
            return red;
        }

        /**
         * The elements in this CELL, in the order they were given.
         *
         * <p>The elements rather than a count, because a test that cares which balls went in
         * &mdash; a preloaded POLLEN, an opponent's NECTAR &mdash; can only ask that of the balls
         * themselves, and the count is one call away.</p>
         */
        public List<GameElement> holding() {
            return holding;
        }

        public int points() {
            return holding.size() * POINTS_PER_ELEMENT;
        }

        @Override
        public String toString() {
            return cellName + ": " + holding.size() + " for " + points();
        }
    }
}
