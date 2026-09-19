package org.ngicollective.testframework.hardware;

import com.qualcomm.robotcore.hardware.HardwareMap;

import org.ngicollective.testframework.behavior.Behavior;
import org.ngicollective.testframework.behavior.CRServoBehaviors;
import org.ngicollective.testframework.behavior.CRServoState;
import org.ngicollective.testframework.behavior.ColorBehaviors;
import org.ngicollective.testframework.behavior.ColorState;
import org.ngicollective.testframework.behavior.DistanceBehaviors;
import org.ngicollective.testframework.behavior.DistanceState;
import org.ngicollective.testframework.behavior.ImuBehaviors;
import org.ngicollective.testframework.behavior.ImuState;
import org.ngicollective.testframework.behavior.MotorBehaviors;
import org.ngicollective.testframework.behavior.MotorState;
import org.ngicollective.testframework.behavior.ServoBehaviors;
import org.ngicollective.testframework.behavior.ServoState;
import org.ngicollective.testframework.behavior.TouchBehaviors;
import org.ngicollective.testframework.behavior.TouchState;
import org.ngicollective.testframework.behavior.VoltageBehaviors;
import org.ngicollective.testframework.behavior.VoltageState;
import org.ngicollective.testframework.camera.FrameSource;
import org.ngicollective.testframework.camera.GameElement;
import org.ngicollective.testframework.camera.SceneFrameSource;
import org.ngicollective.testframework.camera.Structure;
import org.ngicollective.testframework.physics.FieldPhysics;
import org.ngicollective.testframework.physics.PivotState;
import org.ngicollective.testframework.sim.DriveModel;
import org.ngicollective.testframework.sim.FieldConfig;
import org.ngicollective.testframework.sim.MechanismModel;
import org.ngicollective.testframework.sim.RobotConfig;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
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
    private FieldPhysics physics;

    /** This robot's mechanisms and sensors, or null when it declares none of either. */
    private MechanismModel mechanisms;

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

    /** The simulated webcam configured under {@code name}. */
    public FakeWebcam webcam(String name) {
        return require(name, FakeWebcam.class);
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
     * Puts this robot in a different world: a dashboard scenario, or a test's arrangement.
     *
     * <p>Set from outside rather than built here, because what is on the field is a property of
     * the session, while what advances it has to be this class, which is the one place simulated
     * time passes.</p>
     *
     * <p>The robot moves house with it, keeping where it was standing. A world owns the robot's
     * pose, so replacing the world replaces the thing that knows where the robot is &mdash; and a
     * robot that returned to the origin every time an arrangement was loaded would make the
     * dashboard's "place the robot here" useless the moment anything else was edited.</p>
     */
    public void setPhysics(FieldPhysics physics) {
        this.physics = physics;
        if (drive != null && physics != null && physics.chassis() != null) {
            drive.useChassis(physics.chassis());
        }
    }

    /** The world this robot is driving in, or null when nothing is simulating one. */
    public FieldPhysics physics() {
        return physics;
    }

    /**
     * Advances every simulated device, and the world they are in, by {@code seconds} of simulated
     * time.
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
            // Before the world steps, because the world is what moves the robot: these are the
            // wheel speeds the contact forces of this step are computed from.
            drive.commandWheels();
        }
        if (physics != null) {
            if (mechanisms != null) {
                // Before the step: a roller's power has to be in the world the solver is about to
                // run, or every intake acts on the command before last.
                mechanisms.applyMechanisms(physics);
            }
            // Before the devices, and the camera is a device. Stepping the world after the camera
            // had rendered would stream frames of where the balls were a tick ago, while the field
            // view showed where they are -- two pictures of one field that disagree, which
            // ADR-0002 exists to prevent.
            physics.advance(seconds);
            if (physics.moving()) {
                showMovedBodies();
            }
        }
        if (drive != null) {
            // After the step, because only now does the chassis know where it ended up, and before
            // the devices, because the IMU behavior that copies the heading out to an OpMode runs
            // in the loop below. Publishing before the step would report the heading the robot had
            // a tick ago, and at 50 Hz a fast pivot moves several degrees in a tick -- exactly the
            // lag a heading-holding routine would then be tuned against and fail on the real
            // robot.
            drive.publishHeading();
        }
        if (mechanisms != null) {
            // After the step and before the devices, which is the same sandwich the IMU sits in:
            // this writes the world's truth into each sensor's state, and the behavior that copies
            // it out to the OpMode runs in the loop below. Reading before the step would report the
            // field as it was a tick ago, and an OpMode that stops its intake when a ball arrives
            // would stop it a ball late.
            mechanisms.publishSensors(physics);
        }
        for (FakeDevice<?> device : fakes.values()) {
            device.advance(seconds);
        }
    }

    /**
     * Puts the balls' new positions, and any HIVE that has turned, in front of any camera
     * rendering a scene.
     *
     * <p>Only while something is moving. A field at rest is the common case &mdash; a scenario
     * nobody has driven into yet, an OpMode being written &mdash; and it costs nothing at all.</p>
     *
     * <p>The tip goes through the scene rather than being applied to the tags directly, because a
     * tipping HIVE moves three things that have to agree: the panels a ball bounces off, the
     * region it scores in, and the AprilTag cluster an OpMode ranges off. This class knows none of
     * that &mdash; it hands over angles by structure name, and the scene swings whatever named
     * them.</p>
     */
    private void showMovedBodies() {
        List<GameElement> moved = null;
        Map<String, Double> tips = null;
        for (FakeDevice<?> device : fakes.values()) {
            if (!(device instanceof FakeWebcam)) {
                continue;
            }
            FrameSource frames = ((FakeWebcam) device).frameSource();
            if (!(frames instanceof SceneFrameSource)) {
                continue;
            }
            if (moved == null) {
                moved = physics.elements();
                tips = PivotState.anglesOf(physics.pivots());
            }
            SceneFrameSource scene = (SceneFrameSource) frames;
            scene.setScene(scene.scene().withElements(moved).tipped(tips));
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
        private RobotConfig mechanismRobot;

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
         * Adds a simulated webcam under the name an OpMode looks it up by.
         *
         * <p>The frame source is what the camera sees. Thirty frames a second of simulated time
         * is what an ordinary webcam delivers, and pacing off the simulated clock is what makes
         * the run repeatable.</p>
         */
        public Builder addWebcam(String name, FrameSource frameSource) {
            return addWebcam(name, frameSource, 30.0);
        }

        public Builder addWebcam(String name, FrameSource frameSource, double framesPerSecond) {
            FakeWebcam webcam = new FakeWebcam(name, frameSource, framesPerSecond);
            map.register(webcam);
            map.put(name, webcam);
            return this;
        }

        /** Adds a continuous-rotation servo with {@link CRServoBehaviors#ideal()} behavior. */
        public Builder addCRServo(String name) {
            return addCRServo(name, CRServoBehaviors.ideal());
        }

        public Builder addCRServo(String name, Behavior<CRServoState> behavior) {
            FakeCRServo servo = new FakeCRServo(name, nextServoPort++, behavior);
            map.register(servo);
            map.crservo.put(name, servo);
            return this;
        }

        /**
         * Adds a touch sensor whose reading comes from whatever is in its volume.
         *
         * <p>Which volume is the robot configuration's business, not this method's: a sensor is
         * wired to the world by {@link MechanismModel}, from the same file that says where it is
         * mounted. A sensor added here and not declared there reads nothing forever, which is why
         * that model refuses to build when the two disagree.</p>
         */
        public Builder addTouchSensor(String name) {
            return addTouchSensor(name, TouchBehaviors.sensing());
        }

        public Builder addTouchSensor(String name, Behavior<TouchState> behavior) {
            FakeTouchSensor sensor = new FakeTouchSensor(name, behavior);
            map.register(sensor);
            map.touchSensor.put(name, sensor);
            return this;
        }

        /** Adds a distance sensor, measuring along the beam its configuration aims. */
        public Builder addDistanceSensor(String name) {
            return addDistanceSensor(name, DistanceBehaviors.measuring());
        }

        public Builder addDistanceSensor(String name, Behavior<DistanceState> behavior) {
            FakeDistanceSensor sensor = new FakeDistanceSensor(name, behavior);
            map.register(sensor);
            // No typed mapping for this one: the SDK has `opticalDistanceSensor` for the old analog
            // part and nothing for a DistanceSensor, so an OpMode reaches it the modern way, with
            // hardwareMap.get(DistanceSensor.class, name), which tryGet answers.
            map.put(name, sensor);
            return this;
        }

        /** Adds a colour sensor, reading the nearest game element in its volume. */
        public Builder addColorSensor(String name) {
            return addColorSensor(name, ColorBehaviors.sensing());
        }

        public Builder addColorSensor(String name, Behavior<ColorState> behavior) {
            FakeColorSensor sensor = new FakeColorSensor(name, behavior);
            map.register(sensor);
            map.colorSensor.put(name, sensor);
            return this;
        }

        /** Adds a voltage sensor, reading a pack that sags under the robot's own draw. */
        public Builder addVoltageSensor(String name) {
            return addVoltageSensor(name, VoltageBehaviors.reporting());
        }

        public Builder addVoltageSensor(String name, Behavior<VoltageState> behavior) {
            FakeVoltageSensor sensor = new FakeVoltageSensor(name, behavior);
            map.register(sensor);
            map.voltageSensor.put(name, sensor);
            return this;
        }

        /**
         * Declares the mechanisms and sensors this robot's configuration describes, which is what
         * connects them to the field.
         *
         * <p>Separate from {@link #withDrivetrain} because a robot can have one without the other:
         * a test rig of an arm and a touch sensor has no drivetrain, and a bare chassis has no
         * mechanisms.</p>
         */
        public Builder withMechanisms(RobotConfig robot) {
            this.mechanismRobot = robot;
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
                // The world first, because it is what the robot's pose comes out of. Every
                // hardware map with a drivetrain gets one, empty of game elements and of field
                // furniture until a session or a test loads an arrangement: a robot whose pose
                // came from somewhere else until the first INIT would be a second physics nobody
                // tested.
                FieldPhysics world = FieldPhysics.of(
                        Collections.<GameElement>emptyList(), Collections.<Structure>emptyList(),
                        drivetrainField, drivetrainRobot);
                map.physics = world;
                map.drive = new DriveModel(
                        drivetrainRobot, drivetrainField, map, world.chassis());
            }
            if (mechanismRobot != null) {
                MechanismModel mechanisms = new MechanismModel(mechanismRobot, map);
                // Null rather than an empty model when a robot declares neither, so the tick skips
                // the whole business instead of walking two empty lists fifty times a second.
                map.mechanisms = mechanisms.isEmpty() ? null : mechanisms;
            }
            return map;
        }
    }
}
