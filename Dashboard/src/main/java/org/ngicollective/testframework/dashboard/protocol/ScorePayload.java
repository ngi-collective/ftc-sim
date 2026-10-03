package org.ngicollective.testframework.dashboard.protocol;

import java.util.List;

/**
 * What the field is worth right now, as the robot's season scores it, as though the match ended
 * this tick.
 *
 * <p>In the connect greeting, which {@link BodiesPayload} deliberately is not. The difference is
 * that a browser can work out where the balls are from the scene it is greeted with, and cannot
 * work out a score from anything on this socket at all: which volume is facing up comes from a
 * scenario file, the interior of a volume is CAD the browser has never been given, and the point
 * values are the game manual's. All of that stays in Java, and this is the answer it sends.</p>
 *
 * <p>Season-neutral on the wire. A browser draws the totals, the volumes and the tallies by the
 * names it is given, and never learns what a CELL or a TIP is, so a new season's game needs no
 * change to the page.</p>
 *
 * <p>Then again on every OpMode init, every scenario load, and every control cycle where the
 * numbers changed &mdash; which is rare, because a score changes when a ball crosses a volume's
 * edge and not while it rolls about the field. Sending it at the pose's 50 Hz would be fifty
 * identical messages a second for the entire match.</p>
 */
public final class ScorePayload {

    /** Alliance totals: that alliance's volumes plus its tallies. */
    public final int redPoints;
    public final int bluePoints;

    /**
     * The volumes that can score at the moment, in the season's order.
     *
     * <p>Only those: BioBuzz's downward-facing CELL cannot score (manual &sect;10.5.1), so it is
     * absent rather than present with a zero. A browser drawing this must not infer the field has
     * nowhere to score from a short list &mdash; while a HIVE is tipping there is legitimately
     * only one.</p>
     */
    public final List<Volume> volumes;

    /**
     * Things the season counts that are not elements in a place, each alliance's separately and
     * present even at zero.
     *
     * <p>Sent beside the totals because a total on its own is ambiguous at exactly the moment a
     * driver cares about: a BioBuzz TIP empties the CELL that earned it, so six POLLEN worth 12
     * becoming one 20-point TIP is a net gain of 8 and a CELL that has gone to zero.</p>
     */
    public final List<Tally> tallies;

    public ScorePayload(int redPoints, int bluePoints, List<Volume> volumes,
                        List<Tally> tallies) {
        this.redPoints = redPoints;
        this.bluePoints = bluePoints;
        this.volumes = volumes;
        this.tallies = tallies;
    }

    /** One scoring volume in play: which it is, whose it is, and what is in it. */
    public static final class Volume {

        /** The volume's name, as the season gives it: {@code RED AUDIENCE}. */
        public final String name;

        /**
         * Which alliance these points go to.
         *
         * <p>Sent rather than left to be parsed out of {@link #name}, so the browser never has to
         * know that the names happen to begin with the colour. Points follow the volume and not
         * the ball in it, so this is the alliance that owns the basket.</p>
         */
        public final Alliance alliance;

        /** How many elements are in it, of either alliance's colour. */
        public final int holding;

        /** What those elements are worth. */
        public final int points;

        public Volume(String name, Alliance alliance, int holding, int points) {
            this.name = name;
            this.alliance = alliance;
            this.holding = holding;
            this.points = points;
        }
    }

    /** One counted thing for one alliance: {@code TIP}, twice, for 40. */
    public static final class Tally {

        /** What is counted, singular, in the manual's word: {@code TIP}. */
        public final String name;

        public final Alliance alliance;

        /** How many so far; cumulative over the match, as the manual counts them. */
        public final int count;

        /** What all of them are worth together. */
        public final int points;

        public Tally(String name, Alliance alliance, int count, int points) {
            this.name = name;
            this.alliance = alliance;
            this.count = count;
            this.points = points;
        }
    }
}
