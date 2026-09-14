package org.ngicollective.testframework.camera;

/**
 * Draws a game element as a flat coloured disc.
 *
 * <p>Flat, not shaded. A blob detector thresholds colour and then measures the contour's area and
 * centroid, so a lit sphere's highlight and terminator would only give it something to get wrong.
 * What matters is that the disc is the right colour, in the right place, and the right size.</p>
 *
 * <p>A sphere's outline through a pinhole is an ellipse, not a circle, and off-axis it is also not
 * centred on the projected centre. Both effects are below a pixel for a 3 in ball anywhere a robot
 * can see one, so a circle at the projected centre it is &mdash; and its radius comes from the
 * projected geometry rather than a fudge factor.</p>
 */
public final class ElementRasteriser {

    /** Sub-samples per pixel axis along the disc's edge, matching {@link TagRasteriser}. */
    private static final int SAMPLES_PER_AXIS = 4;

    private ElementRasteriser() {
    }

    /**
     * Draws one element, or nothing if it is behind the camera or off the frame.
     *
     * @return whether any pixel was touched
     */
    public static boolean draw(SyntheticFrame frame, CameraView view, GameElement element) {
        Pixel centre = view.project(element.centre());
        if (centre == null) {
            return false;
        }
        double depth = view.depthOf(element.centre());
        // Scale from the focal length, the same pinhole model the tags go through: a ball of
        // radius r at depth d subtends f * r / d pixels.
        double radiusX = view.intrinsics().focalX() * element.radiusMetres() / depth;
        double radiusY = view.intrinsics().focalY() * element.radiusMetres() / depth;
        if (radiusX < 0.5 || radiusY < 0.5) {
            return false;
        }

        int left = Math.max(0, (int) Math.floor(centre.x() - radiusX));
        int right = Math.min(frame.width() - 1, (int) Math.ceil(centre.x() + radiusX));
        int top = Math.max(0, (int) Math.floor(centre.y() - radiusY));
        int bottom = Math.min(frame.height() - 1, (int) Math.ceil(centre.y() + radiusY));
        if (left > right || top > bottom) {
            return false;
        }

        int samples = SAMPLES_PER_AXIS * SAMPLES_PER_AXIS;
        double step = 1.0 / SAMPLES_PER_AXIS;
        boolean drew = false;

        for (int y = top; y <= bottom; y++) {
            for (int x = left; x <= right; x++) {
                int covered = 0;
                for (int subY = 0; subY < SAMPLES_PER_AXIS; subY++) {
                    for (int subX = 0; subX < SAMPLES_PER_AXIS; subX++) {
                        double dx = (x + (subX + 0.5) * step - centre.x()) / radiusX;
                        double dy = (y + (subY + 0.5) * step - centre.y()) / radiusY;
                        if (dx * dx + dy * dy <= 1.0) {
                            covered++;
                        }
                    }
                }
                if (covered == 0) {
                    continue;
                }
                frame.blend(x, y, element.red(), element.green(), element.blue(),
                        (double) covered / samples);
                drew = true;
            }
        }
        return drew;
    }
}
