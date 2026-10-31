package org.ngicollective.ftcsim.hardware;

import com.qualcomm.robotcore.hardware.I2cAddr;
import com.qualcomm.robotcore.hardware.I2cDeviceSynchSimple;
import com.qualcomm.robotcore.hardware.I2cWaitControl;
import com.qualcomm.robotcore.hardware.TimestampedData;

import org.ngicollective.ftcsim.behavior.Behavior;
import org.ngicollective.ftcsim.behavior.PinpointState;
import org.ngicollective.ftcsim.sim.PinpointMount;

/**
 * A simulated goBILDA Pinpoint, as the I&sup2;C bus sees it.
 *
 * <p>This is the device end of the wire, not a driver. The SDK's own
 * {@code GoBildaPinpointDriver} is constructed around it and is what an OpMode receives from
 * {@code hardwareMap.get(GoBildaPinpointDriver.class, name)}, so every byte an OpMode's pose comes
 * from passes through the driver's real decoding, bulk read and CRC check. Subclassing the driver
 * instead would have replaced exactly the code a team needs to be running.</p>
 */
public class FakePinpoint extends FakeDevice<PinpointState> implements I2cDeviceSynchSimple {

    private I2cAddr address = I2cAddr.zero();
    private String userConfiguredName;
    private HealthStatus health = HealthStatus.HEALTHY;
    private boolean logging;
    private String loggingTag = "FakePinpoint";
    private boolean writeCoalescing;

    FakePinpoint(PinpointMount mount, Behavior<PinpointState> behavior) {
        super(mount.name(), new PinpointState(mount), behavior);
        this.userConfiguredName = mount.name();
    }

    @Override
    public String getDeviceName() {
        return "Simulated goBILDA Pinpoint";
    }

    @Override
    public Manufacturer getManufacturer() {
        return Manufacturer.GoBilda;
    }

    @Override
    public void resetDeviceConfigurationForOpMode() {
        // The device keeps its registers across OpModes, as the real one does while powered.
    }

    @Override
    public byte read8() {
        throw new UnsupportedOperationException("the Pinpoint is only read by register");
    }

    @Override
    public byte read8(int ireg) {
        return read(ireg, 1)[0];
    }

    @Override
    public byte[] read(int creg) {
        throw new UnsupportedOperationException("the Pinpoint is only read by register");
    }

    @Override
    public byte[] read(int ireg, int creg) {
        return state().read(ireg, creg);
    }

    @Override
    public TimestampedData readTimeStamped(int creg) {
        throw new UnsupportedOperationException("the Pinpoint is only read by register");
    }

    @Override
    public TimestampedData readTimeStamped(int ireg, int creg) {
        TimestampedData result = new TimestampedData();
        result.data = read(ireg, creg);
        result.nanoTime = System.nanoTime();
        return result;
    }

    @Override
    public void write8(int bVal) {
        throw new UnsupportedOperationException("the Pinpoint is only written by register");
    }

    @Override
    public void write8(int ireg, int bVal) {
        write(ireg, new byte[] {(byte) bVal});
    }

    @Override
    public void write(byte[] data) {
        throw new UnsupportedOperationException("the Pinpoint is only written by register");
    }

    @Override
    public void write(int ireg, byte[] data) {
        state().write(ireg, data);
    }

    @Override
    public void write8(int bVal, I2cWaitControl waitControl) {
        write8(bVal);
    }

    @Override
    public void write8(int ireg, int bVal, I2cWaitControl waitControl) {
        write8(ireg, bVal);
    }

    @Override
    public void write(byte[] data, I2cWaitControl waitControl) {
        write(data);
    }

    @Override
    public void write(int ireg, byte[] data, I2cWaitControl waitControl) {
        write(ireg, data);
    }

    @Override
    public void waitForWriteCompletions(I2cWaitControl waitControl) {
        // Writes land in the state immediately.
    }

    @Override
    public void enableWriteCoalescing(boolean enable) {
        writeCoalescing = enable;
    }

    @Override
    public boolean isWriteCoalescingEnabled() {
        return writeCoalescing;
    }

    @Override
    public boolean isArmed() {
        return true;
    }

    @Override
    @Deprecated
    public void setI2cAddr(I2cAddr i2cAddr) {
        setI2cAddress(i2cAddr);
    }

    @Override
    @Deprecated
    public I2cAddr getI2cAddr() {
        return getI2cAddress();
    }

    @Override
    public void setI2cAddress(I2cAddr newAddress) {
        address = newAddress;
    }

    @Override
    public I2cAddr getI2cAddress() {
        return address;
    }

    @Override
    public void setLogging(boolean enabled) {
        logging = enabled;
    }

    @Override
    public boolean getLogging() {
        return logging;
    }

    @Override
    public void setLoggingTag(String loggingTag) {
        this.loggingTag = loggingTag;
    }

    @Override
    public String getLoggingTag() {
        return loggingTag;
    }

    @Override
    public void setHealthStatus(HealthStatus status) {
        health = status;
    }

    @Override
    public HealthStatus getHealthStatus() {
        return health;
    }

    @Override
    public void setUserConfiguredName(String name) {
        userConfiguredName = name;
    }

    @Override
    public String getUserConfiguredName() {
        return userConfiguredName;
    }
}
