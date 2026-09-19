package org.ngicollective.testframework.camera;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A rigid group of AprilTags on one plate, placed as a unit.
 *
 * <p>BioBuzz puts four tags on the underside of every CELL, and the CELL tips between two
 * positions during a match. A cluster is therefore the thing that moves: place the plate, and the
 * four tags follow. Modelling the members individually would allow a field that cannot physically
 * exist, and would make the season's signature test &mdash; aim, watch the HIVE tip, does the
 * OpMode recover? &mdash; impossible to write honestly.</p>
 *
 * <h2>The cluster plane frame</h2>
 *
 * <p>Member offsets are in the cluster's own frame, with the same axes as a tag
 * ({@link FieldTag}): +X to the left as seen by a viewer facing the plate, +Y up the plate, +Z
 * into it. That is the SDK's frame, taken from {@code AprilTagProcessorImpl.doClusterSolve}, which
 * adds these offsets directly to the tag-corner coordinates it solves against. Keeping the same
 * frame means the numbers in a scenario file are the numbers in
 * {@code AprilTagGameDatabase}, with no sign flips in between for a reader to verify.</p>
 *
 * <p>One consequence is worth stating because it looks like a bug when first observed: since ids
 * ascend with +X, and +X is leftward as seen, <b>tag ids ascend right-to-left in the image</b>.</p>
 */
public final class TagCluster {

    private final String name;
    private final Pose3d pose;
    private final List<Member> members;
    private final String attachedTo;

    public TagCluster(String name, Pose3d pose, List<Member> members) {
        this(name, pose, members, null);
    }

    private TagCluster(String name, Pose3d pose, List<Member> members, String attachedTo) {
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException("a cluster needs a name to report detections under");
        }
        if (members.isEmpty()) {
            throw new IllegalArgumentException("cluster \"" + name + "\" has no tags");
        }
        this.name = name;
        this.pose = pose;
        this.members = Collections.unmodifiableList(new ArrayList<>(members));
        this.attachedTo = attachedTo;
    }

    /** The cluster's name, matching the SDK's library so detections read the same in telemetry. */
    public String name() {
        return name;
    }

    /** The plate's pose, with {@link Pose3d#forward()} pointing the way the tags face. */
    public Pose3d pose() {
        return pose;
    }

    public List<Member> members() {
        return members;
    }

    /**
     * The same cluster, declared to ride on a {@link Structure}'s pivot.
     *
     * <p>Manual &sect;9.6: "On the bottom face of each CELL is a unique AprilTag Cluster". The
     * sticker is on the basket, so it goes where the basket goes, and naming the structure it is
     * stuck to lets the scene swing the tags and the panels as one rigid thing. Re-deriving the
     * plates from a tip angle instead leaves them a degree or two out of agreement with the
     * geometry they are printed on, and the range error that follows looks exactly like a bad
     * camera calibration.</p>
     */
    public TagCluster on(String structureName) {
        return new TagCluster(name, pose, members, structureName);
    }

    /** The structure whose pivot carries these tags, or null for a plate bolted to the field. */
    public String attachedTo() {
        return attachedTo;
    }

    /** The same cluster, moved: what a CELL tipping does to all four tags at once. */
    public TagCluster movedTo(Pose3d newPose) {
        return new TagCluster(name, newPose, members, attachedTo);
    }

    /** Every member tag, resolved into the field frame. */
    public List<FieldTag> tags() {
        Vec3 clusterX = pose.left().scaled(-1.0);
        Vec3 clusterY = pose.up();
        Vec3 clusterZ = pose.forward().scaled(-1.0);

        List<FieldTag> tags = new ArrayList<>(members.size());
        for (Member member : members) {
            Vec3 centre = pose.position()
                    .plus(clusterX.scaled(member.offsetX))
                    .plus(clusterY.scaled(member.offsetY))
                    .plus(clusterZ.scaled(member.offsetZ));
            // A member shares the plate's orientation; only its position differs.
            tags.add(new FieldTag(member.id, member.size,
                    Pose3d.of(centre, pose.yaw(), pose.pitch(), pose.roll())));
        }
        return tags;
    }

    @Override
    public String toString() {
        return String.format("cluster \"%s\" (%d tags) at %s", name, members.size(), pose);
    }

    /** One tag's place on the plate. */
    public static final class Member {

        private final int id;
        private final double offsetX;
        private final double offsetY;
        private final double offsetZ;
        private final double size;

        /**
         * @param id the tag id
         * @param offsetXMetres along the plate's +X, which is leftward as seen by a viewer
         * @param offsetYMetres up the plate
         * @param offsetZMetres into the plate, away from the viewer
         * @param sizeMetres edge length of the black square
         */
        public Member(int id, double offsetXMetres, double offsetYMetres, double offsetZMetres,
                      double sizeMetres) {
            if (!(sizeMetres > 0.0)) {
                throw new IllegalArgumentException(
                        "tag " + id + " must have a positive size; got " + sizeMetres + "m");
            }
            this.id = id;
            this.offsetX = offsetXMetres;
            this.offsetY = offsetYMetres;
            this.offsetZ = offsetZMetres;
            this.size = sizeMetres;
        }

        public int id() {
            return id;
        }

        public double offsetXMetres() {
            return offsetX;
        }

        public double offsetYMetres() {
            return offsetY;
        }

        public double offsetZMetres() {
            return offsetZ;
        }

        public double sizeMetres() {
            return size;
        }
    }
}
