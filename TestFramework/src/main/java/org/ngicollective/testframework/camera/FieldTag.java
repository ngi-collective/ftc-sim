package org.ngicollective.testframework.camera;

/**
 * One AprilTag sitting somewhere on the field: its id, how big it is, and which way it faces.
 *
 * <h2>The tag frame, and why it is the opposite of the obvious guess</h2>
 *
 * <p>The SDK solves a tag's pose by matching the detector's four reported corners against four
 * ideal points in the tag's own frame, in this order
 * ({@code AprilTagProcessorImpl.poseFromTrapezoid}):</p>
 *
 * <pre>
 *   corners[0] &harr; (-s/2, +s/2, 0)    corners[1] &harr; (+s/2, +s/2, 0)
 *   corners[3] &harr; (-s/2, -s/2, 0)    corners[2] &harr; (+s/2, -s/2, 0)
 * </pre>
 *
 * <p>Feeding a canonically-oriented tag36h11 image to the apriltag library shows where those
 * corners actually land in the image:</p>
 *
 * <pre>
 *   corners[0] = top-RIGHT     corners[1] = top-LEFT
 *   corners[3] = bottom-right  corners[2] = bottom-left
 * </pre>
 *
 * <p>So the corner at <em>positive</em> tag x appears at the image's <em>left</em>. The tag's +X
 * axis points to the left as seen by whoever is looking at it, +Y points up, and therefore +Z
 * points <em>into</em> the surface the tag is stuck to. The visible face normal is
 * <b>-Z</b>.</p>
 *
 * <p>That is not a detail to rediscover later. Get it backwards and every tag renders mirrored;
 * mirrored tag36h11 bit patterns are mostly not valid codewords, so the symptom is not a wrong
 * pose but zero detections, which reads as "the renderer is broken" rather than "the renderer is
 * inside out". The same convention is visible from a second direction in the SDK's own field data:
 * CenterStage tag 1's quaternion puts its +Z at {@code (0.866, 0, -0.5)}, pointing into the
 * backdrop it is taped to.</p>
 *
 * <p>This class therefore stores the pose with {@link Pose3d#forward()} as the direction the tag
 * <em>faces</em> &mdash; the way a human would describe it, and the way a scenario file is written
 * &mdash; and derives the SDK's axes from it.</p>
 */
public final class FieldTag {

    private final int id;
    private final double size;
    private final Pose3d pose;

    private final Vec3 tagX;
    private final Vec3 tagY;

    /**
     * @param id   the tag's id, which must exist in the library the detector was built with
     * @param sizeMetres the length of one edge of the black square
     * @param pose the tag's centre, with {@link Pose3d#forward()} pointing the way it faces
     */
    public FieldTag(int id, double sizeMetres, Pose3d pose) {
        if (!(sizeMetres > 0.0)) {
            throw new IllegalArgumentException(
                    "tag " + id + " must have a positive size; got " + sizeMetres + "m");
        }
        this.id = id;
        this.size = sizeMetres;
        this.pose = pose;
        // +X to the viewer's left, +Y up: see the class comment. Derived once; a tag is placed per
        // scenario and read per frame.
        this.tagX = pose.left().scaled(-1.0);
        this.tagY = pose.up();
    }

    public int id() {
        return id;
    }

    /** Edge length of the black square, in metres. */
    public double sizeMetres() {
        return size;
    }

    /** The tag's centre and facing. */
    public Pose3d pose() {
        return pose;
    }

    /** Unit vector along the tag frame's +X: to the left, as seen by a viewer facing the tag. */
    public Vec3 tagX() {
        return tagX;
    }

    /** Unit vector along the tag frame's +Y: up, as seen by a viewer facing the tag. */
    public Vec3 tagY() {
        return tagY;
    }

    /** Unit vector out of the tag's printed face, which is the tag frame's -Z. */
    public Vec3 visibleNormal() {
        return pose.forward();
    }

    /**
     * The four corners in the field frame, in the order the detector reports them.
     *
     * <p>Index order is the SDK's, so a rendered quad and a detected quad can be compared corner
     * for corner without a translation table in between.</p>
     */
    public Vec3[] corners() {
        double half = size / 2.0;
        Vec3 right = tagX.scaled(half);
        Vec3 top = tagY.scaled(half);
        Vec3 centre = pose.position();
        return new Vec3[] {
                centre.minus(right).plus(top),
                centre.plus(right).plus(top),
                centre.plus(right).minus(top),
                centre.minus(right).minus(top),
        };
    }

    @Override
    public String toString() {
        return String.format("tag %d (%.1fmm) at %s", id, size * 1000.0, pose);
    }
}
