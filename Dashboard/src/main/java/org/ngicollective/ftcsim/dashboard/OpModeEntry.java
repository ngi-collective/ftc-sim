package org.ngicollective.ftcsim.dashboard;

import com.qualcomm.robotcore.eventloop.opmode.OpMode;

import org.ngicollective.ftcsim.dashboard.protocol.OpModeInfo;

import java.util.function.Supplier;

/** A selectable OpMode plus the factory that produces a fresh instance for each run. */
public final class OpModeEntry {

    public final OpModeInfo info;
    public final Supplier<OpMode> factory;

    public OpModeEntry(OpModeInfo info, Supplier<OpMode> factory) {
        this.info = info;
        this.factory = factory;
    }
}
