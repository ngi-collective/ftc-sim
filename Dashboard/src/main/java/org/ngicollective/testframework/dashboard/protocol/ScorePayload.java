package org.ngicollective.testframework.dashboard.protocol;

import java.util.List;

/**
 * What the field is worth right now: the manual's CELL score, as though the match ended this tick.
 *
 * <p>In the connect greeting, which {@link BodiesPayload} deliberately is not. The difference is
 * that a browser can work out where the balls are from the scene it is greeted with, and cannot
 * work out a score from anything on this socket at all: which CELL is facing up comes from a
 * scenario file, the interior of a CELL is CAD the browser has never been given, and the point
 * values are the game manual's. All of that stays in Java, and this is the answer it sends.</p>
 *
 * <p>Then again on every OpMode init, every scenario load, and every control cycle where the
 * numbers changed &mdash; which is rare, because a score changes when a ball crosses a CELL's
 * mouth and not while it rolls about the field. Sending it at the pose's 50 Hz would be fifty
 * identical messages a second for the entire match.</p>
 */
public final class ScorePayload {

    /**
     * Alliance totals: the sum of {@link Cell#points} over that alliance's CELLs, plus twenty for
     * each of its HIVE TIPs.
     */
    public final int redPoints;
    public final int bluePoints;

    /**
     * The upward-facing CELLs, red's first.
     *
     * <p>Only those: a downward-facing CELL cannot score (manual &sect;10.5.1), so it is absent
     * rather than present with a zero. A browser drawing this must not infer the field has no HIVE
     * from a short list &mdash; two is the whole field, and while a HIVE is tipping there is
     * legitimately only one.</p>
     */
    public final List<Cell> cells;

    /**
     * How many completed HIVE TIPs each alliance has.
     *
     * <p>Sent beside the totals because a total on its own is ambiguous at exactly the moment a
     * driver cares about: a TIP empties the CELL that earned it, so six POLLEN worth 12 becoming
     * one 20-point TIP is a net gain of 8 and a CELL that has gone to zero. Cumulative over the
     * match, as the manual counts them &mdash; each TIP scores again.</p>
     */
    public final int redTips;
    public final int blueTips;

    public ScorePayload(int redPoints, int bluePoints, List<Cell> cells, int redTips,
                        int blueTips) {
        this.redPoints = redPoints;
        this.bluePoints = bluePoints;
        this.cells = cells;
        this.redTips = redTips;
        this.blueTips = blueTips;
    }

    /** One CELL in play: which it is, whose it is, and what is in it. */
    public static final class Cell {

        /** The CELL's name: {@code RED SCORING}, {@code RED AUDIENCE} and their blue siblings. */
        public final String cell;

        /**
         * Which alliance these points go to.
         *
         * <p>Sent rather than left to be parsed out of {@link #cell}, so the browser never has to
         * know that the names happen to begin with the colour. Points follow the CELL and not the
         * ball in it, so this is the alliance that owns the basket.</p>
         */
        public final Alliance alliance;

        /** How many POLLEN and NECTAR are in it, of either alliance's colour. */
        public final int holding;

        /** {@code holding} times the manual's two points apiece. */
        public final int points;

        public Cell(String cell, Alliance alliance, int holding, int points) {
            this.cell = cell;
            this.alliance = alliance;
            this.holding = holding;
            this.points = points;
        }
    }
}
