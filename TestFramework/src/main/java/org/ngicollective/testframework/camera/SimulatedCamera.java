package org.ngicollective.testframework.camera;

import org.ngicollective.testframework.sim.Pose2d;

/**
 * A camera bolted to the robot: its optics, and where on the robot it is bolted.
 *
 * <p>The mount is given in the robot frame &mdash; +X out the nose, +Y to the robot's left, +Z up,
 * origin on the floor at the centre of the footprint &mdash; because that is the frame a student
 * can hold a ruler against. Turning that into a field pose is {@link #viewFrom}'s job.</p>
 *
 * <p>Six degrees of freedom, not two. On BioBuzz the AprilTag clusters hang under the CELLs facing
 * the floor, tilted thirty degrees, three to four feet up, so a camera that could only yaw would
 * never see one: the mount has to be able to aim up. Pitch is positive upward for exactly that
 * reason.</p>
 */
public final class SimulatedCamera {

    private final String name;
    private final CameraIntrinsics intrinsics;
    private final Pose3d mount;

    /**
     * @param name       the hardware name the OpMode looks the camera up under, so that a
     *                   mismatch between configuration and OpMode is a legible failure
     * @param intrinsics the pinhole model, which must match the calibration handed to the detector
     * @param mount      the camera's pose in the robot frame
     */
    public SimulatedCamera(String name, CameraIntrinsics intrinsics, Pose3d mount) {
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException("a camera needs the name it is configured under");
        }
        this.name = name;
        this.intrinsics = intrinsics;
        this.mount = mount;
    }

    /** The hardware name this camera answers to. */
    public String name() {
        return name;
    }

    public CameraIntrinsics intrinsics() {
        return intrinsics;
    }

    /** Where the camera sits on the robot. */
    public Pose3d mount() {
        return mount;
    }

    /** What this camera sees with the robot at the given pose. */
    public CameraView viewFrom(Pose2d robot) {
        return new CameraView(intrinsics, mount.toFieldFrame(robot));
    }

    @Override
    public String toString() {
        return String.format("%s [%s] mounted at %s", name, intrinsics, mount);
    }
}
