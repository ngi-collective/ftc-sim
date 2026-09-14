package org.ngicollective.testframework.hardware;

import com.qualcomm.robotcore.hardware.HardwareMap;

import org.ngicollective.testframework.behavior.Behavior;
import org.ngicollective.testframework.behavior.ImuBehaviors;
import org.ngicollective.testframework.behavior.ImuState;
import org.ngicollective.testframework.behavior.MotorBehaviors;
import org.ngicollective.testframework.behavior.MotorState;
import org.ngicollective.testframework.behavior.ServoBehaviors;
import org.ngicollective.testframework.behavior.ServoState;
import org.ngicollective.testframework.sim.DriveModel;
import org.ngicollective.testframework.sim.FieldConfig;
import org.ngicollective.testframework.sim.RobotConfig;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A {@link HardwareMap} populated entirely with simulated devices, so an unmodified OpMode's
 * {@code hardwareMap.get(...)} calls resolve without a robot.
 *
 * <p>This extends the real {@code HardwareMap} rather than mocking it, so the SDK's own device
 * mappings ({@code hardwareMap.dcMotor.get("FL")}), name bookkeeping and {@code getAll} all keep
 * working. {@link #tryGet} is overridden because the stock implementation calls
 * {@code Device.isRevControlHub()}, whose class initializer needs an Android {@code Context}.</p>
 *
 * <pre>
 * FakeHardwareMap hardware = FakeHardwareMap.builder()
 *         .addMotor("FL").addMotor("FR").addMotor("BL").addMotor("BR")
 *         .addImu("imu")
 *         .build();
 * </pre>
 */
public class FakeHardwareMap extends HardwareMap {

    private final Map<String, FakeDevice<?>> fakes = new LinkedHashMap<>();
    private double elapsedSeconds;
    private DriveModel drive;

    private FakeHardwareMap() {
        // A null notifier is the SDK's own supported way of building a HardwareMap that no
        // OpModeManager owns; a null Context is safe because nothing on the simulated path reads it.
        super(null, null);
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    public <T> T tryGet(Class<? extends T> classOrInterface, String deviceName) {
        FakeDevice<?> device = fakes.get(deviceName.trim());
        if (device == null || !classOrInterface.isInstance(device)) {
            return null;
        }
        return classOrInterface.cast(device);
    }

    /** The simulated motor configured under {@code name}. */
    public FakeDcMotorEx motor(String name) {
        return require(name, FakeDcMotorEx.class);
    }

    /** The simulated servo configured under {@code name}. */
    public FakeServo servo(String name) {
        return require(name, FakeServo.class);
    }

    /** The simulated IMU configured under {@code name}. */
    public FakeImu imu(String name) {
        return require(name, FakeImu.class);
    }

    /** Every simulated device, keyed by configured name, in the order they were declared. */
    public Map<String, FakeDevice<?>> devices() {
        return Collections.unmodifiableMap(fakes);
    }

    /**
     * The drive model turning this robot's wheels into a field pose, or null when no drivetrain was
     * declared &mdash; a hardware map of one arm motor has no business having a pose.
     */
    public DriveModel drive() {
        return drive;
    }

    /** Simulated seconds elapsed since this map was built. */
    public double elapsedSeconds() {
        return elapsedSeconds;
    }

    /**
     * Advances every simulated device by {@code seconds} of simulated time.
     *
     * <p>This clock is the framework's own: it drives device behaviors only. It has no effect on
     * {@code LinearOpMode.sleep()} or {@code ElapsedTime}, which read real wall-clock time.</p>
     */
    public void advance(double seconds) {
        if (seconds < 0.0) {
            throw new IllegalArgumentException("cannot advance simulated time backwards");
        }
        elapsedSeconds += seconds;
        if (drive != null) {
            // Before the devices, deliberately: the drive model publishes this tick's chassis
            // heading into the IMU's state, and the IMU behavior that copies it out runs in the
            // loop below. Advancing the devices first would report the heading the robot had a tick
            // ago, and at 50 Hz a fast pivot moves several degrees in a tick -- exactly the lag a
            // heading-holding routine would then be tuned against and fail on the real robot.
            drive.advance(seconds);
        }
        for (FakeDevice<?> device : fakes.values()) {
            device.advance(seconds);
        }
    }

    private <T extends FakeDevice<?>> T require(String name, Class<T> type) {
        FakeDevice<?> device = fakes.get(name.trim());
        if (device == null) {
            throw new IllegalArgumentException(
                    "No simulated device named \"" + name + "\"; configured names are " + fakes.keySet());
        }
        if (!type.isInstance(device)) {
            throw new IllegalArgumentException(
                    "Simulated device \"" + name + "\" is a " + device.getClass().getSimpleName()
                            + ", not a " + type.getSimpleName());
        }
        return type.cast(device);
    }

    private void register(FakeDevice<?> device) {
        String name = device.configuredName();
        if (fakes.containsKey(name)) {
            throw new IllegalArgumentException("Duplicate simulated device name \"" + name + "\"");
        }
        fakes.put(name, device);
    }

    /** Declares the devices a robot configuration would have contained. */
    public static final class Builder {

        private final FakeHardwareMap map = new FakeHardwareMap();
        private int nextMotorPort;
        private int nextServoPort;
        private RobotConfig drivetrainRobot;
        private FieldConfig drivetrainField;

        private Builder() {
        }

        /** Adds a motor with {@link MotorBehaviors#ideal()} behavior. */
        public Builder addMotor(String name) {
            return addMotor(name, MotorBehaviors.ideal());
        }

        public Builder addMotor(String name, Behavior<MotorState> behavior) {
            FakeDcMotorEx motor = new FakeDcMotorEx(name, nextMotorPort++, behavior);
            map.register(motor);
            map.dcMotor.put(name, motor);
            return this;
        }

        /** Adds a servo with {@link ServoBehaviors#instant()} behavior. */
        public Builder addServo(String name) {
            return addServo(name, ServoBehaviors.instant());
        }

        public Builder addServo(String name, Behavior<ServoState> behavior) {
            FakeServo servo = new FakeServo(name, nextServoPort++, behavior);
            map.register(servo);
            map.servo.put(name, servo);
            return this;
        }

        /**
         * Adds an IMU with {@link ImuBehaviors#stationary()} behavior.
         *
         * <p>A robot that also declares a drivetrain wants {@link ImuBehaviors#followingChassis()}
         * instead: a stationary IMU on a robot whose wheels are turning reports a heading of zero
         * forever, which looks less like a missing argument than like a broken OpMode.</p>
         */
        public Builder addImu(String name) {
            return addImu(name, ImuBehaviors.stationary());
        }

        public Builder addImu(String name, Behavior<ImuState> behavior) {
            FakeImu imu = new FakeImu(name, behavior);
            map.register(imu);
            map.put(name, imu);
            return this;
        }

        /**
         * Declares that these motors drive a robot around a field, which is what gives the map a
         * {@link DriveModel}.
         *
         * <p>Order does not matter: the model is built once every device has been declared, so this
         * can be called before or after the motors and the IMU it resolves.</p>
         */
        public Builder withDrivetrain(RobotConfig robot, FieldConfig field) {
            this.drivetrainRobot = robot;
            this.drivetrainField = field;
            return this;
        }

        public FakeHardwareMap build() {
            if (drivetrainRobot != null) {
                map.drive = new DriveModel(drivetrainRobot, drivetrainField, map);
            }
            return map;
        }
    }
}
