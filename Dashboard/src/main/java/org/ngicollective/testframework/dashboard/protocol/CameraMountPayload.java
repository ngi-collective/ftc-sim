package org.ngicollective.testframework.dashboard.protocol;

/**
 * Where the simulated robot's camera is bolted, and what it can see from there.
 *
 * <p>Sent on connect and again on every change, because the browser's mount sliders aim the live
 * camera: a drag sets the session's mount and the very next rendered frame is taken through it, no
 * INIT and no file write. {@link #unsaved} is what the UI warns on &mdash; true exactly while the
 * session is holding a mount the robot's configuration file does not have &mdash; so that a number
 * somebody liked does not quietly vanish on the next reload, and a number somebody was only
 * playing with does not quietly land in the team's file.</p>
 *
 * <p>{@link #name} is the device's name, and is the only thing that says which device this is: the
 * device panel matches it against a {@code DeviceState.name}. A second marker on the device row
 * saying "this one is the camera" could disagree with this one.</p>
 *
 * <p>The six mount numbers are in the units {@code robot-config/<robot>.json} writes: metres in
 * the robot frame &mdash; +X out the nose, +Y to the robot's left, +Z up, from the floor at the
 * centre of the footprint &mdash; and degrees, with yaw counter-clockwise from the nose, pitch
 * positive upward, and roll about the optical axis. The same numbers the file holds, so that what
 * a slider reads and what a person edits by hand are the same quantity.</p>
 *
 * <p>The two fields of view are derived from the camera's calibration rather than configurable.
 * They are here so the browser can draw the frustum the camera actually sees through; a lens in
 * this payload could only disagree with the one the frames are rendered with.</p>
 */
public final class CameraMountPayload {

    public final String name;
    public final double forwardMetres;
    public final double leftMetres;
    public final double heightMetres;
    public final double yawDegrees;
    public final double pitchDegrees;
    public final double rollDegrees;
    public final double horizontalFovDegrees;
    public final double verticalFovDegrees;
    public final boolean unsaved;

    public CameraMountPayload(String name, double forwardMetres, double leftMetres,
                              double heightMetres, double yawDegrees, double pitchDegrees,
                              double rollDegrees, double horizontalFovDegrees,
                              double verticalFovDegrees, boolean unsaved) {
        this.name = name;
        this.forwardMetres = forwardMetres;
        this.leftMetres = leftMetres;
        this.heightMetres = heightMetres;
        this.yawDegrees = yawDegrees;
        this.pitchDegrees = pitchDegrees;
        this.rollDegrees = rollDegrees;
        this.horizontalFovDegrees = horizontalFovDegrees;
        this.verticalFovDegrees = verticalFovDegrees;
        this.unsaved = unsaved;
    }
}
