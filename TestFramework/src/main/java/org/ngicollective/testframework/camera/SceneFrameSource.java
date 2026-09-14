package org.ngicollective.testframework.camera;

import org.ngicollective.testframework.sim.Pose2d;

/**
 * Frames rendered from a scene, as seen by a camera on a moving robot.
 *
 * <p>Reads the robot's pose at render time rather than holding a copy, so the view follows the
 * simulated robot as it drives. The scene can be swapped between frames, which is what makes a
 * HIVE tipping mid-run expressible: the camera does not need to know it happened.</p>
 */
public final class SceneFrameSource implements FrameSource {

    private final PoseSource robotPose;

    private volatile SimulatedScene scene;
    private volatile SimulatedCamera camera;

    /** Where the robot is when a frame is taken. */
    public interface PoseSource {
        Pose2d pose();
    }

    public SceneFrameSource(SimulatedScene scene, SimulatedCamera camera, PoseSource robotPose) {
        this.scene = scene;
        this.camera = camera;
        this.robotPose = robotPose;
    }

    /** A still camera at the field origin, for tests that do not need a robot. */
    public SceneFrameSource(SimulatedScene scene, SimulatedCamera camera) {
        this(scene, camera, new PoseSource() {
            @Override
            public Pose2d pose() {
                return Pose2d.ORIGIN;
            }
        });
    }

    /** The scene being rendered. */
    public SimulatedScene scene() {
        return scene;
    }

    /** Swaps in a new scene, taking effect on the next frame. */
    public void setScene(SimulatedScene scene) {
        this.scene = scene;
    }

    /** The camera's optics and mount. */
    public SimulatedCamera camera() {
        return camera;
    }

    /**
     * Replaces the camera, keeping the same scene.
     *
     * <p>This is how the real calibration reaches the renderer. A camera's resolution is not known
     * until an OpMode starts streaming, and the intrinsics the pose solver will use come from the
     * SDK's calibration table for that resolution; the plumbing sets them here so that the frames
     * are drawn through the very lens the solver inverts.</p>
     */
    public void setCamera(SimulatedCamera camera) {
        this.camera = camera;
    }

    @Override
    public void render(SyntheticFrame frame) {
        SimulatedCamera current = camera;
        // The lens does not change when an OpMode picks a different resolution: the field of view
        // is preserved and only the focal length in pixels scales. resizedTo returns the same
        // instance when nothing changes, so the common case allocates nothing.
        CameraIntrinsics lens =
                current.intrinsics().resizedTo(frame.width(), frame.height());
        CameraView view = new CameraView(lens, current.mount().toFieldFrame(robotPose.pose()));
        scene.renderInto(frame, view);
    }
}
