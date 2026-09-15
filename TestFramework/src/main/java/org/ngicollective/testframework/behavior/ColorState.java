package org.ngicollective.testframework.behavior;

import com.qualcomm.robotcore.util.Range;

/**
 * Simulated state of a color sensor, 0..255 per channel.
 *
 * <p>The 8-bit scale matches what the SDK's {@code ColorSensor.red()} and friends return, so an
 * OpMode's own thresholds carry over from the field to the simulator unchanged.</p>
 *
 * <p>The {@code world} channels are what the simulation put in front of the sensor; the plain
 * channels are what the OpMode reads. Only {@link ColorBehaviors#sensing()} keeps them equal.</p>
 */
public class ColorState {

    private volatile int worldRed;
    private volatile int worldGreen;
    private volatile int worldBlue;
    private volatile int red;
    private volatile int green;
    private volatile int blue;
    private volatile boolean ledOn;

    /** Red the simulated world has in the sensor's volume, 0..255. */
    public int worldRed() {
        return worldRed;
    }

    /** Green the simulated world has in the sensor's volume, 0..255. */
    public int worldGreen() {
        return worldGreen;
    }

    /** Blue the simulated world has in the sensor's volume, 0..255. */
    public int worldBlue() {
        return worldBlue;
    }

    public void setWorldColor(int red, int green, int blue) {
        this.worldRed = clipChannel(red);
        this.worldGreen = clipChannel(green);
        this.worldBlue = clipChannel(blue);
    }

    /** Red as the OpMode reads it, 0..255. */
    public int red() {
        return red;
    }

    /** Green as the OpMode reads it, 0..255. */
    public int green() {
        return green;
    }

    /** Blue as the OpMode reads it, 0..255. */
    public int blue() {
        return blue;
    }

    public void setColor(int red, int green, int blue) {
        this.red = clipChannel(red);
        this.green = clipChannel(green);
        this.blue = clipChannel(blue);
    }

    /** Whether the OpMode turned the sensor's illumination LED on. */
    public boolean isLedOn() {
        return ledOn;
    }

    public void setLedOn(boolean ledOn) {
        this.ledOn = ledOn;
    }

    /**
     * Channels are clipped rather than rejected because the simulation derives them from lighting
     * arithmetic, and a value a shade over 255 there is rounding, not a bug worth aborting a match
     * for.
     */
    private static int clipChannel(int value) {
        return Range.clip(value, 0, 255);
    }
}
