package org.ngicollective.testframework.dashboard.protocol;

/** One selectable OpMode, as the Driver Station's dropdown would list it. */
public final class OpModeInfo {

    public final String name;
    public final String group;
    /** {@code "TeleOp"} or {@code "Autonomous"}. */
    public final String flavor;
    /** The implementing class, so two OpModes with the same display name stay distinguishable. */
    public final String className;

    public OpModeInfo(String name, String group, String flavor, String className) {
        this.name = name;
        this.group = group;
        this.flavor = flavor;
        this.className = className;
    }
}
