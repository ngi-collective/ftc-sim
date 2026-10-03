package org.ngicollective.ftcsim.dashboard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.ngicollective.ftcsim.camera.CameraIntrinsics;
import org.ngicollective.ftcsim.camera.GameElement;
import org.ngicollective.ftcsim.camera.Pose3d;
import org.ngicollective.ftcsim.camera.SceneFrameSource;
import org.ngicollective.ftcsim.camera.SimulatedCamera;
import org.ngicollective.ftcsim.camera.SimulatedScene;
import org.ngicollective.ftcsim.camera.Vec3;
import org.ngicollective.ftcsim.dashboard.protocol.Alliance;
import org.ngicollective.ftcsim.dashboard.protocol.OpModeInfo;
import org.ngicollective.ftcsim.dashboard.protocol.ScorePayload;
import org.ngicollective.ftcsim.hardware.FakeHardwareMap;
import org.ngicollective.ftcsim.hardware.SimulatedRobot;
import org.ngicollective.ftcsim.season.BioBuzz;
import org.ngicollective.ftcsim.season.BioBuzzElements;
import org.ngicollective.ftcsim.season.BioBuzzField;
import org.ngicollective.ftcsim.season.BioBuzzHive;
import org.ngicollective.ftcsim.season.BioBuzzScore;
import org.ngicollective.ftcsim.sim.Season;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Supplier;

/**
 * What {@code sim/score} says, and when it keeps quiet.
 *
 * <p>The score is the one frame in this protocol that is both greeted with and pushed on change,
 * and both halves are tested here, because each covers a way the readout goes wrong that the other
 * does not. Without the greeting, a page opened during a match shows nothing until the next ball
 * lands. Without the push, it shows the score as it was when the page loaded, for the rest of the
 * match.</p>
 *
 * <p>A ball is dropped into a real CELL rather than teleported into it. What makes a score change
 * is a ball crossing a CELL's mouth, and that is the solver's doing: this is the only test of the
 * lot that exercises the whole chain from gravity to the socket.</p>
 */
class ScorePublicationTest {

    private static final BioBuzzField.HiveTip UP = BioBuzzField.HiveTip.AUDIENCE_UP;
    private static final BioBuzzField.HiveTip DOWN = BioBuzzField.HiveTip.AUDIENCE_DOWN;

    private LocalDashboardBackend backend;
    private ManualTicks ticks;
    private final List<ScorePayload> published = new ArrayList<>();

    @AfterEach
    void tearDown() {
        if (backend != null) {
            backend.close();
        }
    }

    @Test
    void anEmptyFieldIsGreetedWithATwoNilScoreOfNothing() {
        session(BioBuzzField.scene(UP, DOWN));

        ScorePayload greeting = backend.score();

        assertNotNull(greeting, "a session on the season's field has CELLs, so it has a score");
        assertEquals(0, greeting.redPoints);
        assertEquals(0, greeting.bluePoints);
        assertEquals(2, greeting.volumes.size(),
                "one upward-facing CELL per alliance, and the two facing down are not in play");
        assertEquals(BioBuzzField.RED_AUDIENCE, greeting.volumes.get(0).name);
        assertEquals(Alliance.RED, greeting.volumes.get(0).alliance);
        assertEquals(BioBuzzField.BLUE_SCORING, greeting.volumes.get(1).name);
        assertEquals(Alliance.BLUE, greeting.volumes.get(1).alliance);
        assertEquals(0, tips(greeting, Alliance.RED),
                "each alliance's TIPs are sent at zero, so 'none yet' is not 'not counted'");
        assertEquals(0, tips(greeting, Alliance.BLUE));
    }

    /** The season is the robot's, so a robot that plays none has no score on any field. */
    @Test
    void aRobotThatPlaysNoSeasonHasNoScoreEvenOnASeasonsField() {
        session(BioBuzzField.scene(UP, DOWN), Season.none());

        assertNull(backend.score(),
                "with no season there is nobody to say what a CELL is worth");
    }

    /** A robot whose camera renders nothing has no CELLs, and a 0-0 would be a claim about one. */
    @Test
    void aSessionWithNoFieldHasNoScoreToGreetWith() {
        session(new SimulatedScene(Collections.emptyList(), Collections.emptyList()));

        assertNull(backend.score(),
                "a scene with no scoring volumes on it is not a field with an empty one");
    }

    @Test
    void aBallFallingIntoACellIsPublishedAsItScores() {
        session(BioBuzzField.scene(UP, DOWN).withElements(
                Collections.singletonList(aboveTheMouthOf(BioBuzzField.RED_AUDIENCE))));

        // Long enough for a 10 cm drop, which takes about a seventh of a second.
        for (int tick = 0; tick < 40; tick++) {
            ticks.pump();
        }

        assertTrue(published.size() >= 1,
                "the ball went in and nothing was said about it");
        ScorePayload scored = published.get(published.size() - 1);
        assertEquals(2, scored.redPoints, "one NECTAR in red's raised CELL is two points");
        assertEquals(0, scored.bluePoints);
        assertEquals(1, scored.volumes.get(0).holding);
    }

    @Test
    void theScoreIsSaidOnceRatherThanOnEveryCycleTheBallIsInThere() {
        session(BioBuzzField.scene(UP, DOWN).withElements(
                Collections.singletonList(aboveTheMouthOf(BioBuzzField.RED_AUDIENCE))));

        for (int tick = 0; tick < 40; tick++) {
            ticks.pump();
        }
        int whileFalling = published.size();
        published.clear();
        for (int tick = 0; tick < 100; tick++) {
            ticks.pump();
        }

        assertTrue(whileFalling >= 1, "the ball never scored at all");
        assertTrue(whileFalling <= 2,
                "the score went from 0 to 2 and should have been said once; it was said "
                        + whileFalling + " times");
        assertTrue(published.isEmpty(),
                "a ball sitting in a CELL is still being reported " + published.size()
                        + " times; the score has not changed and the socket should be quiet");
    }

    @Test
    void aTipIsPublishedAsTwentyPointsAndTheOtherCellComingIntoPlay() {
        // The whole chain, and the reason the volumes ride the scene rather than being handed to
        // the score: the HIVE turns inside the solver, the hardware map republishes the scene the
        // camera is looking at, and the score is then read from CELLs that have moved. Scored
        // against the staged geometry instead, this would report 12 points in a basket that is
        // upside down.
        session(BioBuzzField.scene(UP, DOWN).withElements(
                droppedInto(BioBuzzField.RED_AUDIENCE, 6)));

        // Long enough for six POLLEN to fall in, the HIVE to go over, and the balls to leave it.
        for (int tick = 0; tick < 150; tick++) {
            ticks.pump();
        }

        ScorePayload scored = published.get(published.size() - 1);
        assertEquals(1, tips(scored, Alliance.RED),
                "six POLLEN should have tipped red's HIVE: " + published);
        assertEquals(0, tips(scored, Alliance.BLUE));
        assertEquals(20, scored.redPoints,
                "twenty for the TIP, and nothing left in the basket that earned it");
        assertEquals(BioBuzzField.RED_SCORING, scored.volumes.get(0).name,
                "the CELL facing up is the other one now");
        assertEquals(0, scored.volumes.get(0).holding);
    }

    /** Enough POLLEN dropped in through a CELL's mouth to tip the HIVE it is cut in. */
    private static List<GameElement> droppedInto(String cellName, int count) {
        Pose3d cell = BioBuzzHive.cell(cellName, UP, DOWN);
        Vec3 mouth = cell.position()
                .plus(cell.forward().scaled(BioBuzzHive.CELL_DEPTH_METRES / 2.0));
        List<GameElement> balls = new ArrayList<>(count);
        for (int ball = 0; ball < count; ball++) {
            Vec3 at = mouth.plus(cell.left().scaled(((ball % 3) - 1) * 0.12))
                    .plus(new Vec3(0.0, 0.0, 0.10 + 0.09 * (ball / 3)));
            balls.add(BioBuzzElements.pollenAt(at.x(), at.y(), at.z()));
        }
        return balls;
    }

    /**
     * A NECTAR held just outside a CELL's mouth, so the drop is what puts it in.
     *
     * <p>Outside to begin with, which is the point: the first score has to be zero for the change
     * to be a change. Ten centimetres straight up from the middle of the opening clears the mouth,
     * because the mouth is tilted 30&deg; back and up.</p>
     */
    private static GameElement aboveTheMouthOf(String cellName) {
        Pose3d cell = BioBuzzHive.cell(cellName, UP, DOWN);
        Vec3 at = cell.position()
                .plus(cell.forward().scaled(BioBuzzHive.CELL_DEPTH_METRES / 2.0))
                .plus(new Vec3(0.0, 0.0, 0.10));
        return BioBuzzElements.redNectarAt(at.x(), at.y(), at.z());
    }

    /** How many TIPs {@code alliance} has, read off the tallies the way the browser reads them. */
    private static int tips(ScorePayload score, Alliance alliance) {
        for (ScorePayload.Tally tally : score.tallies) {
            if (tally.name.equals(BioBuzzScore.TIP) && tally.alliance == alliance) {
                return tally.count;
            }
        }
        throw new AssertionError("no " + alliance + " TIP tally in " + score.tallies);
    }

    private void session(SimulatedScene scene) {
        session(scene, BioBuzz.SEASON);
    }

    private void session(SimulatedScene scene, Season season) {
        ticks = new ManualTicks();
        OpModeInfo info = new OpModeInfo(
                "Ticking TeleOp", "", "TeleOp", TickingTeleOp.class.getName());
        Supplier<com.qualcomm.robotcore.eventloop.opmode.OpMode> factory = TickingTeleOp::new;
        backend = new LocalDashboardBackend(robotShowing(scene, season),
                Collections.singletonList(new OpModeEntry(info, factory)), ticks);
        backend.subscribeScore(published::add);
    }

    /** A robot with a camera rendering {@code scene} and no drivetrain; see BodyPublicationTest. */
    private static SimulatedRobot robotShowing(SimulatedScene scene, Season season) {
        return new SimulatedRobot() {
            @Override
            public String name() {
                return "ScoreBot";
            }

            @Override
            public Season season() {
                return season;
            }

            @Override
            public FakeHardwareMap create() {
                SimulatedCamera camera = new SimulatedCamera("Webcam 1",
                        CameraIntrinsics.approximate(320, 240),
                        Pose3d.facingForward(new Vec3(0.0, 0.0, 0.3)));
                return FakeHardwareMap.builder()
                        .addMotor("drive")
                        .addWebcam("Webcam 1", new SceneFrameSource(scene, camera))
                        .build();
            }
        };
    }
}
