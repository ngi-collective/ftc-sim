package org.ngicollective.testframework.camera;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns a {@link Solid} into the flat polygons this renderer can actually fill.
 *
 * <p>This is the one place a primitive is flattened, and it exists because this rasteriser is the
 * odd consumer out: the physics world takes a box or a cylinder as it comes, and so does three.js,
 * where one primitive is one lit mesh. Here there is no lighting and one drawing operation &mdash;
 * a flat-filled {@link Surface} &mdash; so a box has to become six quads and a cylinder a ring of
 * them.</p>
 *
 * <p>Each face is a separate {@link Surface} rather than one object, because the painter's-order
 * sort in {@link SimulatedScene} works per polygon: that is what lets a ball inside a FLOWER be
 * drawn over the tube's far wall and behind its near one, with no depth buffer anywhere.</p>
 */
public final class SolidSurfaces {

    /**
     * Facets around a cylinder's circumference.
     *
     * <p>Twelve, which is a thirty-degree facet. The things this draws are a FLOWER's four-inch
     * mouth and its half-inch uprights, a metre or more from the camera and a few dozen pixels
     * across; past a dozen facets the extra polygons land inside the same pixels and every one of
     * them still costs a projection, a sort and a fill. A cylinder is also never the thing under
     * test &mdash; a detector is looking at the POLLEN inside it.</p>
     */
    private static final int FACETS = 12;

    private SolidSurfaces() {
    }

    /** Every polygon of one solid, in no particular order: the scene sorts them by depth. */
    public static List<Surface> of(Solid solid) {
        return solid.shape() == Solid.Shape.BOX ? box(solid) : shell(solid);
    }

    /** Every polygon of every solid a structure draws. */
    public static List<Surface> of(Structure structure) {
        List<Surface> surfaces = new ArrayList<>();
        for (Solid solid : structure.drawn()) {
            surfaces.addAll(of(solid));
        }
        return surfaces;
    }

    /**
     * Six faces from the three half-axes.
     *
     * <p>Corners run round each face rather than criss-crossing it: a {@link Surface} works out
     * its own winding, but it has to be a polygon in the first place, and a bow-tie ordering fills
     * as two triangles with a pinch in the middle.</p>
     */
    private static List<Surface> box(Solid solid) {
        Pose3d pose = solid.pose();
        Vec3 centre = pose.position();
        Vec3 alongX = pose.forward().scaled(solid.lengthX() / 2.0);
        Vec3 alongY = pose.left().scaled(solid.lengthY() / 2.0);
        Vec3 alongZ = pose.up().scaled(solid.lengthZ() / 2.0);

        List<Surface> faces = new ArrayList<>(6);
        faces.add(face(solid, centre.plus(alongX), alongY, alongZ));
        faces.add(face(solid, centre.minus(alongX), alongY, alongZ));
        faces.add(face(solid, centre.plus(alongY), alongZ, alongX));
        faces.add(face(solid, centre.minus(alongY), alongZ, alongX));
        faces.add(face(solid, centre.plus(alongZ), alongX, alongY));
        faces.add(face(solid, centre.minus(alongZ), alongX, alongY));
        return faces;
    }

    /** One face, as its centre plus the two half-axes that span it. */
    private static Surface face(Solid solid, Vec3 centre, Vec3 acrossOne, Vec3 acrossTwo) {
        return new Surface(new Vec3[] {
                centre.plus(acrossOne).plus(acrossTwo),
                centre.minus(acrossOne).plus(acrossTwo),
                centre.minus(acrossOne).minus(acrossTwo),
                centre.plus(acrossOne).minus(acrossTwo),
        }, solid.red(), solid.green(), solid.blue());
    }

    /**
     * A cylinder as {@link #FACETS} quads around its axis, and no end caps.
     *
     * <p>The radial basis is the pose's own forward and left, so a facet seam sits wherever the
     * structure's designer put the pose's nose. That is invisible on a twelve-sided tube and it
     * means a ring and the uprights around it seam in the same places, which is one fewer thing to
     * be surprised by.</p>
     */
    private static List<Surface> shell(Solid solid) {
        Pose3d pose = solid.pose();
        Vec3 centre = pose.position();
        Vec3 halfAxis = pose.up().scaled(solid.lengthMetres() / 2.0);
        double radius = solid.radiusMetres();

        List<Surface> facets = new ArrayList<>(FACETS);
        for (int facet = 0; facet < FACETS; facet++) {
            Vec3 start = centre.plus(radial(pose, radius, facet));
            Vec3 end = centre.plus(radial(pose, radius, facet + 1));
            facets.add(new Surface(new Vec3[] {
                    start.plus(halfAxis),
                    end.plus(halfAxis),
                    end.minus(halfAxis),
                    start.minus(halfAxis),
            }, solid.red(), solid.green(), solid.blue()));
        }
        return facets;
    }

    private static Vec3 radial(Pose3d pose, double radius, int facet) {
        double angle = 2.0 * Math.PI * facet / FACETS;
        return pose.forward().scaled(radius * Math.cos(angle))
                .plus(pose.left().scaled(radius * Math.sin(angle)));
    }
}
