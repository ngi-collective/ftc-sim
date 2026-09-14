package org.ngicollective.testframework.dashboard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.ngicollective.testframework.camera.CameraIntrinsics;
import org.ngicollective.testframework.camera.GameElement;
import org.ngicollective.testframework.camera.Pose3d;
import org.ngicollective.testframework.camera.SceneFrameSource;
import org.ngicollective.testframework.camera.SimulatedCamera;
import org.ngicollective.testframework.camera.SimulatedScene;
import org.ngicollective.testframework.camera.Tag36h11;
import org.ngicollective.testframework.camera.TagCluster;
import org.ngicollective.testframework.camera.Vec3;
import org.ngicollective.testframework.dashboard.protocol.OpModeInfo;
import org.ngicollective.testframework.dashboard.protocol.OpModeStatus;
import org.ngicollective.testframework.dashboard.protocol.ScenePayload;
import org.ngicollective.testframework.hardware.FakeHardwareMap;
import org.ngicollective.testframework.hardware.SimulatedRobot;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.Supplier;

/**
 * What {@code sim/scene} promises the Field View about the world the Camera View renders.
 *
 * <p>Almost all of this is about corner order and cell orientation, because those are the two
 * things that can be wrong without looking wrong. A mirrored tag36h11 pattern is mostly not a valid
 * codeword: the field view would draw a convincing tag that no detector will ever report, and the
 * two views would disagree silently &mdash; which is the exact failure this envelope exists to
 * remove. So the expected geometry below is worked out from where a viewer stands, not read back
 * from what the converter produced.</p>
 */
class ScenePublicationTest {

    /** Tolerance on a corner, in metres; the arithmetic is exact but yaw goes through sin/cos. */
    private static final double EPSILON = 1e-9;

    private static final double TAG_SIZE = 0.1016;

    /**
     * The plate's centre, and its facing: yaw 180&deg; points the tags along field -X.
     *
     * <p>Chosen so that the viewer's axes are two different field axes with unambiguous signs. A
     * tag facing -X with no roll can be read by standing on the -X side looking back towards +X:
     * that viewer's up is field +Z, and their left hand points to field +Y. A mirrored conversion
     * swaps the sign of y, and a rotated one moves z, so either shows up as a named corner landing
     * somewhere else.</p>
     */
    private static final Vec3 PLATE_CENTRE = new Vec3(1.0, 0.5, 0.9);

    /**
     * Tag 30 as apriltag3 itself rendered it, transcribed from {@code Tag36h11Test}'s fixture.
     *
     * <p>Deliberately a second-hand copy of a hand-checked picture rather than a call into
     * {@code Tag36h11}: it fixes which way up the rows run, which is the part
     * {@code Tag36h11.isWhite} cannot be asked about.</p>
     */
    private static final String[] TAG_30_ROWS = {
            "BBBBBBBB",
            "BBWWBWWB",
            "BWBBBBBB",
            "BWWBBBWB",
            "BBWWWBWB",
            "BBWBWBBB",
            "BWBBWWWB",
            "BBBBBBBB",
    };

    private LocalDashboardBackend backend;

    @AfterEach
    void tearDown() {
        if (backend != null) {
            backend.close();
        }
    }

    @Test
    void cornersRunTopLeftTopRightBottomRightBottomLeftAsTheViewerSeesThem() {
        backend = sessionOn(sceneRobot());

        ScenePayload.Tag tag = tagWithId(backend.scene(), 30);

        double half = TAG_SIZE / 2.0;
        assertEquals(4, tag.corners.size());
        // A viewer on the -X side looking at this tag has +Y on their left and +Z above them.
        assertCorner("top-left", tag.corners.get(0),
                PLATE_CENTRE.x(), PLATE_CENTRE.y() + half, PLATE_CENTRE.z() + half);
        assertCorner("top-right", tag.corners.get(1),
                PLATE_CENTRE.x(), PLATE_CENTRE.y() - half, PLATE_CENTRE.z() + half);
        assertCorner("bottom-right", tag.corners.get(2),
                PLATE_CENTRE.x(), PLATE_CENTRE.y() - half, PLATE_CENTRE.z() - half);
        assertCorner("bottom-left", tag.corners.get(3),
                PLATE_CENTRE.x(), PLATE_CENTRE.y() + half, PLATE_CENTRE.z() - half);
    }

    /**
     * Cluster members are offset along the plate's +X, which is leftward as seen. The payload has
     * to carry that through: a tag published on the wrong side of its neighbour is a tag the driver
     * will aim at and miss.
     */
    @Test
    void aClusterMemberSitsWhereItsOffsetPutsIt() {
        backend = sessionOn(sceneRobot());

        ScenePayload payload = backend.scene();
        ScenePayload.Tag member = tagWithId(payload, 31);

        assertEquals(2, payload.tags.size());
        assertEquals("RED SCORING", member.cluster);
        assertEquals(TAG_SIZE, member.sizeMetres, EPSILON);
        // 0.2 m to the viewer's left of the plate centre, and the viewer's left is field +Y.
        double centreY = (member.corners.get(0).y + member.corners.get(1).y) / 2.0;
        assertEquals(PLATE_CENTRE.y() + 0.2, centreY, EPSILON);
        assertEquals(PLATE_CENTRE.z(),
                (member.corners.get(0).z + member.corners.get(3).z) / 2.0, EPSILON);
    }

    @Test
    void cellsSpellTheTagOutTheWayTheViewerReadsIt() {
        backend = sessionOn(sceneRobot());

        ScenePayload.Tag tag = tagWithId(backend.scene(), 30);

        assertEquals(Tag36h11.CELLS_ACROSS, tag.cells.size());
        for (int row = 0; row < Tag36h11.CELLS_ACROSS; row++) {
            assertEquals(Tag36h11.CELLS_ACROSS, tag.cells.get(row).length(),
                    "row " + row + " is the wrong width");
        }

        // Row 0 is the viewer's top row, column 0 their left: transposing or flipping the pattern
        // would leave the border rows intact and only these interior rows wrong.
        assertEquals(Arrays.asList(TAG_30_ROWS), tag.cells,
                "tag 30 does not look like what apriltag itself drew");
    }

    /** Sampled the other way round, so a rewritten fixture cannot drift from the family table. */
    @Test
    void sampledCellsAgreeWithTheFamilyTable() {
        backend = sessionOn(sceneRobot());

        ScenePayload.Tag tag = tagWithId(backend.scene(), 30);

        int[][] sampled = {{0, 0}, {1, 2}, {2, 1}, {3, 6}, {6, 3}, {7, 7}};
        for (int[] cell : sampled) {
            char expected = Tag36h11.isWhite(30, cell[0], cell[1]) ? 'W' : 'B';
            assertEquals(expected, tag.cells.get(cell[0]).charAt(cell[1]),
                    "cell (" + cell[0] + ", " + cell[1] + ")");
        }
    }

    @Test
    void gameElementsCarryTheirCentreRadiusAndColour() {
        backend = sessionOn(sceneRobot());

        ScenePayload payload = backend.scene();

        assertEquals(1, payload.elements.size());
        ScenePayload.Element pollen = payload.elements.get(0);
        assertEquals("POLLEN", pollen.name);
        assertEquals(0.3, pollen.x, EPSILON);
        assertEquals(-1.2, pollen.y, EPSILON);
        assertEquals(0.0355, pollen.z, EPSILON);
        assertEquals(0.0355, pollen.radiusMetres, EPSILON);
        assertEquals(222, pollen.red);
        assertEquals(196, pollen.green);
        assertEquals(64, pollen.blue);
    }

    @Test
    void aRobotWithNoCameraHasNoScene() {
        backend = sessionOn(robotWith(() -> FakeHardwareMap.builder().addMotor("drive").build()));

        assertNull(backend.scene(), "a blind robot must not claim the field is empty");
    }

    /**
     * A camera can render anything at all &mdash; a test pattern, a recording. Only a scene-backed
     * one knows what is on the field, and the rest must publish nothing rather than guess.
     */
    @Test
    void aCameraThatIsNotSceneBackedHasNoScene() {
        backend = sessionOn(robotWith(() -> FakeHardwareMap.builder()
                .addMotor("drive")
                .addWebcam("Webcam 1", frame -> frame.fillGrey(90))
                .build()));

        assertNull(backend.scene());
    }

    /** An init rebuilds the robot and therefore its scene, so the browser has to be told again. */
    @Test
    void anOpModeInitRepublishesTheScene() {
        backend = sessionOn(sceneRobot());
        List<ScenePayload> published = new ArrayList<>();
        backend.subscribeScene(published::add);

        backend.initOpMode(TickingTeleOp.class.getName());

        assertEquals(1, published.size());
        assertEquals(2, published.get(0).tags.size());
    }

    @Test
    void anInitOnASessionWithNoSceneStaysSilent() {
        backend = sessionOn(robotWith(() -> FakeHardwareMap.builder().addMotor("drive").build()));
        List<ScenePayload> published = new ArrayList<>();
        backend.subscribeScene(published::add);

        backend.initOpMode(TickingTeleOp.class.getName());

        assertEquals(OpModeStatus.State.INIT, backend.status().state,
                "a scene-less session still has to be able to run an OpMode");
        assertTrue(published.isEmpty(), "there is no scene to publish");
    }

    /**
     * A plate of two tags facing field -X, plus a ball.
     *
     * <p>Two members rather than one so that the leftward-offset convention is visible, and ids 30
     * and 31 because they are real tag36h11 ids with committed patterns.</p>
     */
    private static SimulatedScene scene() {
        TagCluster cluster = new TagCluster("RED SCORING",
                Pose3d.ofDegrees(PLATE_CENTRE, 180.0, 0.0, 0.0),
                Arrays.asList(
                        new TagCluster.Member(30, 0.0, 0.0, 0.0, TAG_SIZE),
                        new TagCluster.Member(31, 0.2, 0.0, 0.0, TAG_SIZE)));
        GameElement pollen = new GameElement("POLLEN", new Vec3(0.3, -1.2, 0.0355), 0.071,
                222, 196, 64);
        return new SimulatedScene(
                Collections.singletonList(cluster), Collections.singletonList(pollen));
    }

    private static SimulatedRobot sceneRobot() {
        SimulatedCamera camera = new SimulatedCamera("Webcam 1",
                CameraIntrinsics.approximate(320, 240),
                Pose3d.facingForward(new Vec3(0.0, 0.0, 0.3)));
        SceneFrameSource frames = new SceneFrameSource(scene(), camera);
        return robotWith(() -> FakeHardwareMap.builder()
                .addMotor("drive")
                .addWebcam("Webcam 1", frames)
                .build());
    }

    /** A robot that builds genuinely fresh hardware per run, as {@code initOpMode} expects. */
    private static SimulatedRobot robotWith(Supplier<FakeHardwareMap> hardware) {
        return new SimulatedRobot() {
            @Override
            public String name() {
                return "SceneBot";
            }

            @Override
            public FakeHardwareMap create() {
                return hardware.get();
            }
        };
    }

    private static LocalDashboardBackend sessionOn(SimulatedRobot robot) {
        Supplier<com.qualcomm.robotcore.eventloop.opmode.OpMode> factory = TickingTeleOp::new;
        OpModeInfo info = new OpModeInfo(
                "Ticking TeleOp", "", "TeleOp", TickingTeleOp.class.getName());
        return new LocalDashboardBackend(
                robot, Collections.singletonList(new OpModeEntry(info, factory)));
    }

    private static ScenePayload.Tag tagWithId(ScenePayload payload, int id) {
        assertNotNull(payload, "the session's camera renders a scene, so there is one to publish");
        for (ScenePayload.Tag tag : payload.tags) {
            if (tag.id == id) {
                return tag;
            }
        }
        throw new AssertionError("no tag " + id + " in the payload");
    }

    private static void assertCorner(String which, ScenePayload.Corner corner,
                                     double x, double y, double z) {
        assertEquals(x, corner.x, EPSILON, which + " x");
        assertEquals(y, corner.y, EPSILON, which + " y");
        assertEquals(z, corner.z, EPSILON, which + " z");
    }
}
