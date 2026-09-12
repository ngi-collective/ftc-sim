package org.ngicollective.testframework.harness;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.ngicollective.testframework.hardware.FakeHardwareMap;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LinearOpModeHarnessTest {

    private FakeHardwareMap hardware;
    private SampleLinearOpMode opMode;
    private LinearOpModeHarness harness;

    @BeforeEach
    void setUp() {
        hardware = FakeHardwareMap.builder().addMotor("drive").build();
        hardware.motor("drive").state().setMaxTicksPerSecond(1000.0);
        opMode = new SampleLinearOpMode();
        harness = OpModeHarness.forLinear(opMode, hardware);
    }

    @Test
    void runsTheWholeLifecycleWithoutARobot() throws Exception {
        harness.launch();
        await(opMode.reachedWaitForStart);

        assertFalse(harness.isStarted(), "the OpMode must be held in init until start is pressed");
        assertEquals(0.0, hardware.motor("drive").getPower(), 1e-9);

        harness.pressStart();
        await(opMode.reachedRunLoop);

        assertEquals(1.0, hardware.motor("drive").getPower(), 1e-9);

        hardware.advance(1.0);
        assertEquals(1000, hardware.motor("drive").getCurrentPosition());

        harness.pressStop();
        harness.awaitCompletion(2000);

        assertTrue(opMode.loops > 0, "the run loop never ran");
        assertEquals(0.0, hardware.motor("drive").getPower(), 1e-9, "stop must cut power");
    }

    @Test
    void telemetryIsCapturedAsTheDriverStationWouldShowIt() throws Exception {
        harness.launch();
        await(opMode.reachedWaitForStart);

        assertEquals(1, harness.telemetryFrames().size());
        assertEquals(java.util.Collections.singletonList("Status : Initialized"),
                harness.lastTelemetry());

        harness.pressStop();
        harness.awaitCompletion(2000);
    }

    @Test
    void telemetryListenersSeeEveryFrameAsItIsTransmitted() throws Exception {
        List<List<String>> streamed = new CopyOnWriteArrayList<>();
        harness.addTelemetryListener(lines -> streamed.add(new ArrayList<>(lines)));

        harness.launch();
        await(opMode.reachedWaitForStart);
        harness.pressStop();
        harness.awaitCompletion(2000);

        assertTrue(streamed.contains(java.util.Collections.singletonList("Status : Initialized")),
                "listener saw " + streamed);
    }

    @Test
    void telemetryHistoryIsBoundedSoASpinningInitLoopCannotExhaustMemory() throws Exception {
        harness.setTelemetryHistory(5);

        harness.launch();
        await(opMode.reachedWaitForStart);
        harness.pressStart();
        await(opMode.reachedRunLoop);
        while (harness.telemetryFrames().size() < 5) {
            Thread.yield();
        }
        harness.pressStop();
        harness.awaitCompletion(2000);

        assertEquals(5, harness.telemetryFrames().size());
    }

    @Test
    void stoppingDuringInitReleasesAnOpModeParkedInWaitForStart() throws Exception {
        harness.launch();
        await(opMode.reachedWaitForStart);

        harness.pressStop();
        harness.awaitCompletion(2000);

        assertEquals(0, opMode.loops, "the run loop must not execute after a stop during init");
    }

    @Test
    void anOpModeThatThrowsSurfacesTheFailureToTheTest() {
        FakeHardwareMap empty = FakeHardwareMap.builder().build();
        LinearOpModeHarness failing =
                OpModeHarness.forLinear(new SampleLinearOpMode(), empty);

        failing.launch();

        // "drive" is not configured, so the hardware lookup throws on the OpMode thread.
        IllegalArgumentException thrown =
                assertThrows(IllegalArgumentException.class, () -> failing.awaitCompletion(2000));
        assertTrue(thrown.getMessage().contains("drive"), thrown.getMessage());
    }

    @Test
    void anOpModeThatIgnoresStopIsReportedRatherThanHangingTheSuite() throws Exception {
        harness.launch();
        await(opMode.reachedWaitForStart);
        harness.pressStart();
        await(opMode.reachedRunLoop);

        IllegalStateException thrown =
                assertThrows(IllegalStateException.class, () -> harness.awaitCompletion(100));
        assertTrue(thrown.getMessage().contains("still running"), thrown.getMessage());

        harness.pressStop();
        harness.awaitCompletion(2000);
    }

    private static void await(CountDownLatch latch) throws InterruptedException {
        assertTrue(latch.await(2, TimeUnit.SECONDS), "the OpMode thread did not get there in time");
    }
}
