package org.ngicollective.testframework.harness;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.ngicollective.testframework.hardware.FakeHardwareMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IterativeOpModeHarnessTest {

    private FakeHardwareMap hardware;
    private SampleIterativeOpMode opMode;
    private IterativeOpModeHarness harness;

    @BeforeEach
    void setUp() {
        hardware = FakeHardwareMap.builder().addMotor("drive").build();
        hardware.motor("drive").state().setMaxTicksPerSecond(1000.0);
        opMode = new SampleIterativeOpMode();
        harness = OpModeHarness.forIterative(opMode, hardware);
    }

    @Test
    void drivesTheLifecycleSynchronouslyOnTheTestThread() {
        harness.init();
        harness.initLoop();

        assertEquals(1, opMode.initLoops);
        assertEquals(0.0, hardware.motor("drive").getPower(), 1e-9);

        harness.pressStart();
        assertEquals(0.5, hardware.motor("drive").getPower(), 1e-9, "start() must have run");

        harness.gamepad1().left_stick_y = 1.0f;
        harness.tick(0.02, 5);

        assertEquals(5, opMode.loops);
        assertEquals(1.0, hardware.motor("drive").getPower(), 1e-9);
        // A tick advances the hardware and then runs loop(), like a real control cycle: the first
        // 20 ms still runs at the 0.5 power start() commanded, the other four at the 1.0 loop() set.
        assertEquals(90, hardware.motor("drive").getCurrentPosition());

        harness.stop();

        assertTrue(opMode.stopped);
        assertEquals(0.0, hardware.motor("drive").getPower(), 1e-9);
    }

    @Test
    void gamepadInputReachesTheOpMode() {
        harness.init();
        harness.pressStart();

        harness.gamepad1().left_stick_y = -0.25f;
        harness.tick(0.02);

        assertEquals(-0.25, hardware.motor("drive").getPower(), 1e-9);
    }

    @Test
    void telemetryFromAnIterativeOpModeIsCaptured() {
        harness.init();

        assertEquals(java.util.Collections.singletonList("Status : Initialized"),
                harness.lastTelemetry());
    }
}
