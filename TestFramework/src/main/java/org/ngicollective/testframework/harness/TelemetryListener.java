package org.ngicollective.testframework.harness;

import java.util.List;

/**
 * Receives each telemetry frame the OpMode transmits, as the composed lines a Driver Station would
 * display.
 *
 * <p>Called on the thread that ran {@code telemetry.update()} &mdash; for a linear OpMode that is
 * the OpMode thread, not the test thread.</p>
 */
public interface TelemetryListener {

    void onTelemetry(List<String> lines);
}
