package org.ngicollective.testframework.camera;

import org.ngicollective.testframework.sim.FieldConfig;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Everything on the field a camera could see, and how to draw it.
 *
 * <p>The field itself &mdash; its tiles, its perimeter and its alliance ends, from
 * {@link FieldSurfaces} &mdash; plus the tag clusters and coloured game elements standing on it.
 * The field is drawn because the Dashboard shows this camera beside a field view of the same
 * world, and a camera that saw a grey void could not be checked against it; the grey is now only
 * what lies beyond the perimeter.</p>
 *
 * <p>Drawn back to front, so a ball in front of a tag hides it. That is not decoration either: a
 * robot's own intake blocking its view of a tag is an ordinary situation, and an OpMode that
 * assumed a tag stays visible should fail in simulation rather than at a competition. The field's
 * surfaces take part in the same sort, so a tag hanging in front of a wall covers it and a ball
 * on the far side of one does not.</p>
 */
public final class SimulatedScene {

    /** Grey, standing in for the gym beyond the perimeter: everything that is not the field. */
    private static final int DEFAULT_BACKGROUND = 110;

    private final List<TagCluster> clusters;
    private final List<GameElement> elements;
    private final List<FieldSurfaces.Surface> surfaces;
    private final int background;

    public SimulatedScene(List<TagCluster> clusters, List<GameElement> elements) {
        this(clusters, elements, FieldConfig.standard(), DEFAULT_BACKGROUND);
    }

    public SimulatedScene(List<TagCluster> clusters, List<GameElement> elements,
                          int backgroundGrey) {
        this(clusters, elements, FieldConfig.standard(), backgroundGrey);
    }

    /** A scene on a field that is not the competition one: a half field, or a taped-out gym. */
    public SimulatedScene(List<TagCluster> clusters, List<GameElement> elements,
                          FieldConfig field) {
        this(clusters, elements, field, DEFAULT_BACKGROUND);
    }

    public SimulatedScene(List<TagCluster> clusters, List<GameElement> elements,
                          FieldConfig field, int backgroundGrey) {
        this(clusters, elements, FieldSurfaces.of(field), backgroundGrey);
    }

    private SimulatedScene(List<TagCluster> clusters, List<GameElement> elements,
                           List<FieldSurfaces.Surface> surfaces, int backgroundGrey) {
        this.clusters = Collections.unmodifiableList(new ArrayList<>(clusters));
        this.elements = Collections.unmodifiableList(new ArrayList<>(elements));
        this.surfaces = surfaces;
        this.background = backgroundGrey;
    }

    /**
     * A scene with nothing in it but tags: no field either, just a flat grey void.
     *
     * <p>The fixture for a test about projection or rasterising, where a floor and four walls
     * would be geometry to reason around for no gain. Anything asking what a camera on the robot
     * sees wants a field, and therefore a constructor.</p>
     */
    public static SimulatedScene of(TagCluster... clusters) {
        return new SimulatedScene(Arrays.asList(clusters), Collections.<GameElement>emptyList(),
                Collections.<FieldSurfaces.Surface>emptyList(), DEFAULT_BACKGROUND);
    }

    public List<TagCluster> clusters() {
        return clusters;
    }

    public List<GameElement> elements() {
        return elements;
    }

    /**
     * This scene standing on a different field.
     *
     * <p>For the caller that knows which perimeter is really in the room. A season's tag geometry
     * is fixed by the game manual, so it is built without reference to a field; the field a robot
     * is actually simulated on comes from configuration, and those two facts meet here rather than
     * in every constructor. Handing a season scene to a robot without this is how the camera comes
     * to draw a competition perimeter while the field view draws the half field somebody
     * configured.</p>
     */
    public SimulatedScene on(FieldConfig field) {
        return new SimulatedScene(clusters, elements, FieldSurfaces.of(field), background);
    }

    /**
     * This scene with one cluster moved &mdash; what happens when a HIVE tips.
     *
     * @throws IllegalArgumentException if no cluster goes by that name, since a silent no-op here
     *     would look exactly like a tip that failed to change anything
     */
    public SimulatedScene withCluster(String name, Pose3d pose) {
        List<TagCluster> moved = new ArrayList<>(clusters.size());
        boolean found = false;
        for (TagCluster cluster : clusters) {
            if (cluster.name().equals(name)) {
                moved.add(cluster.movedTo(pose));
                found = true;
            } else {
                moved.add(cluster);
            }
        }
        if (!found) {
            throw new IllegalArgumentException("no cluster named \"" + name + "\" in this scene");
        }
        return new SimulatedScene(moved, elements, surfaces, background);
    }

    /**
     * Renders what the camera sees into a frame the caller owns.
     *
     * <p>The caller's frame, so a camera streaming at thirty frames a second is not allocating a
     * megabyte thirty times a second.</p>
     *
     * @return how many tags landed in the frame, which is the number a detector could plausibly
     *     report and therefore the number a test can assert on before ever starting an emulator
     */
    public int renderInto(SyntheticFrame frame, CameraView view) {
        frame.fillGrey(background);

        List<Drawable> drawables = new ArrayList<>(
                elements.size() + clusters.size() * 4 + surfaces.size());
        for (FieldSurfaces.Surface surface : surfaces) {
            drawables.add(new SurfaceDrawable(surface, surface.depthFrom(view)));
        }
        for (TagCluster cluster : clusters) {
            for (FieldTag tag : cluster.tags()) {
                drawables.add(new TagDrawable(tag, view.depthOf(tag.pose().position())));
            }
        }
        for (GameElement element : elements) {
            drawables.add(new ElementDrawable(element, view.depthOf(element.centre())));
        }

        // Farthest first: whatever is nearer paints over it.
        Collections.sort(drawables, new Comparator<Drawable>() {
            @Override
            public int compare(Drawable left, Drawable right) {
                return Double.compare(right.depth, left.depth);
            }
        });

        int tagsDrawn = 0;
        for (Drawable drawable : drawables) {
            if (drawable.draw(frame, view) && drawable instanceof TagDrawable) {
                tagsDrawn++;
            }
        }
        return tagsDrawn;
    }

    private abstract static class Drawable {

        final double depth;

        Drawable(double depth) {
            this.depth = depth;
        }

        abstract boolean draw(SyntheticFrame frame, CameraView view);
    }

    private static final class TagDrawable extends Drawable {

        private final FieldTag tag;

        TagDrawable(FieldTag tag, double depth) {
            super(depth);
            this.tag = tag;
        }

        @Override
        boolean draw(SyntheticFrame frame, CameraView view) {
            return TagRasteriser.draw(frame, view.project(tag));
        }
    }

    private static final class ElementDrawable extends Drawable {

        private final GameElement element;

        ElementDrawable(GameElement element, double depth) {
            super(depth);
            this.element = element;
        }

        @Override
        boolean draw(SyntheticFrame frame, CameraView view) {
            return ElementRasteriser.draw(frame, view, element);
        }
    }

    private static final class SurfaceDrawable extends Drawable {

        private final FieldSurfaces.Surface surface;

        SurfaceDrawable(FieldSurfaces.Surface surface, double depth) {
            super(depth);
            this.surface = surface;
        }

        @Override
        boolean draw(SyntheticFrame frame, CameraView view) {
            return SurfaceRasteriser.draw(frame, view, surface);
        }
    }
}
