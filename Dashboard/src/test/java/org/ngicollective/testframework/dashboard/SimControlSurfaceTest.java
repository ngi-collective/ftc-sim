package org.ngicollective.testframework.dashboard;

import com.qualcomm.robotcore.eventloop.opmode.OpMode;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.ngicollective.testframework.dashboard.protocol.Alliance;
import org.ngicollective.testframework.dashboard.protocol.DeviceState;
import org.ngicollective.testframework.dashboard.protocol.GamepadState;
import org.ngicollective.testframework.dashboard.protocol.OpModeInfo;
import org.ngicollective.testframework.dashboard.protocol.SimPose;
import org.ngicollective.testframework.dashboard.protocol.SimStatus;
import org.ngicollective.testframework.hardware.FakeHardwareMap;
import org.ngicollective.testframework.hardware.SimulatedRobot;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@code sim/*} control surface: time dilation, pause, step, placement and alliance, plus the
 * pose stream they are all visible in.
 *
 * <p>All of it asserted through what a browser would receive rather than through the backend's own
 * fields, and all of it on {@link ManualTicks}: these are statements about how much simulated time
 * a control cycle hands to the hardware, which is arithmetic, so the test performs the cycles and
 * checks the arithmetic instead of sleeping and sampling.</p>
 *
 * <p>The session drives {@link MecanumIterativeTeleOp} deliberately. An iterative OpMode's
 * {@code loop()} runs inside the control cycle, so the commanded power for a cycle is a fact about
 * that cycle rather than about how the two threads happened to interleave.</p>
 */
class SimControlSurfaceTest {

    /** Well inside the field, so nothing here is measuring the wall clamp by accident. */
    private static final double DRIVE_STICK = -0.5;

    private final List<SimPose> poses = new CopyOnWriteArrayList<>();
    private final List<SimStatus> simStatuses = new CopyOnWriteArrayList<>();
    private final List<List<DeviceState>> deviceSnapshots = new CopyOnWriteArrayList<>();

    private ManualTicks ticks;
    private LocalDashboardBackend backend;

    /**
     * A robot whose configuration declares no drivetrain: one arm motor and an IMU.
     *
     * <p>Not a degenerate case &mdash; an arm-only test rig is a real thing to simulate &mdash; and
     * the backend promises to stay quiet about a pose it has no way to know.</p>
     */
    private static final SimulatedRobot ARM_ONLY = new SimulatedRobot() {
        @Override
        public String name() {
            return "ArmOnlyBot";
        }

        @Override
        public FakeHardwareMap create() {
            return FakeHardwareMap.builder().addMotor("arm").addImu("imu").build();
        }
    };

    @BeforeEach
    void setUp() {
        openSession(new DrivingRobot());
    }

    @AfterEach
    void tearDown() {
        backend.close();
    }

    @Test
    void clampsTheTimeMultiplierToARangeAPersonCanWatch() {
        backend.setSimTime(100.0, false);

        assertEquals(8.0, backend.simStatus().multiplier, 1e-9);
        assertEquals(8.0, lastSimStatus().multiplier, 1e-9,
                "the browser has to be told what it actually got, not what it asked for");

        backend.setSimTime(0.0, false);

        assertEquals(0.1, backend.simStatus().multiplier, 1e-9);
        assertEquals(0.1, lastSimStatus().multiplier, 1e-9);
    }

    @Test
    void rejectsATimeMultiplierThatIsNotANumber() {
        for (double requested : new double[] {
                Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
            assertThrows(IllegalArgumentException.class, () -> backend.setSimTime(requested, false),
                    "accepted a multiplier of " + requested);
        }

        assertEquals(1.0, backend.simStatus().multiplier, 1e-9,
                "a rejected request must not have changed anything");
        assertTrue(simStatuses.isEmpty(), "a rejected request published " + simStatuses.size()
                + " status messages");
    }

    /**
     * A request that lands on the settings already in force is not news. Republishing it would be
     * harmless on one browser and a feedback loop on a UI that echoes what it receives back.
     */
    @Test
    void aTimeRequestThatChangesNothingIsNotBroadcast() {
        backend.setSimTime(1.0, false);

        assertTrue(simStatuses.isEmpty(), "the session's own settings were broadcast as a change");

        backend.setSimTime(8.0, false);
        backend.setSimTime(100.0, false);

        assertEquals(1, simStatuses.size(),
                "a second request that clamped to the same multiplier was broadcast again");
    }

    @Test
    void handsOutSimulatedTimeAtTheMultiplierItWasGiven() {
        drive();
        int cycles = 10;

        // Up to speed first. A standing start is no longer instantaneous: the chassis is a rigid
        // body accelerated by what its wheels can grip, so it takes about three tenths of a second
        // to reach free speed. Distance is not proportional to time while that is happening, and
        // comparing a standing leg with a dilated one would be measuring the ramp rather than the
        // multiplier.
        ticks.pump(cycles);

        SimPose before = lastPose();
        ticks.pump(cycles);
        SimPose atOnce = lastPose();
        double firstLeg = atOnce.x - before.x;
        assertEquals(cycles * ManualTicks.SECONDS_PER_PUMP,
                atOnce.elapsedSeconds - before.elapsedSeconds, 1e-9);
        assertTrue(firstLeg > 0.0, "the robot did not move at all: " + firstLeg + " m");

        backend.setSimTime(2.0, false);
        ticks.pump(cycles);
        SimPose dilated = lastPose();

        assertEquals(2.0 * cycles * ManualTicks.SECONDS_PER_PUMP,
                dilated.elapsedSeconds - atOnce.elapsedSeconds, 1e-9);
        // A tenth of a percent, not an exact double: a wheel pulls towards its commanded speed
        // rather than snapping to it, so there is always some slip left to take up.
        assertEquals(2.0 * firstLeg, dilated.x - atOnce.x, firstLeg * 1e-3,
                "the same wheels over the same number of control cycles at twice the time "
                        + "multiplier have to cover twice the ground");
    }

    @Test
    void aPausedSimulationHandsOutNoTimeAtAll() {
        drive();
        ticks.pump(5);

        backend.setSimTime(1.0, true);
        SimPose frozen = lastPose();
        ticks.pump(5);

        assertEquals(frozen.elapsedSeconds, lastPose().elapsedSeconds, 1e-9);
        assertEquals(frozen.x, lastPose().x, 1e-12, "a paused robot moved");
        assertTrue(backend.simStatus().paused);
        assertTrue(lastSimStatus().paused, "the pause was not broadcast");
    }

    /**
     * A step is drained one cycle per tick rather than all at once, so a ten-cycle step animates at
     * the rate the robot really runs at instead of jumping to its end state.
     */
    @Test
    void stepsAPausedSimulationOneControlCycleAtATime() {
        drive();
        ticks.pump(5);
        backend.setSimTime(1.0, true);
        double frozenAt = lastPose().elapsedSeconds;

        backend.stepSim(3);

        for (int step = 1; step <= 3; step++) {
            ticks.pump();
            assertEquals(frozenAt + step * ManualTicks.SECONDS_PER_PUMP,
                    lastPose().elapsedSeconds, 1e-9,
                    "after " + step + " of a three-cycle step");
        }

        double steppedTo = lastPose().elapsedSeconds;
        double stoppedAt = lastPose().x;
        ticks.pump(5);

        assertEquals(steppedTo, lastPose().elapsedSeconds, 1e-9,
                "the step was exhausted and the simulation is paused again");
        assertEquals(stoppedAt, lastPose().x, 1e-12);
        assertTrue(stoppedAt > 0.0, "the steps did not drive the robot anywhere");
    }

    @Test
    void refusesAStepOfLessThanOneControlCycle() {
        backend.setSimTime(1.0, true);

        assertThrows(IllegalArgumentException.class, () -> backend.stepSim(0));
        assertThrows(IllegalArgumentException.class, () -> backend.stepSim(-1));
    }

    @Test
    void steppingASessionThatIsNotPausedIsRejected() {
        assertThrows(IllegalStateException.class, () -> backend.stepSim(1));
    }

    /**
     * Steps were asked for to inspect a frozen robot. Letting them fire into a resumed one, or
     * hold them until the next pause, would land as a glitch minutes later.
     */
    @Test
    void resumingDiscardsTheStepsQueuedWhilePaused() {
        drive();
        ticks.pump(5);
        backend.setSimTime(1.0, true);
        backend.stepSim(3);

        backend.setSimTime(1.0, false);
        backend.setSimTime(1.0, true);
        SimPose frozen = lastPose();
        ticks.pump(5);

        assertEquals(frozen.elapsedSeconds, lastPose().elapsedSeconds, 1e-9,
                "a step queued before the resume was still owed afterwards");
        assertEquals(frozen.x, lastPose().x, 1e-12);
    }

    @Test
    void reportsThePoseItWasToldToPlaceTheRobotAt() {
        backend.placeRobot(1.0, -0.5, 90.0);
        ticks.pump();

        SimPose placed = lastPose();
        assertEquals(1.0, placed.x, 1e-9);
        assertEquals(-0.5, placed.y, 1e-9);
        assertEquals(90.0, placed.headingDegrees, 1e-9);
        assertEquals(0.0, placed.forwardVelocity, 1e-9,
                "a teleport is not a motion; the robot arrives stopped");
    }

    /**
     * Where the robot is standing is a choice made by whoever is sitting in front of the
     * dashboard, like the alliance and the arrangement of the field, so it survives the rebuild
     * that INIT and STOP both do.
     *
     * <p>Without this, checking a moved camera mount or a repositioned sensor means dragging the
     * chassis back to where it was worth looking at after every single INIT, and driving somewhere
     * and pressing STOP teleports the robot to the middle of the field.</p>
     */
    @Test
    void theRobotStaysWhereItWasPutThroughAnInitAndAStop() {
        backend.placeRobot(1.0, -0.5, 90.0);
        ticks.pump();

        backend.initOpMode(MecanumIterativeTeleOp.class.getName());
        ticks.pump();

        SimPose afterInit = lastPose();
        assertEquals(1.0, afterInit.x, 1e-9, "INIT put the robot back at the field origin");
        assertEquals(-0.5, afterInit.y, 1e-9);
        assertEquals(90.0, afterInit.headingDegrees, 1e-9);

        backend.start();
        backend.stop();
        ticks.pump();

        SimPose afterStop = lastPose();
        assertEquals(1.0, afterStop.x, 1e-9, "STOP put the robot back at the field origin");
        assertEquals(-0.5, afterStop.y, 1e-9);
        assertEquals(90.0, afterStop.headingDegrees, 1e-9);
    }

    /**
     * The alliance survives the same rebuild, and has to reach the IMU of the robot the rebuild
     * produced: the offset is applied to hardware, and INIT replaces the hardware.
     */
    @Test
    void theAllianceSurvivesAnInitAndReachesTheRebuiltRobotsImu() {
        backend.setAlliance(Alliance.BLUE);

        backend.initOpMode(MecanumIterativeTeleOp.class.getName());
        backend.start();
        deviceSnapshots.clear();
        ticks.pumpUntil(() -> latestDevice("imu") != null, "no device snapshot arrived");

        assertEquals(180.0, Math.abs(latestDevice("imu").yawDegrees), 1e-9,
                "the rebuilt robot forgot which wall the session is played from");
        assertEquals(Alliance.BLUE, backend.simStatus().alliance);
    }

    @Test
    void placingARobotThatHasNoDrivetrainIsRejected() {
        openSession(ARM_ONLY);

        IllegalStateException thrown =
                assertThrows(IllegalStateException.class, () -> backend.placeRobot(0.0, 0.0, 0.0));
        assertTrue(thrown.getMessage().contains("ArmOnlyBot"), thrown.getMessage());
    }

    /**
     * Switching sides mid-session is the point of this control: it is how you check a field-centric
     * drive from the other wall without re-initialising. So the offset has to reach the IMU the
     * running OpMode is reading, not the next one it builds.
     */
    @Test
    void movingToTheOtherAllianceReachesTheRunningRobotsImu() {
        backend.initOpMode(MecanumIterativeTeleOp.class.getName());
        backend.start();
        ticks.pumpUntil(() -> latestDevice("imu") != null, "no device snapshot arrived");
        assertEquals(0.0, latestDevice("imu").yawDegrees, 1e-9,
                "red's station is at -X, so a robot facing +X reports heading zero with no offset");

        backend.setAlliance(Alliance.BLUE);
        deviceSnapshots.clear();
        ticks.pumpUntil(() -> latestDevice("imu") != null, "no device snapshot after the switch");

        assertEquals(180.0, Math.abs(latestDevice("imu").yawDegrees), 1e-9,
                "the same robot, now read from behind blue's wall, is pointing at that wall");
        assertEquals(Alliance.BLUE, backend.simStatus().alliance);
        assertEquals(Alliance.BLUE, lastSimStatus().alliance, "the switch was not broadcast");
    }

    @Test
    void settingTheAllianceItIsAlreadyOnIsNotBroadcast() {
        backend.setAlliance(Alliance.RED);

        assertTrue(simStatuses.isEmpty(), "a session already on red broadcast a change to red");

        backend.setAlliance(Alliance.BLUE);
        backend.setAlliance(Alliance.BLUE);

        assertEquals(1, simStatuses.size(), "the second switch to blue was broadcast again");
    }

    /**
     * The pose is the highest-frequency message on the socket and the one the field view is drawn
     * from: one per control cycle, whatever else is going on.
     */
    @Test
    void publishesAPoseEveryControlCycleWhileAnOpModeDrives() {
        drive();
        poses.clear();
        int cycles = 6;

        ticks.pump(cycles);

        assertEquals(cycles, poses.size(), "a subscriber saw " + poses.size() + " poses from "
                + cycles + " control cycles");
        for (int i = 1; i < poses.size(); i++) {
            assertTrue(poses.get(i).x > poses.get(i - 1).x,
                    "pose " + i + " did not advance: " + poses.get(i - 1).x + " then "
                            + poses.get(i).x);
            assertTrue(poses.get(i).timestampMillis > poses.get(i - 1).timestampMillis,
                    "pose " + i + " carries a timestamp that did not move");
        }
    }

    @Test
    void aRobotWithNoDrivetrainHasNoPoseToPublish() {
        openSession(ARM_ONLY);

        ticks.pump(5);

        assertTrue(poses.isEmpty(), "a robot that cannot drive published " + poses.size()
                + " poses; there is nothing for them to describe");
    }

    /**
     * Initialises, starts and commands the drivetrain forward, leaving the wheels already turning
     * at full commanded speed.
     */
    private void drive() {
        backend.initOpMode(MecanumIterativeTeleOp.class.getName());
        GamepadState input = new GamepadState();
        input.left_stick_y = (float) DRIVE_STICK;
        backend.injectGamepadState(1, input);
        backend.start();
        // Two cycles before the wheels are laying down floor, and neither is a quirk of the test.
        // The first advances the hardware before the OpMode's first loop() has commanded anything.
        // The second commands it, but a motor's behavior turns a command into physical shaft speed
        // during the device advance, which the drive model reads from *before* -- see
        // FakeHardwareMap.advance, which advances the drivetrain first on purpose so that a tick's
        // heading reaches the IMU in the same tick. So measure from the third.
        ticks.pump(2);
    }

    private void openSession(SimulatedRobot robot) {
        if (backend != null) {
            backend.close();
        }
        poses.clear();
        simStatuses.clear();
        deviceSnapshots.clear();

        ticks = new ManualTicks();
        Supplier<OpMode> factory = MecanumIterativeTeleOp::new;
        OpModeInfo info = new OpModeInfo("Mecanum Iterative TeleOp", "", "TeleOp",
                MecanumIterativeTeleOp.class.getName());
        backend = new LocalDashboardBackend(
                robot, Collections.singletonList(new OpModeEntry(info, factory)), ticks);
        backend.subscribeSimPose(poses::add);
        backend.subscribeSimStatus(simStatuses::add);
        backend.subscribeDeviceState(deviceSnapshots::add);
    }

    private SimPose lastPose() {
        assertTrue(!poses.isEmpty(), "no pose has been published at all");
        return poses.get(poses.size() - 1);
    }

    private SimStatus lastSimStatus() {
        assertTrue(!simStatuses.isEmpty(), "no sim status has been published at all");
        return simStatuses.get(simStatuses.size() - 1);
    }

    private DeviceState latestDevice(String name) {
        if (deviceSnapshots.isEmpty()) {
            return null;
        }
        for (DeviceState state : deviceSnapshots.get(deviceSnapshots.size() - 1)) {
            if (state.name.equals(name)) {
                return state;
            }
        }
        return null;
    }
}
