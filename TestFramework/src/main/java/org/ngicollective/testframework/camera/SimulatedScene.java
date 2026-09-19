package org.ngicollective.testframework.camera;

import org.ngicollective.testframework.sim.FieldConfig;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Everything on the field a camera could see, and how to draw it.
 *
 * <p>The field itself &mdash; its tiles, its perimeter and its alliance ends, from
 * {@link FieldSurfaces} &mdash; plus the tag clusters, the coloured game elements, and the
 * structures standing on it. The field is drawn because the Dashboard shows this camera beside a
 * field view of the same world, and a camera that saw a grey void could not be checked against
 * it; the grey is now only what lies beyond the perimeter.</p>
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
    private final List<Structure> structures;
    private final List<ScoringVolume> volumes;
    private final List<Surface> surfaces;
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
        this(clusters, elements, Collections.<Structure>emptyList(),
                Collections.<ScoringVolume>emptyList(), everythingAround(field), backgroundGrey);
    }

    /**
     * The field and the room it stands in.
     *
     * <p>The room comes with the field rather than being opt-in because its job is to make the
     * view readable: this camera looks up at the overhead tags, so most of a frame is past the
     * perimeter, and a background that cannot change is a view a driver reads as frozen. See
     * {@link GymSurroundings}.</p>
     */
    private static List<Surface> everythingAround(FieldConfig field) {
        List<Surface> surfaces = new ArrayList<>(GymSurroundings.of(field));
        surfaces.addAll(FieldSurfaces.of(field));
        return Collections.unmodifiableList(surfaces);
    }

    private SimulatedScene(List<TagCluster> clusters, List<GameElement> elements,
                           List<Structure> structures, List<ScoringVolume> volumes,
                           List<Surface> surfaces, int backgroundGrey) {
        this.clusters = Collections.unmodifiableList(new ArrayList<>(clusters));
        this.elements = Collections.unmodifiableList(new ArrayList<>(elements));
        this.structures = Collections.unmodifiableList(new ArrayList<>(structures));
        this.volumes = Collections.unmodifiableList(new ArrayList<>(volumes));
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
                Collections.<Structure>emptyList(), Collections.<ScoringVolume>emptyList(),
                Collections.<Surface>emptyList(), DEFAULT_BACKGROUND);
    }

    public List<TagCluster> clusters() {
        return clusters;
    }

    public List<GameElement> elements() {
        return elements;
    }

    /** The field furniture a robot can run into: the HIVE Structure and the FLOWERs. */
    public List<Structure> structures() {
        return structures;
    }

    /**
     * Where an element on this field is worth points: the upward-facing CELLs, for now.
     *
     * <p>Empty for a scene that is not a season field &mdash; a bare cluster fixture, a taped-out
     * gym &mdash; because there is nowhere on one of those to score. These are the only part of a
     * scene a camera cannot see, and they ride along on it anyway because the scene is what every
     * consumer already receives: the Dashboard needs to know which CELL is the raised one to put a
     * score on screen, and the tip state that decides it lives in a scenario file that nothing
     * downstream of {@code ScenarioConfig.scene()} ever sees.</p>
     */
    public List<ScoringVolume> scoringVolumes() {
        return volumes;
    }

    /**
     * This scene with field furniture in it.
     *
     * <p>Separate from the constructors because a structure is the field's, not a scenario's: the
     * competition FLOWERs are bolted to the wall and nobody chooses where they go, while a
     * scenario chooses every ball. {@link org.ngicollective.testframework.season.BioBuzzField} is
     * what puts them here, once, and everything derived from that scene carries them along.</p>
     */
    public SimulatedScene withStructures(List<Structure> added) {
        return new SimulatedScene(clusters, elements, added, volumes, surfaces, background);
    }

    /**
     * This scene with somewhere on it that scores.
     *
     * <p>Attached by the season alongside the structures, and for the same reason: which CELL is
     * facing up is a property of the field as arranged, not of the robot looking at it or of the
     * balls lying on it.</p>
     */
    public SimulatedScene withScoringVolumes(List<ScoringVolume> added) {
        return new SimulatedScene(clusters, elements, structures, added, surfaces, background);
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
        return new SimulatedScene(clusters, elements, structures, volumes,
                everythingAround(field), background);
    }

    /**
     * This scene with its pivoting structures turned to the angles given &mdash; what a HIVE
     * tipping does to the field.
     *
     * <p>One rigid rotation per pivot, applied to the structure's own solids and to everything
     * that named it: its CELLs' scoring volumes, and the AprilTag clusters stuck to their
     * undersides. That is the point of doing it here rather than in the season &mdash; the panels,
     * the regions balls score in and the tags a camera ranges off are one body, and this is the
     * one place that can be true by construction instead of by three derivations agreeing.</p>
     *
     * <p>Structures not mentioned are left alone, and an angle a structure is already at costs
     * nothing: the common case is a field where nothing has tipped, called once per control cycle.
     * Surfaces are handed straight through for the same reason {@link #withElements} does it
     * &mdash; the tiles cannot tip.</p>
     *
     * @param anglesByStructure structure name to absolute pivot angle in radians, as
     *     {@code FieldPhysics} reports them
     * @throws IllegalArgumentException if a named structure is missing or is bolted down, because
     *     a silently ignored angle looks exactly like a HIVE the solver cannot move
     */
    public SimulatedScene tipped(Map<String, Double> anglesByStructure) {
        if (anglesByStructure.isEmpty()) {
            return this;
        }
        List<Structure> turned = new ArrayList<>(structures.size());
        Map<String, Double> rotations = new LinkedHashMap<>();
        for (Structure structure : structures) {
            Double angle = anglesByStructure.get(structure.name());
            if (angle == null) {
                turned.add(structure);
                continue;
            }
            if (structure.pivot() == null) {
                throw new IllegalArgumentException("structure \"" + structure.name() + "\" is"
                        + " bolted to the field and cannot be tipped to " + angle + " radians");
            }
            rotations.put(structure.name(), angle - structure.pivot().angleRadians());
            turned.add(structure.at(angle));
        }
        if (rotations.size() != anglesByStructure.size()) {
            throw new IllegalArgumentException("no structure named " + anglesByStructure.keySet()
                    + " in this scene; it has " + names(structures));
        }

        List<TagCluster> movedClusters = new ArrayList<>(clusters.size());
        for (TagCluster cluster : clusters) {
            Double turn = rotations.get(cluster.attachedTo());
            movedClusters.add(turn == null || turn == 0.0 ? cluster
                    : cluster.movedTo(rotate(cluster.pose(), cluster.attachedTo(), turn, turned)));
        }

        List<ScoringVolume> movedVolumes = new ArrayList<>(volumes.size());
        for (ScoringVolume volume : volumes) {
            Double turn = rotations.get(volume.attachedTo());
            movedVolumes.add(turn == null || turn == 0.0 ? volume
                    : volume.movedTo(rotate(volume.pose(), volume.attachedTo(), turn, turned)));
        }

        return new SimulatedScene(movedClusters, elements, turned, movedVolumes, surfaces,
                background);
    }

    /** One attached pose, swung about the pivot of the structure it named. */
    private static Pose3d rotate(Pose3d pose, String structureName, double radians,
                                 List<Structure> structures) {
        for (Structure structure : structures) {
            if (structure.name().equals(structureName)) {
                Pivot pivot = structure.pivot();
                return pose.rotatedAbout(pivot.point(), pivot.axis(), radians);
            }
        }
        throw new IllegalArgumentException("nothing in this scene is attached to \""
                + structureName + "\"");
    }

    private static String names(List<Structure> structures) {
        List<String> named = new ArrayList<>(structures.size());
        for (Structure structure : structures) {
            named.add(structure.name());
        }
        return named.toString();
    }

    /**
     * This scene with its game elements somewhere else &mdash; what a physics step produces.
     *
     * <p>The surfaces are handed straight through rather than rebuilt. A field's tiles, walls and
     * surroundings are a hundred-odd polygons that cannot move, and rebuilding them fifty times a
     * second to carry six balls three millimetres would make a rolling ball more expensive than
     * the field it rolls on.</p>
     */
    public SimulatedScene withElements(List<GameElement> moved) {
        return new SimulatedScene(clusters, moved, structures, volumes, surfaces, background);
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
        for (Surface surface : surfaces) {
            drawables.add(new SurfaceDrawable(surface, surface.depthFrom(view)));
        }
        // Flattened here rather than kept as polygons, so a structure's own geometry stays one
        // description shared with the solver and the browser. Each facet joins the same depth
        // sort as everything else, which is what lets a ball inside a FLOWER be drawn over the
        // tube's far wall and behind its near one with no depth buffer anywhere.
        for (Structure structure : structures) {
            for (Surface surface : SolidSurfaces.of(structure)) {
                drawables.add(new SurfaceDrawable(surface, surface.depthFrom(view)));
            }
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

        private final Surface surface;

        SurfaceDrawable(Surface surface, double depth) {
            super(depth);
            this.surface = surface;
        }

        @Override
        boolean draw(SyntheticFrame frame, CameraView view) {
            return SurfaceRasteriser.draw(frame, view, surface);
        }
    }
}
