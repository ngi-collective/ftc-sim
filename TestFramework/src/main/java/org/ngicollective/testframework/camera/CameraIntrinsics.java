package org.ngicollective.testframework.camera;

/**
 * A camera's pinhole model: how a direction in space becomes a pixel.
 *
 * <p>Focal lengths and the principal point, in pixels, with no distortion terms. Real lenses
 * distort, and modelling that would make the simulated image prettier without making it more
 * useful: what is under test is an OpMode's reaction to a detection, not the detector's ability to
 * undistort. The one thing that matters is that the model the renderer projects <em>through</em>
 * is the same model the SDK's pose solver is handed &mdash; disagree on focal length and every
 * simulated tag sits at the wrong range, consistently and invisibly.</p>
 */
public final class CameraIntrinsics {

    /**
     * Focal length as a fraction of frame width for a 60&deg; horizontal field of view, which is
     * typical of the USB webcams on a dev desk.
     *
     * <p>This is the nominal lens a simulated camera carries before an OpMode picks a resolution
     * and the SDK's own calibration replaces it, and the one the plain-JVM tests render through.
     * {@link #approximate} is asserted to agree with {@link #fromHorizontalFieldOfView} so that
     * the two ways of saying "an ordinary webcam" cannot drift apart.</p>
     */
    private static final double DEFAULT_FOCAL_LENGTH_RATIO = 0.866;

    private final int width;
    private final int height;
    private final double focalX;
    private final double focalY;
    private final double centreX;
    private final double centreY;

    private CameraIntrinsics(int width, int height, double focalX, double focalY,
                             double centreX, double centreY) {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException(
                    "a frame must have a positive size; got " + width + "x" + height);
        }
        if (!(focalX > 0.0) || !(focalY > 0.0)) {
            throw new IllegalArgumentException(
                    "focal lengths must be positive; got fx=" + focalX + " fy=" + focalY);
        }
        this.width = width;
        this.height = height;
        this.focalX = focalX;
        this.focalY = focalY;
        this.centreX = centreX;
        this.centreY = centreY;
    }

    /** Square pixels, principal point at the frame centre, focal length given directly. */
    public static CameraIntrinsics of(int width, int height, double focalLengthPixels) {
        return new CameraIntrinsics(width, height, focalLengthPixels, focalLengthPixels,
                width / 2.0, height / 2.0);
    }

    /**
     * Every parameter given explicitly, which is how a measured calibration arrives.
     *
     * <p>Real calibrations do not have their principal point exactly at the frame centre, and
     * rounding it there would shift every projected tag by a pixel or two.</p>
     */
    public static CameraIntrinsics of(int width, int height, double focalX, double focalY,
                                      double centreX, double centreY) {
        return new CameraIntrinsics(width, height, focalX, focalY, centreX, centreY);
    }

    /** Square pixels, principal point at the frame centre, focal length from the horizontal FOV. */
    public static CameraIntrinsics fromHorizontalFieldOfView(int width, int height,
                                                             double fovDegrees) {
        if (!(fovDegrees > 0.0) || fovDegrees >= 180.0) {
            throw new IllegalArgumentException(
                    "a field of view must be in (0, 180) degrees; got " + fovDegrees);
        }
        double focal = (width / 2.0) / Math.tan(Math.toRadians(fovDegrees) / 2.0);
        return of(width, height, focal);
    }

    /** An ordinary webcam of the given frame size: 60&deg; horizontal, no distortion. */
    public static CameraIntrinsics approximate(int width, int height) {
        return of(width, height, DEFAULT_FOCAL_LENGTH_RATIO * width);
    }

    /** The horizontal field of view these intrinsics describe, in degrees. */
    public double horizontalFieldOfViewDegrees() {
        return Math.toDegrees(2.0 * Math.atan((width / 2.0) / focalX));
    }

    /** The vertical field of view these intrinsics describe, in degrees. */
    public double verticalFieldOfViewDegrees() {
        return Math.toDegrees(2.0 * Math.atan((height / 2.0) / focalY));
    }

    /**
     * The same lens at a different frame size.
     *
     * <p>An OpMode chooses its own resolution, and a real webcam obliges: the lens does not
     * change, so the field of view is preserved and the focal length in <em>pixels</em> scales
     * with the frame. Getting this wrong is the kind of mistake that never throws &mdash; every
     * tag would simply be reported at the wrong range, in proportion.</p>
     *
     * <p>Square pixels are assumed, which is true of every webcam a team is likely to own and is
     * what the SDK's own approximate calibration assumes too.</p>
     */
    public CameraIntrinsics resizedTo(int newWidth, int newHeight) {
        if (newWidth == width && newHeight == height) {
            return this;
        }
        return fromHorizontalFieldOfView(newWidth, newHeight, horizontalFieldOfViewDegrees());
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    /** Focal length in pixels along the image's x axis. */
    public double focalX() {
        return focalX;
    }

    /** Focal length in pixels along the image's y axis. */
    public double focalY() {
        return focalY;
    }

    /** Principal point x, in pixels from the left edge. */
    public double centreX() {
        return centreX;
    }

    /** Principal point y, in pixels from the top edge. */
    public double centreY() {
        return centreY;
    }

    @Override
    public String toString() {
        return String.format("%dx%d fx=%.1f fy=%.1f c=(%.1f, %.1f)",
                width, height, focalX, focalY, centreX, centreY);
    }
}
