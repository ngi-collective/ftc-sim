package org.ngicollective.testframework.camera;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A piece of the field a robot can run into: a FLOWER, the HIVE's frame, or a HIVE.
 *
 * <p>Named, because a test, a dashboard and a solver all want to say <em>which</em> one, and
 * because the name is what a moving structure is keyed by: a tipping HIVE's angle arrives on the
 * wire as "HIVE RED is at 0.43 radians" and everything bolted to it follows from that.</p>
 *
 * <h2>Two lists, on purpose</h2>
 *
 * <p>{@link #drawn()} is what a viewer sees and {@link #collided()} is what the solver pushes
 * against, and they are deliberately not the same geometry. A FLOWER's mouth is one ring: drawn,
 * that is a single open cylinder; collided with, it has to be a dozen little boxes around the
 * circumference, because ode4j's cylinders are solid and a solid one would plug the hole a robot
 * is trying to shoot POLLEN through.</p>
 *
 * <p>So a discrepancy between the two is not a bug to go and fix. What must agree is the
 * dimensions they are both derived from, which is why each structure computes both from one set of
 * measurements in one place. See
 * {@code docs/adr/0004-structures-are-published-as-posed-primitives.md}.</p>
 */
public final class Structure {

    private final String name;
    private final List<Solid> drawn;
    private final List<Solid> collided;
    private final Pivot pivot;

    /** A structure bolted down: a FLOWER, or the A-frame the HIVEs hang in. */
    public Structure(String name, List<Solid> drawn, List<Solid> collided) {
        this(name, drawn, collided, null);
    }

    /**
     * A structure on a pivot, its solids drawn at {@link Pivot#angleRadians()}.
     *
     * <p>The angle lives on the pivot rather than beside it so that a structure and the angle its
     * geometry was built at cannot be separated. {@link #at} is the only way to move one, and it
     * moves both.</p>
     */
    public Structure(String name, List<Solid> drawn, List<Solid> collided, Pivot pivot) {
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException("a structure needs a name to be referred to by");
        }
        this.name = name;
        this.drawn = Collections.unmodifiableList(new ArrayList<>(drawn));
        this.collided = Collections.unmodifiableList(new ArrayList<>(collided));
        this.pivot = pivot;
    }

    public String name() {
        return name;
    }

    /** What a camera and the browser draw. */
    public List<Solid> drawn() {
        return drawn;
    }

    /** What the physics world builds collision geometry from. */
    public List<Solid> collided() {
        return collided;
    }

    /** The axis this turns on, or null for the great majority of a field, which is bolted down. */
    public Pivot pivot() {
        return pivot;
    }

    /**
     * The same structure, turned to {@code angleRadians} about its pivot.
     *
     * <p>A rigid rotation of every solid it has, from the angle it is currently drawn at, so
     * repeated calls neither accumulate error nor need to be applied in order: this is a
     * <em>destination</em>, not a nudge. Passing the angle it is already at is the identity, which
     * is what the common case &mdash; a HIVE nobody has tipped &mdash; costs.</p>
     *
     * @throws IllegalStateException if this structure is bolted to the field, because silently
     *     answering "itself" would hide a caller that thinks a FLOWER can move
     */
    public Structure at(double angleRadians) {
        if (pivot == null) {
            throw new IllegalStateException(name + " is bolted to the field and has no pivot to"
                    + " turn on");
        }
        double turn = angleRadians - pivot.angleRadians();
        if (turn == 0.0) {
            return this;
        }
        return new Structure(name, rotate(drawn, turn), rotate(collided, turn),
                pivot.at(angleRadians));
    }

    private List<Solid> rotate(List<Solid> solids, double radians) {
        List<Solid> turned = new ArrayList<>(solids.size());
        for (Solid solid : solids) {
            turned.add(solid.rotatedAbout(pivot.point(), pivot.axis(), radians));
        }
        return turned;
    }

    @Override
    public String toString() {
        return String.format("%s (%d drawn, %d collided)", name, drawn.size(), collided.size());
    }
}
