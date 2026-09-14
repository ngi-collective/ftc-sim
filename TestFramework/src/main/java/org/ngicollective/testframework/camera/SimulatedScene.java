package org.ngicollective.testframework.camera;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Everything on the field a camera could see, and how to draw it.
 *
 * <p>Tag clusters and coloured game elements over a flat background. The background is a colour
 * rather than a rendered floor because nothing under test cares: what is being exercised is an
 * OpMode's reaction to detections, and a detector run over a photorealistic field reaches the same
 * conclusions it reaches over a grey one, just slower and with more code to be wrong.</p>
 *
 * <p>Drawn back to front, so a ball in front of a tag hides it. That is not decoration either: a
 * robot's own intake blocking its view of a tag is an ordinary situation, and an OpMode that
 * assumed a tag stays visible should fail in simulation rather than at a competition.</p>
 */
public final class SimulatedScene {

    /** Grey, in the neighbourhood of the field's foam tiles. */
    private static final int DEFAULT_BACKGROUND = 110;

    private final List<TagCluster> clusters;
    private final List<GameElement> elements;
    private final int background;

    public SimulatedScene(List<TagCluster> clusters, List<GameElement> elements) {
        this(clusters, elements, DEFAULT_BACKGROUND);
    }

    public SimulatedScene(List<TagCluster> clusters, List<GameElement> elements,
                          int backgroundGrey) {
        this.clusters = Collections.unmodifiableList(new ArrayList<>(clusters));
        this.elements = Collections.unmodifiableList(new ArrayList<>(elements));
        this.background = backgroundGrey;
    }

    /** A scene with nothing in it but tags. */
    public static SimulatedScene of(TagCluster... clusters) {
        return new SimulatedScene(Arrays.asList(clusters), Collections.<GameElement>emptyList());
    }

    public List<TagCluster> clusters() {
        return clusters;
    }

    public List<GameElement> elements() {
        return elements;
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
        return new SimulatedScene(moved, elements, background);
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

        List<Drawable> drawables = new ArrayList<>(elements.size() + clusters.size() * 4);
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
}
