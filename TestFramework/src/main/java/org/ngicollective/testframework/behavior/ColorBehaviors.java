package org.ngicollective.testframework.behavior;

/** The built-in catalog of color sensor behaviors. */
public final class ColorBehaviors {

    private ColorBehaviors() {
    }

    /** A working sensor: it reports the color the simulation put in front of it. */
    public static Behavior<ColorState> sensing() {
        return (state, elapsedSeconds) ->
                state.setColor(state.worldRed(), state.worldGreen(), state.worldBlue());
    }

    /**
     * A sensor that reads black whatever it is looking at &mdash; an unplugged I2C bus, or a lens
     * that scuffed opaque during a match.
     */
    public static Behavior<ColorState> blind() {
        return (state, elapsedSeconds) -> state.setColor(0, 0, 0);
    }

    /**
     * A sensor frozen on one color. Ball sorting that keys off hue puts every ball in the same bin
     * against this, which is the failure worth catching before the match rather than during it.
     */
    public static Behavior<ColorState> stuckAt(int red, int green, int blue) {
        return (state, elapsedSeconds) -> state.setColor(red, green, blue);
    }
}
