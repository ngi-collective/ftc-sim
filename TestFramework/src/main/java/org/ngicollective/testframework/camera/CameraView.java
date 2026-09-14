package org.ngicollective.testframework.camera;

/**
 * What a camera at one particular place on the field can see: the projection from field
 * coordinates to pixels.
 *
 * <h2>The camera reading of {@link Pose3d}</h2>
 *
 * <p>OpenCV's camera frame is x right, y <em>down</em>, z along the optical axis, and that is the
 * frame the SDK's pose solver works in, so it is the frame used here:</p>
 *
 * <pre>
 *   camera x (right) = -pose.left()
 *   camera y (down)  = -pose.up()
 *   camera z (axis)  =  pose.forward()
 * </pre>
 *
 * <p>A point projects to {@code (cx + fx * X/Z, cy + fy * Y/Z)}, undistorted, which is the same
 * arithmetic {@code solvePnP} inverts. Nothing here is approximate except the absence of lens
 * distortion.</p>
 *
 * <p>Cheap to build: one per frame, holding three cached basis vectors. Deliberately immutable, so
 * a view captured for an assertion cannot be invalidated by the robot moving underneath it.</p>
 */
public final class CameraView {

    /**
     * How close to the image plane a point may be and still be projected.
     *
     * <p>A point at or behind the plane has no projection &mdash; division by a vanishing depth
     * runs off to infinity &mdash; and a tag one millimetre from the lens is not a case any real
     * pipeline has to serve.
     */
    private static final double MIN_RANGE_METRES = 1e-3;

    private final CameraIntrinsics intrinsics;
    private final Pose3d pose;

    private final Vec3 right;
    private final Vec3 down;
    private final Vec3 axis;

    CameraView(CameraIntrinsics intrinsics, Pose3d poseOnField) {
        this.intrinsics = intrinsics;
        this.pose = poseOnField;
        this.right = poseOnField.left().scaled(-1.0);
        this.down = poseOnField.up().scaled(-1.0);
        this.axis = poseOnField.forward();
    }

    /** The camera's pose in the field frame, mount and robot pose already composed. */
    public Pose3d pose() {
        return pose;
    }

    public CameraIntrinsics intrinsics() {
        return intrinsics;
    }

    /**
     * How far in front of the camera a field point is, in metres along the optical axis.
     *
     * <p>Negative or near zero means the point is level with or behind the lens. This is also the
     * depth a rasteriser needs to decide which of two overlapping things is nearer.</p>
     */
    public double depthOf(Vec3 fieldPoint) {
        return fieldPoint.minus(pose.position()).dot(axis);
    }

    /**
     * Where a field point lands in the image, or {@code null} if it is not in front of the camera.
     *
     * <p>Null rather than an exception because "behind the camera" is the ordinary case, not a
     * mistake: most of the field is behind the camera most of the time. The returned pixel may lie
     * outside the frame; clipping is the rasteriser's job, and a caller that needs the true
     * off-frame corner would be poorly served by a clamped one.</p>
     */
    public Pixel project(Vec3 fieldPoint) {
        return projectFromCameraFrame(inCameraFrame(fieldPoint));
    }

    /**
     * A field point in the camera's own frame: x right, y down, z along the optical axis.
     *
     * <p>Handed out so that geometry which cannot be projected as it stands &mdash; a floor plane
     * running back past the lens &mdash; can be clipped in the frame the near plane is defined in
     * before it is projected at all. See {@link SurfaceRasteriser}.</p>
     */
    public Vec3 inCameraFrame(Vec3 fieldPoint) {
        Vec3 relative = fieldPoint.minus(pose.position());
        return new Vec3(relative.dot(right), relative.dot(down), relative.dot(axis));
    }

    /**
     * Where a point already in the camera frame lands in the image, or {@code null} if it is not
     * far enough in front of the lens to have a projection.
     */
    public Pixel projectFromCameraFrame(Vec3 cameraPoint) {
        if (cameraPoint.z() < MIN_RANGE_METRES) {
            return null;
        }
        return new Pixel(
                intrinsics.centreX() + intrinsics.focalX() * cameraPoint.x() / cameraPoint.z(),
                intrinsics.centreY() + intrinsics.focalY() * cameraPoint.y() / cameraPoint.z());
    }

    /**
     * How far in front of the lens a point must be to have a projection, in metres.
     *
     * <p>The plane a rasteriser clips against: a polygon trimmed to this plane projects to a
     * polygon, whereas one that straddles it does not.</p>
     */
    public double nearPlaneMetres() {
        return MIN_RANGE_METRES;
    }

    /**
     * Where a tag lands in the image, or a hidden quad if the camera cannot see its face.
     *
     * <p>A tag counts as visible when the camera sits on the printed side of it and all four
     * corners are in front of the lens. Partially-behind tags are reported hidden rather than
     * clipped, because a quad that straddles the image plane has no valid homography, and
     * pretending otherwise would smear a tag across the whole frame.</p>
     */
    public TagQuad project(FieldTag tag) {
        // On the printed side? The dot product goes to zero as the tag turns edge-on, and negative
        // once the camera is behind it.
        Vec3 towardCamera = pose.position().minus(tag.pose().position());
        if (towardCamera.dot(tag.visibleNormal()) <= 0.0) {
            return TagQuad.hidden(tag.id());
        }

        Vec3[] corners = tag.corners();
        Pixel[] projected = new Pixel[corners.length];
        for (int i = 0; i < corners.length; i++) {
            projected[i] = project(corners[i]);
            if (projected[i] == null) {
                return TagQuad.hidden(tag.id());
            }
        }
        return TagQuad.visible(tag.id(), projected);
    }

    /**
     * Whether any part of a tag's bounding box falls inside the frame.
     *
     * <p>Conservative on purpose: a thin quad lying diagonally across a corner can pass this test
     * without a single pixel on screen. That is the right direction to be wrong in for culling,
     * and it keeps the rasteriser and the tests asking the same question in one place rather than
     * two.</p>
     *
     * <p>Distinct from {@link TagQuad#isVisible()}, which asks whether the camera is on the
     * printed side of the tag at all. A tag can be perfectly visible and still be nowhere near
     * the frame &mdash; which is exactly what a BioBuzz camera that forgot to aim up would
     * report.</p>
     */
    public boolean boundsOverlapFrame(TagQuad quad) {
        if (!quad.isVisible()) {
            return false;
        }
        double minX = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE;
        double minY = Double.MAX_VALUE;
        double maxY = -Double.MAX_VALUE;
        for (Pixel corner : quad.corners()) {
            minX = Math.min(minX, corner.x());
            maxX = Math.max(maxX, corner.x());
            minY = Math.min(minY, corner.y());
            maxY = Math.max(maxY, corner.y());
        }
        return maxX >= 0.0 && minX <= intrinsics.width()
                && maxY >= 0.0 && minY <= intrinsics.height();
    }
}
