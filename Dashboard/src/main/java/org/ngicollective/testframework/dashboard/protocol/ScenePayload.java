package org.ngicollective.testframework.dashboard.protocol;

import java.util.List;

/**
 * What is standing on the field: every AprilTag the simulation knows about, and every game
 * element.
 *
 * <p>Sent so that the Dashboard Field View can draw the same world the Dashboard Camera View
 * renders. Two views of one field that disagree are worse than one view: a tag drawn where the
 * camera cannot see it turns "the OpMode is wrong" into "one of the two pictures is wrong, and I
 * do not know which". So both read from the server's scene, and nothing about the field's contents
 * is hard-coded in the web app.</p>
 *
 * <p>Geometry is in the FTC field frame &mdash; origin at field centre, metres, +Z up &mdash; like
 * every other payload here. The browser converts to Three.js' Y-up frame at draw time.</p>
 *
 * <h2>Corner order and cell orientation are the contract</h2>
 *
 * <p>Both are defined against <b>a viewer looking at the tag's printed face</b>, because that is
 * the only reference frame a person can check a rendering against by looking at it.
 * {@link Tag#corners} runs [top-left, top-right, bottom-right, bottom-left] and {@link Tag#cells}
 * has row 0 as that viewer's top row and column 0 as their left column. The browser maps the
 * pattern straight onto the quad and has no way to notice a different order.</p>
 *
 * <p>Getting either backwards mirrors the tag, and this is the failure mode to fear: a mirrored
 * tag36h11 pattern is mostly not a valid codeword, so the field view shows a tag that looks
 * entirely plausible while the detector reports nothing. The symptom reads as "vision is broken"
 * rather than "the picture is inside out". The server derives both from
 * {@code org.ngicollective.testframework.camera.FieldTag}'s documented axes and from
 * {@code Tag36h11.isWhite}, so there is one derivation to check rather than two to reconcile.</p>
 */
public final class ScenePayload {

    public final List<Tag> tags;
    public final List<Element> elements;

    public ScenePayload(List<Tag> tags, List<Element> elements) {
        this.tags = tags;
        this.elements = elements;
    }

    /** One AprilTag, placed and spelled out cell by cell. */
    public static final class Tag {

        public final int id;

        /** The owning cluster's name, matching what a detection reports in telemetry. */
        public final String cluster;

        /** Edge length of the black square, border included. */
        public final double sizeMetres;

        /** [top-left, top-right, bottom-right, bottom-left], as seen by a viewer facing the tag. */
        public final List<Corner> corners;

        /**
         * The black square as {@code Tag36h11.CELLS_ACROSS} rows of that many characters,
         * {@code 'B'} black and {@code 'W'} white, row 0 the viewer's top and column 0 their left.
         *
         * <p>Sent as a picture rather than as a codeword so the browser never carries a second
         * copy of the family's bit tables. A second copy could only ever drift from the one the
         * renderer draws from, and a tag that differs between the two views is exactly what this
         * envelope exists to prevent.</p>
         */
        public final List<String> cells;

        public Tag(int id, String cluster, double sizeMetres, List<Corner> corners,
                   List<String> cells) {
            this.id = id;
            this.cluster = cluster;
            this.sizeMetres = sizeMetres;
            this.corners = corners;
            this.cells = cells;
        }
    }

    /** A point in the field frame, in metres. */
    public static final class Corner {

        public final double x;
        public final double y;
        public final double z;

        public Corner(double x, double y, double z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }
    }

    /** A game element: a coloured sphere at a field-frame centre. */
    public static final class Element {

        /**
         * This element's place in {@link ScenePayload#elements}, and its identity in
         * {@link BodiesPayload}.
         *
         * <p>Sent explicitly rather than left as the array index it currently equals, because the
         * two payloads arrive on different cadences: a browser that keyed balls by array position
         * would silently repaint one ball with another's colour the first time an element is added
         * or removed mid-session.</p>
         */
        public final int id;

        public final String name;

        public final double x;
        public final double y;
        public final double z;
        public final double radiusMetres;

        /** The element's own colour, so POLLEN and NECTAR are told apart without a lookup table. */
        public final int red;
        public final int green;
        public final int blue;

        public Element(int id, String name, double x, double y, double z, double radiusMetres,
                       int red, int green, int blue) {
            this.id = id;
            this.name = name;
            this.x = x;
            this.y = y;
            this.z = z;
            this.radiusMetres = radiusMetres;
            this.red = red;
            this.green = green;
            this.blue = blue;
        }
    }
}
