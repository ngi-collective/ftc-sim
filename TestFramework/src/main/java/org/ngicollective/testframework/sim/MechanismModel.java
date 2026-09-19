package org.ngicollective.testframework.sim;

import org.ngicollective.testframework.behavior.ColorState;
import org.ngicollective.testframework.behavior.DistanceState;
import org.ngicollective.testframework.behavior.MotorState;
import org.ngicollective.testframework.behavior.TouchState;
import org.ngicollective.testframework.behavior.VoltageState;
import org.ngicollective.testframework.camera.GameElement;
import org.ngicollective.testframework.hardware.FakeCRServo;
import org.ngicollective.testframework.hardware.FakeColorSensor;
import org.ngicollective.testframework.hardware.FakeDcMotorEx;
import org.ngicollective.testframework.hardware.FakeDevice;
import org.ngicollective.testframework.hardware.FakeDistanceSensor;
import org.ngicollective.testframework.hardware.FakeHardwareMap;
import org.ngicollective.testframework.hardware.FakeTouchSensor;
import org.ngicollective.testframework.hardware.FakeVoltageSensor;
import org.ngicollective.testframework.physics.FieldPhysics;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Everything on the robot that is not the drivetrain: what its mechanisms do to the field, and what
 * its sensors read back out of it.
 *
 * <p>Two directions, and they are separate calls because they belong on opposite sides of a physics
 * step. A roller's power has to be in the world before the solver runs, or the intake spends every
 * tick acting on the command before last; a sensor has to be read after it, or every reading is a
 * tick stale and an OpMode that stops when its intake fills overshoots by a ball. {@code
 * FakeHardwareMap.advance} is the one place that ordering is written down.</p>
 *
 * <p>Sensors are <em>derived</em>, never configured. A colour sensor that returned a value from a
 * file would let an OpMode pass its tests while being wrong about the one thing it exists to
 * decide; this one reports the colour of the ball that is actually in front of it, or the foam tile
 * when there is none. The same rule is why {@link DriveModel} reads shaft speeds rather than
 * commanded power.</p>
 *
 * <p>The state a reading lands in is the world's truth, not what the OpMode sees. A behaviour
 * copies it out, and a fault behaviour reports something else &mdash; which is how a sensor gets to
 * lie in simulation the way one does in a match. See {@code ImuBehaviors.followingChassis()}, the
 * pattern this follows.</p>
 */
public final class MechanismModel {

    /**
     * What a colour sensor sees with nothing in front of it: the grey of an FTC foam tile.
     *
     * <p>Not black. A sensor looking at bare floor reads the floor, and an OpMode that waits for
     * "not black" would sail through a test and then trigger on the tile in the first match. The
     * exact grey is not the contract &mdash; that it is the floor rather than an absence is.</p>
     */
    private static final int TILE_GREY = 70;

    /**
     * A fresh, rested 12&nbsp;V FTC battery, and what the robot's own draw does to it.
     *
     * <p>Nominal figures, and the comment matters more than the numbers: the point is that the
     * voltage a sensor reports <em>sags when the robot pulls current</em>, because that is the
     * behaviour an OpMode compensating for a tired battery is written against. A voltage sensor
     * reporting 12.0 forever would make every such compensation untestable. Current is taken from
     * how hard the motors are actually working, at a few amps each under load &mdash; well short of
     * a 5203's 9.2&nbsp;A stall, which is a robot pushed against a wall rather than one driving.
     */
    private static final double RESTED_VOLTS = 12.5;
    private static final double INTERNAL_OHMS = 0.035;
    private static final double AMPS_AT_FULL_POWER = 4.5;

    private final List<Sweep> sweeps = new ArrayList<>();
    private final List<Launch> launches = new ArrayList<>();
    private final List<Sense> senses = new ArrayList<>();
    private final List<MotorState> motors = new ArrayList<>();

    /**
     * Resolves every declared mechanism and sensor to the simulated device it names.
     *
     * @throws IllegalArgumentException if a configured device is not in the hardware map, or is
     *     there as the wrong kind of device. Either is a configuration mistake, and a silently
     *     skipped sensor is one that reads zero all match.
     */
    public MechanismModel(RobotConfig robot, FakeHardwareMap hardware) {
        for (Map.Entry<String, ServoConfig> entry : robot.servos().entrySet()) {
            ServoConfig servo = entry.getValue();
            if (!servo.sweepsBalls()) {
                continue;
            }
            FakeCRServo device = require(hardware, entry.getKey(), FakeCRServo.class,
                    "sweeps balls, so it must be a continuous-rotation servo");
            sweeps.add(new Sweep(entry.getKey(), device));
        }

        for (LauncherConfig launcher : robot.launchers().values()) {
            String motorName = launcher.motorName();
            FakeDcMotorEx device = require(hardware, motorName, FakeDcMotorEx.class,
                    "spins a launcher's flywheel, so it must be a motor");
            // The encoder resolution comes from the configuration rather than from the device,
            // which is what makes the shaft speed readable whether or not anyone remembered to
            // call setMaxSpeed on the fake: an unset resolution is zero, and dividing by it would
            // turn a forgotten line into an infinite ball speed.
            launches.add(new Launch(launcher, robot.motors().get(motorName), device.state()));
        }

        for (Map.Entry<String, SensorConfig> entry : robot.sensors().entrySet()) {
            String name = entry.getKey();
            SensorConfig sensor = entry.getValue();
            switch (sensor.kind()) {
                case TOUCH:
                    senses.add(new Touch(sensor,
                            require(hardware, name, FakeTouchSensor.class, "is a touch sensor")
                                    .state()));
                    break;
                case COLOR:
                    senses.add(new Colour(sensor,
                            require(hardware, name, FakeColorSensor.class, "is a colour sensor")
                                    .state()));
                    break;
                case DISTANCE:
                    senses.add(new Range(sensor,
                            require(hardware, name, FakeDistanceSensor.class, "is a distance sensor")
                                    .state()));
                    break;
                case VOLTAGE:
                    senses.add(new Battery(
                            require(hardware, name, FakeVoltageSensor.class, "is a voltage sensor")
                                    .state()));
                    break;
                default:
                    throw new IllegalArgumentException("robot \"" + robot.name() + "\" declares a \""
                            + sensor.kind() + "\" sensor named \"" + name
                            + "\", which this build cannot simulate");
            }
        }

        // Every motor on the robot, for the battery: what sags a pack is current being pulled, and
        // the motors are where a simulated robot pulls it.
        for (FakeDevice<?> device : hardware.devices().values()) {
            if (device instanceof FakeDcMotorEx) {
                motors.add(((FakeDcMotorEx) device).state());
            }
        }
    }

    /**
     * Hands each mechanism's current shaft state to the world, before it is stepped.
     *
     * <p>A servo gives up its power and a launcher its surface speed, and the difference is the
     * point: an intake either runs or does not, while what a flywheel <em>is</em> doing is the
     * whole question. Reading {@code getVelocity()} and not {@code demandedVelocity()} is what
     * makes a shot fired during spin-up fall short, the same rule {@link DriveModel} follows for
     * the wheels.</p>
     */
    public void applyMechanisms(FieldPhysics physics) {
        for (Sweep sweep : sweeps) {
            physics.setSweepPower(sweep.name, sweep.servo.state().getPhysicalPower());
        }
        for (Launch launch : launches) {
            physics.setLauncherSpeed(launch.motorName, launch.surfaceMetresPerSecond());
        }
    }

    /**
     * Reads the world into every sensor's state, after it has been stepped.
     *
     * @param physics the world, or null when nothing is simulating one &mdash; in which case the
     *     sensors that need it read nothing rather than reading stale values from the last session
     */
    public void publishSensors(FieldPhysics physics) {
        for (Sense sense : senses) {
            sense.publish(physics);
        }
    }

    /** Whether this robot has anything for either direction to do. */
    public boolean isEmpty() {
        return sweeps.isEmpty() && launches.isEmpty() && senses.isEmpty();
    }

    private static <T> T require(FakeHardwareMap hardware, String name, Class<T> type,
                                 String because) {
        T device = hardware.tryGet(type, name);
        if (device == null) {
            throw new IllegalArgumentException("the configuration declares \"" + name
                    + "\", which " + because + ", but no such simulated device is in the hardware"
                    + " map; the configured names are " + hardware.devices().keySet());
        }
        return device;
    }

    /** One sweeping surface and the servo whose shaft runs it. */
    private static final class Sweep {

        private final String name;
        private final FakeCRServo servo;

        Sweep(String name, FakeCRServo servo) {
            this.name = name;
            this.servo = servo;
        }
    }

    /**
     * One launcher and the motor whose shaft spins it: everything needed to turn encoder ticks
     * into the speed of the rubber touching a ball.
     */
    private static final class Launch {

        private final String motorName;
        private final MotorConfig motor;
        private final MotorState state;

        /** Radians the wheel turns per encoder tick, times its radius: metres of surface per tick. */
        private final double metresPerTick;

        Launch(LauncherConfig launcher, MotorConfig motor, MotorState state) {
            this.motorName = launcher.motorName();
            this.motor = motor;
            this.state = state;
            this.metresPerTick = 2.0 * Math.PI * launcher.wheelRadiusMetres()
                    / motor.ticksPerRevolution();
        }

        /**
         * How fast the wheel's surface is moving right now, in metres/second.
         *
         * <p>The mounting mirror is applied here and nowhere else, exactly as {@link DriveModel}
         * applies it to a wheel: {@link MotorState#getVelocity()} already carries the OpMode's own
         * {@code Direction}, and {@link MotorConfig#mirrored()} is the separate fact that this
         * gearbox is bolted on facing the other way. A flywheel mounted mirrored and never set to
         * {@code REVERSE} therefore runs backwards and throws nothing, which is what the real one
         * does.</p>
         */
        double surfaceMetresPerSecond() {
            double surface = state.getVelocity() * metresPerTick;
            return motor.mirrored() ? -surface : surface;
        }
    }

    private interface Sense {

        void publish(FieldPhysics physics);
    }

    private static final class Touch implements Sense {

        private final SensorConfig sensor;
        private final TouchState state;

        Touch(SensorConfig sensor, TouchState state) {
            this.sensor = sensor;
            this.state = state;
        }

        @Override
        public void publish(FieldPhysics physics) {
            state.setTouching(physics != null && !physics.touching(sensor.volume()).isEmpty());
        }
    }

    private static final class Colour implements Sense {

        private final SensorConfig sensor;
        private final ColorState state;

        Colour(SensorConfig sensor, ColorState state) {
            this.sensor = sensor;
            this.state = state;
        }

        @Override
        public void publish(FieldPhysics physics) {
            List<GameElement> seen = physics == null
                    ? Collections.<GameElement>emptyList()
                    : physics.touching(sensor.volume());
            if (seen.isEmpty()) {
                state.setWorldColor(TILE_GREY, TILE_GREY, TILE_GREY);
                return;
            }
            GameElement nearest = seen.get(0);
            state.setWorldColor(nearest.red(), nearest.green(), nearest.blue());
        }
    }

    private static final class Range implements Sense {

        private final SensorConfig sensor;
        private final DistanceState state;

        Range(SensorConfig sensor, DistanceState state) {
            this.sensor = sensor;
            this.state = state;
        }

        @Override
        public void publish(FieldPhysics physics) {
            state.setWorldMetres(physics == null ? Double.NaN : physics.rangeAlong(sensor));
        }
    }

    /** The battery, whose voltage is the one reading that comes from the robot rather than the field. */
    private final class Battery implements Sense {

        private final VoltageState state;

        Battery(VoltageState state) {
            this.state = state;
        }

        @Override
        public void publish(FieldPhysics physics) {
            double amps = 0.0;
            for (MotorState motor : motors) {
                amps += Math.abs(motor.getPhysicalPower()) * AMPS_AT_FULL_POWER;
            }
            state.setWorldVolts(RESTED_VOLTS - amps * INTERNAL_OHMS);
        }
    }
}
