package org.ngicollective.testframework.dashboard.protocol;

import java.util.List;

/**
 * What is standing on the field: every AprilTag the simulation knows about, every game element,
 * and every structure.
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

    /**
     * The field's own furniture: the FLOWERs, and the HIVE once it tips.
     *
     * <p>Published rather than drawn from the browser's own knowledge of the season, because the
     * geometry is a CAD measurement and retyping thirty inch-denominated numbers into TypeScript
     * is the same class of mistake as a mirrored tag &mdash; plausible on screen, wrong everywhere
     * it matters. The field's <em>surfaces</em>, its tiles and perimeter faces, are deliberately
     * not here: they are cosmetic and derivable from the three numbers in {@code sim/config}, and
     * nothing collides with them. See
     * {@code docs/adr/0004-structures-are-published-as-posed-primitives.md}.</p>
     */
    public final List<Structure> structures;

    public ScenePayload(List<Tag> tags, List<Element> elements, List<Structure> structures) {
        this.tags = tags;
        this.elements = elements;
        this.structures = structures;
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

        /**
         * The {@link Structure#name} whose pivot carries this tag, or null when it is bolted to
         * the field.
         *
         * <p>Every BioBuzz tag is stuck to a CELL of a HIVE that tips, so every one of them has
         * this. It is what lets the browser draw a tipping HIVE as one thing: the corners here are
         * where the tag is at the angle the scene was sent at, and the group it hangs in is what
         * moves. Without it a tip would have to republish sixteen tags with four corners and
         * sixty-four pattern cells each, fifty times a second, and the browser would rebuild every
         * tag texture each time.</p>
         */
        public final String attachedTo;

        public Tag(int id, String cluster, double sizeMetres, List<Corner> corners,
                   List<String> cells, String attachedTo) {
            this.id = id;
            this.cluster = cluster;
            this.sizeMetres = sizeMetres;
            this.corners = corners;
            this.cells = cells;
            this.attachedTo = attachedTo;
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

    /** One structure, as the primitives it is drawn from. */
    public static final class Structure {

        /** Which one, as the server's own {@code Structure} names it: "FLOWER RED". */
        public final String name;

        public final List<Solid> solids;

        /** The axis it turns on, or null for the great majority of a field, which is bolted down. */
        public final Pivot pivot;

        public Structure(String name, List<Solid> solids, Pivot pivot) {
            this.name = name;
            this.solids = solids;
            this.pivot = pivot;
        }
    }

    /**
     * Where a structure's axis of rotation is, and what angle its solids are drawn at.
     *
     * <p>The HIVE is the only thing on a BioBuzz field that moves without being a ball or a robot.
     * Its angle arrives separately and continuously on {@link BodiesPayload}; this is the frame
     * that angle is measured in, sent once with the geometry.</p>
     *
     * <p>{@link #angleRadians} is the angle the solids in the same message are <em>already</em>
     * at, so a live angle equal to it means "draw exactly what was sent". A browser that rotated
     * by the absolute angle instead of by the difference would draw a HIVE tipped twice over,
     * which looks very nearly right for small angles and is why this is spelled out.</p>
     */
    public static final class Pivot {

        /** A point on the axis, in the field frame, in metres. */
        public final double x;
        public final double y;
        public final double z;

        /** A unit vector along the axis, in the field frame. Field +X, for a HIVE. */
        public final double axisX;
        public final double axisY;
        public final double axisZ;

        public final double angleRadians;

        public Pivot(double x, double y, double z, double axisX, double axisY, double axisZ,
                     double angleRadians) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.axisX = axisX;
            this.axisY = axisY;
            this.axisZ = axisZ;
            this.angleRadians = angleRadians;
        }
    }

    /**
     * One posed primitive: a box or a cylinder, in one flat colour.
     *
     * <h2>The conventions are the contract</h2>
     *
     * <p>{@link #shape} discriminates, and <b>every numeric field is always present</b> &mdash;
     * the ones the other shape does not use are zero. A box reads {@link #lengthX},
     * {@link #lengthY} and {@link #lengthZ}; a cylinder reads {@link #radiusMetres} and
     * {@link #lengthMetres}.</p>
     *
     * <p>{@code x, y, z} is the primitive's <em>centre</em> in the field frame. The three angles
     * orient it exactly as {@code Pose3d} does &mdash; yaw about field +Z, then pitch, positive
     * upward, then roll about the solid's own nose.</p>
     *
     * <p><b>A cylinder's axis is its local +Z, and a cylinder is an open-ended shell.</b> Three.js
     * builds cylinders along Y, so the browser corrects for that inside the solid's own frame; a
     * cylinder with all three angles zero is therefore a ring lying flat on the tiles. The shell
     * has no caps on purpose: every cylinder on this field is a ring or a pipe, and a cap would
     * hide the POLLEN sitting inside a FLOWER, which is the thing anyone looking at one is looking
     * for.</p>
     */
    public static final class Solid {

        /** {@code "box"} or {@code "cylinder"}. */
        public final String shape;

        public final double x;
        public final double y;
        public final double z;

        public final double yawDegrees;
        public final double pitchDegrees;
        public final double rollDegrees;

        public final double lengthX;
        public final double lengthY;
        public final double lengthZ;

        public final double radiusMetres;
        public final double lengthMetres;

        public final int red;
        public final int green;
        public final int blue;

        public Solid(String shape, double x, double y, double z,
                     double yawDegrees, double pitchDegrees, double rollDegrees,
                     double lengthX, double lengthY, double lengthZ,
                     double radiusMetres, double lengthMetres,
                     int red, int green, int blue) {
            this.shape = shape;
            this.x = x;
            this.y = y;
            this.z = z;
            this.yawDegrees = yawDegrees;
            this.pitchDegrees = pitchDegrees;
            this.rollDegrees = rollDegrees;
            this.lengthX = lengthX;
            this.lengthY = lengthY;
            this.lengthZ = lengthZ;
            this.radiusMetres = radiusMetres;
            this.lengthMetres = lengthMetres;
            this.red = red;
            this.green = green;
            this.blue = blue;
        }
    }
}
