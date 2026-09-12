package org.ngicollective.testframework.harness;

import com.qualcomm.robotcore.eventloop.opmode.OpMode;

import org.ngicollective.testframework.hardware.FakeHardwareMap;

/**
 * Drives an iterative {@code OpMode} synchronously on the test thread: every {@code init_loop()} and
 * {@code loop()} call happens where the test says it does, so there is no concurrency to reason
 * about.
 *
 * <pre>
 * IterativeOpModeHarness harness = OpModeHarness.forIterative(new MyTeleOp(), hardware);
 * harness.init();
 * harness.pressStart();
 * harness.tick(0.02);   // one 20 ms control cycle
 * harness.stop();
 * </pre>
 */
public class IterativeOpModeHarness extends OpModeHarness {

    IterativeOpModeHarness(OpMode opMode, FakeHardwareMap hardware) {
        super(opMode, hardware);
    }

    /** Calls the OpMode's {@code init()}. */
    public void init() {
        opMode.init();
    }

    /** Calls the OpMode's {@code init_loop()} once. */
    public void initLoop() {
        opMode.init_loop();
    }

    /** Advances simulated time, then calls {@code init_loop()} once. */
    public void initTick(double seconds) {
        advance(seconds);
        initLoop();
    }

    /** Calls the OpMode's {@code loop()} once. */
    public void loop() {
        opMode.loop();
    }

    /**
     * One control cycle: advance simulated time by {@code seconds}, then call {@code loop()}.
     *
     * @param seconds simulated seconds for this cycle; the real robot runs at roughly 0.02
     */
    public void tick(double seconds) {
        advance(seconds);
        loop();
    }

    /** Runs {@code count} control cycles of {@code seconds} each. */
    public void tick(double seconds, int count) {
        for (int i = 0; i < count; i++) {
            tick(seconds);
        }
    }

    /** Calls the OpMode's {@code stop()} after flagging the stop request. */
    public void stop() {
        pressStop();
        opMode.stop();
    }

    /** Sets the start flag and calls the OpMode's {@code start()}. */
    @Override
    public void pressStart() {
        super.pressStart();
        opMode.start();
    }
}
