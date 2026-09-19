package org.ngicollective.testframework.physics;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Where a pivoting structure has got to, at the instant it was asked.
 *
 * <p>A snapshot, for the same reason {@link BodyState} is one: the solver mutates its joints in
 * place and a reader wants a value that cannot change halfway through being serialised.</p>
 *
 * <p>Keyed by the structure's name rather than by an index, unlike a ball. There are two of these
 * on a field, their names are the manual's, and the scene has to look each one up to swing the
 * right basket &mdash; a position in a list would make "which HIVE tipped" depend on the order
 * somebody assembled the field in.</p>
 */
public final class PivotState {

    private final String structureName;
    private final double angleRadians;
    private final int completedSwings;

    public PivotState(String structureName, double angleRadians, int completedSwings) {
        this.structureName = structureName;
        this.angleRadians = angleRadians;
        this.completedSwings = completedSwings;
    }

    /** The {@code Structure} this belongs to: {@code "HIVE RED"}, or its blue sibling. */
    public String structureName() {
        return structureName;
    }

    /**
     * Its angle about its own pivot, in that pivot's absolute measure.
     *
     * <p>Handed to {@code SimulatedScene.tipped} to move the geometry, the tags and the scoring
     * volumes together. Continuous, and between the two stable states for most of a tip: a HIVE
     * halfway round has no tip <em>state</em>, which is the honest description of a field a robot
     * has just shot into.</p>
     */
    public double angleRadians() {
        return angleRadians;
    }

    /**
     * How many times it has travelled from one stable state to the other since the world was
     * built.
     *
     * <p>Which is a HIVE TIP, and the manual's definition of one almost word for word (&sect;10.5.1):
     * the HIVE moves from one stable state to the other, and then "the damper on the HIVE that was
     * previously not contacting the frame begins to contact the frame". A stop <em>is</em> that
     * damper, so arriving at the far one is both clauses at once.</p>
     *
     * <p>Counted here, in the thing that owns the motion, rather than inferred by a watcher
     * sampling the angle: at 50&nbsp;Hz a control cycle can miss a fast swing entirely, and a
     * counter that reads the angle twice would score a HIVE that bounced off its stop and came
     * back as two TIPs. Cumulative, never reset, because the manual counts every TIP in a MATCH
     * and each one scores again.</p>
     */
    public int completedSwings() {
        return completedSwings;
    }

    /**
     * Every pivot's angle, keyed by structure name: what {@code SimulatedScene.tipped} takes.
     *
     * <p>Here rather than at each of the three call sites that want it, because a map built by
     * hand from these two accessors is the sort of four-line loop that gets one of them wrong
     * once.</p>
     */
    public static Map<String, Double> anglesOf(List<PivotState> pivots) {
        Map<String, Double> angles = new LinkedHashMap<>();
        for (PivotState pivot : pivots) {
            angles.put(pivot.structureName(), pivot.angleRadians());
        }
        return angles;
    }

    /** And every pivot's TIP count, which is what {@code BioBuzzScore} prices at twenty apiece. */
    public static Map<String, Integer> swingsOf(List<PivotState> pivots) {
        Map<String, Integer> swings = new LinkedHashMap<>();
        for (PivotState pivot : pivots) {
            swings.put(pivot.structureName(), pivot.completedSwings());
        }
        return swings;
    }

    @Override
    public String toString() {
        return String.format("%s at %.1f\u00b0 (%d swings)", structureName,
                Math.toDegrees(angleRadians), completedSwings);
    }
}
