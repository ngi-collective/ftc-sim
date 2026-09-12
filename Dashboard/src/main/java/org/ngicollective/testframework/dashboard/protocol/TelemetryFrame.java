package org.ngicollective.testframework.dashboard.protocol;

import java.util.List;

/** One {@code telemetry.update()}, as the composed lines a Driver Station would display. */
public final class TelemetryFrame {

    public final long timestamp;
    public final List<String> lines;

    public TelemetryFrame(long timestamp, List<String> lines) {
        this.timestamp = timestamp;
        this.lines = lines;
    }
}
