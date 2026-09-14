package org.ngicollective.testframework.camera;

/**
 * Where one tag landed in the image: four pixel corners, or a statement that it is not there.
 *
 * <p>Corners are in the detector's order, so what the renderer drew and what a detection reports
 * are directly comparable.</p>
 */
public final class TagQuad {

    private final int id;
    private final Pixel[] corners;

    private TagQuad(int id, Pixel[] corners) {
        this.id = id;
        this.corners = corners;
    }

    static TagQuad visible(int id, Pixel[] corners) {
        return new TagQuad(id, corners);
    }

    static TagQuad hidden(int id) {
        return new TagQuad(id, null);
    }

    public int id() {
        return id;
    }

    /**
     * Whether the camera can see this tag's face at all.
     *
     * <p>False when the tag is behind the camera, edge-on, or turned away. "Turned away" is the
     * one that matters: a tag is printed on one side of a physical plate, and a simulator that
     * cheerfully rendered its back would let an OpMode lock onto a tag it could never see on a
     * real field. Off-screen is a separate question, and the rasteriser's to answer by clipping.</p>
     */
    public boolean isVisible() {
        return corners != null;
    }

    /**
     * The four corners, in the detector's order.
     *
     * @throws IllegalStateException if the tag is not visible, because there is no sensible
     *     pixel to return for a tag behind the camera and a caller that ignored
     *     {@link #isVisible()} would otherwise rasterise nonsense
     */
    public Pixel[] corners() {
        if (corners == null) {
            throw new IllegalStateException("tag " + id + " is not visible from this view");
        }
        return corners.clone();
    }

    @Override
    public String toString() {
        if (corners == null) {
            return "tag " + id + ": not visible";
        }
        return String.format("tag %d: %s %s %s %s",
                id, corners[0], corners[1], corners[2], corners[3]);
    }
}
