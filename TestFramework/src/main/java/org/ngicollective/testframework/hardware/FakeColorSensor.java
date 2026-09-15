package org.ngicollective.testframework.hardware;

import com.qualcomm.robotcore.hardware.ColorSensor;
import com.qualcomm.robotcore.hardware.I2cAddr;

import org.ngicollective.testframework.behavior.Behavior;
import org.ngicollective.testframework.behavior.ColorState;

/** A simulated {@link ColorSensor}, reading the color of whatever the field put in its volume. */
public class FakeColorSensor extends FakeDevice<ColorState> implements ColorSensor {

    FakeColorSensor(String configuredName, Behavior<ColorState> behavior) {
        super(configuredName, new ColorState(), behavior);
    }

    @Override
    public String getDeviceName() {
        return "Simulated Color Sensor";
    }

    @Override
    public void resetDeviceConfigurationForOpMode() {
        state().setLedOn(false);
    }

    @Override
    public int red() {
        return state().red();
    }

    @Override
    public int green() {
        return state().green();
    }

    @Override
    public int blue() {
        return state().blue();
    }

    /**
     * Total light, as the mean of the three channels.
     *
     * <p>A real sensor measures clear light on its own photodiode, which is why a white ball reads
     * a higher alpha than a dark one. Averaging the channels reproduces that ordering without
     * pretending to model the diode's spectral response.</p>
     */
    @Override
    public int alpha() {
        return (state().red() + state().green() + state().blue()) / 3;
    }

    /**
     * Packs alpha and the three channels the way the SDK's own sensors do.
     *
     * <p>Shifted by hand rather than through {@code android.graphics.Color}, whose methods are
     * unimplemented stubs in a JVM unit test.</p>
     */
    @Override
    public int argb() {
        return ((alpha() & 0xFF) << 24)
                | ((state().red() & 0xFF) << 16)
                | ((state().green() & 0xFF) << 8)
                | (state().blue() & 0xFF);
    }

    @Override
    public void enableLed(boolean enable) {
        state().setLedOn(enable);
    }

    @Override
    public void setI2cAddress(I2cAddr newAddress) {
        throw new UnsupportedOperationException(
                "A simulated color sensor is not on an I2C bus, so it has no address to change.");
    }

    @Override
    public I2cAddr getI2cAddress() {
        throw new UnsupportedOperationException(
                "A simulated color sensor is not on an I2C bus, so it has no address to report.");
    }
}
