package org.ngicollective.ftcsim.sim;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * What a field is worth right now, as a season counts it, in terms that need no knowledge of the
 * game to display.
 *
 * <p>Two kinds of line. A <em>volume</em> is a region an element scores in, with how many it is
 * holding: a CELL, a GARDEN, a goal. A <em>tally</em> is something counted that is not an element
 * in a place, with the points each one earns: a HIVE TIP, a parked robot. The alliance totals
 * already include both, and the lines are there so a reader can see where a total came from &mdash;
 * a total that rose by eight while a volume went to zero is unreadable without the tally that
 * explains it.</p>
 *
 * <p>Immutable. The lists are copied on the way in.</p>
 */
public final class FieldScore {

    /** Which side a line counts for. */
    public enum Alliance { RED, BLUE }

    private final int redPoints;
    private final int bluePoints;
    private final List<Volume> volumes;
    private final List<Tally> tallies;

    public FieldScore(int redPoints, int bluePoints, List<Volume> volumes, List<Tally> tallies) {
        this.redPoints = redPoints;
        this.bluePoints = bluePoints;
        this.volumes = Collections.unmodifiableList(new ArrayList<>(volumes));
        this.tallies = Collections.unmodifiableList(new ArrayList<>(tallies));
    }

    public int redPoints() {
        return redPoints;
    }

    public int bluePoints() {
        return bluePoints;
    }

    /** Every volume that can score at the moment, in the season's order. */
    public List<Volume> volumes() {
        return volumes;
    }

    /** Every tally the season keeps, including those still at zero. */
    public List<Tally> tallies() {
        return tallies;
    }

    /** A region that elements score in, and what it holds now. */
    public static final class Volume {

        private final String name;
        private final Alliance alliance;
        private final int holding;
        private final int points;

        public Volume(String name, Alliance alliance, int holding, int points) {
            this.name = name;
            this.alliance = alliance;
            this.holding = holding;
            this.points = points;
        }

        /** The volume's own name, as the scene's {@code ScoringVolume} gives it. */
        public String name() {
            return name;
        }

        public Alliance alliance() {
            return alliance;
        }

        /** How many elements are inside it. */
        public int holding() {
            return holding;
        }

        /** What those elements are worth. */
        public int points() {
            return points;
        }
    }

    /** Something counted that is not an element in a place. */
    public static final class Tally {

        private final String name;
        private final Alliance alliance;
        private final int count;
        private final int points;

        public Tally(String name, Alliance alliance, int count, int points) {
            this.name = name;
            this.alliance = alliance;
            this.count = count;
            this.points = points;
        }

        /** What is counted, in the manual's own word and in the singular: {@code "TIP"}. */
        public String name() {
            return name;
        }

        public Alliance alliance() {
            return alliance;
        }

        public int count() {
            return count;
        }

        /** What all {@link #count()} of them are worth together. */
        public int points() {
            return points;
        }
    }
}
