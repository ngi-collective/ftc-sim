package org.firstinspires.ftc.teamcode.simulated;

import org.ngicollective.ftcsim.behavior.ImuBehaviors;
import org.ngicollective.ftcsim.behavior.MotorBehaviors;
import org.ngicollective.ftcsim.camera.CameraIntrinsics;
import org.ngicollective.ftcsim.camera.SceneFrameSource;
import org.ngicollective.ftcsim.camera.SimulatedCamera;
import org.ngicollective.ftcsim.camera.SimulatedScene;
import org.ngicollective.ftcsim.hardware.FakeHardwareMap;
import org.ngicollective.ftcsim.hardware.SimulatedRobot;
import org.ngicollective.ftcsim.season.BioBuzz;
import org.ngicollective.ftcsim.season.BioBuzzField;
import org.ngicollective.ftcsim.sim.CameraConfig;
import org.ngicollective.ftcsim.sim.FieldConfig;
import org.ngicollective.ftcsim.sim.LauncherConfig;
import org.ngicollective.ftcsim.sim.MotorConfig;
import org.ngicollective.ftcsim.sim.Pose2d;
import org.ngicollective.ftcsim.sim.RobotConfig;
import org.ngicollective.ftcsim.sim.Season;
import org.ngicollective.ftcsim.sim.SensorConfig;
import org.ngicollective.ftcsim.sim.ServoConfig;
import org.ngicollective.ftcsim.sim.SimConfigFiles;

import java.nio.file.Path;
import java.util.Map;
import java.util.function.Supplier;

/**
 * The simulated stand-in for the team's robot: the same device names the real robot configuration
 * file declares, so unmodified OpModes find their hardware.
 *
 * <p>Single source of truth for the headless tests, the dashboard, and the simulated build of the
 * app itself. Rename a motor here and every OpMode that looks it up fails in the same place.</p>
 *
 * <p>The numbers live in {@code TeamCode/robot-config/example.json} rather than in this file. They
 * are physics: the drive model integrates them into a field pose, and the 3D scene draws the robot
 * they describe. Keeping them in one data file is what stops the encoder resolution from being
 * written down three times &mdash; once here, once in a layout file, once in the browser &mdash;
 * and drifting apart. The device <em>names</em> stay in code, because a rename must break a build.</p>
 *
 * <p>Motor directions are deliberately absent from the config: {@code DriveHardware} sets them at
 * runtime, and a second copy here would be free to disagree with the robot that actually ships.</p>
 */
public class ExampleRobot implements SimulatedRobot {

    private static final String CONFIG_NAME = "example";

    /**
     * The camera's optics before an OpMode has chosen a resolution: a nominal 60&deg; webcam.
     *
     * <p>Not a placeholder. Once {@code VisionPortal} starts streaming, the simulated camera
     * swaps in the SDK's own calibration for the resolution the OpMode asked for, so that frames
     * are drawn through the very lens the pose solver inverts. This is what the plain-JVM tests,
     * which have no Android calibration database to consult, render through.</p>
     */
    private static final CameraIntrinsics NOMINAL_OPTICS =
            CameraIntrinsics.approximate(640, 480);

    /**
     * Where {@link #create()} gets its numbers, read afresh for every robot it builds.
     *
     * <p>Not a copy parsed once in the constructor. Someone who moves the webcam edits the file
     * and presses INIT, and the dashboard keeps one robot for the life of the session: a robot
     * that had parsed its configuration on the way in would go on building the mount it was born
     * with until the process was restarted, which is exactly the bug this shape exists to make
     * impossible.</p>
     */
    private final Supplier<Snapshot> source;

    /**
     * What the most recent read found.
     *
     * <p>So that {@link #name()}, {@link #config()}, {@link #field()} and the hardware map are all
     * describing one generation of the file. Two pictures of one robot must not disagree, and a
     * browser drawing a chassis from these accessors beside a camera view rendered from
     * {@code create()} is two pictures. Volatile because a session answers questions from threads
     * other than the one that pressed INIT.</p>
     */
    private volatile Snapshot current;

    /**
     * The file {@link #source} reads, or null when the numbers were handed in by a test.
     *
     * <p>What makes a camera mount adjusted on the dashboard saveable: the session has to know
     * which file to put it back into, and a robot built from a {@code RobotConfig} someone
     * constructed has no such file.</p>
     */
    private final Path configurationFile;

    public ExampleRobot() {
        this(() -> new Snapshot(SimConfigFiles.robot(CONFIG_NAME), SimConfigFiles.field()),
                SimConfigFiles.robotFile(CONFIG_NAME));
    }

    /**
     * Reads its numbers from a nominated directory rather than the team's.
     *
     * <p>For tests about re-reading itself: they need a configuration file they can edit, and
     * editing {@code TeamCode/robot-config/example.json} would be editing the robot that drives.</p>
     */
    public ExampleRobot(Path configDirectory) {
        this(() -> new Snapshot(SimConfigFiles.robot(configDirectory, CONFIG_NAME),
                SimConfigFiles.field(configDirectory)),
                SimConfigFiles.robotFile(configDirectory, CONFIG_NAME));
    }

    /** For tests that want a robot or a field that differs from the one on disk. */
    public ExampleRobot(RobotConfig config, FieldConfig field) {
        this(config, field, BioBuzzField.official());
    }

    /**
     * The same, looking at a particular arrangement of the field.
     *
     * <p>The official field is the default because it needs no file to be right. A scenario is
     * for the arrangements that are a choice &mdash; a HIVE tipped the other way, balls left where
     * they broke autonomous last weekend &mdash; and
     * {@code BioBuzzScenario.named(name).scene()} is where one comes from.</p>
     */
    public ExampleRobot(RobotConfig config, FieldConfig field, SimulatedScene scene) {
        this(fixed(new Snapshot(config, field, scene)), null);
    }

    private ExampleRobot(Supplier<Snapshot> source, Path configurationFile) {
        this.source = source;
        this.configurationFile = configurationFile;
        // Read now as well as per create(), because a robot is asked its name before anything is
        // built: the dashboard picks which configuration to drive by name. A file that will not
        // parse therefore still fails at startup, where it is easiest to understand.
        this.current = source.get();
    }

    /** Numbers handed in by a test, which do not change under it. */
    private static Supplier<Snapshot> fixed(Snapshot snapshot) {
        return () -> snapshot;
    }

    @Override
    public String name() {
        return current.robot.name();
    }

    @Override
    public Path configurationFile() {
        return configurationFile;
    }

    @Override
    public FakeHardwareMap create() {
        // Read afresh. The point of keeping the numbers in a file is that editing the file changes
        // what happens next, and "next" is the robot built by the next INIT.
        Snapshot snapshot = source.get();
        current = snapshot;
        RobotConfig config = snapshot.robot;

        FakeHardwareMap.Builder builder = FakeHardwareMap.builder()
                // followingChassis rather than followingYawRate: heading is now derived from the
                // wheels, so the IMU reports what the chassis actually did. The rate-following and
                // stationary presets remain selectable, and selecting one is now fault injection --
                // an IMU that disagrees with the drivetrain on purpose.
                .addImu(config.imuName(), ImuBehaviors.followingChassis());
        for (String motor : config.motors().keySet()) {
            LauncherConfig launcher = config.launchers().get(motor);
            if (launcher == null) {
                builder.addMotor(motor);
                continue;
            }
            // A flywheel has inertia and the rest of this robot does not. Every other motor here
            // is ideal -- it reaches the speed it was asked for between one tick and the next --
            // and that is a fair model of a wheel against a floor with the whole robot's mass
            // behind it. It is not a fair model of a launcher: the seconds a flywheel spends
            // coming up to speed are the reason an OpMode has to wait for it, and with an ideal
            // motor firing too early is a mistake nobody could make.
            builder.addMotor(motor, MotorBehaviors.ramping(launcher.spinUpSeconds()));
        }

        // Servos and sensors come from the same file, under the names an OpMode looks them up by.
        // Declared here and wired to the field by MechanismModel, which refuses to build if the
        // two ever disagree -- a sensor the configuration describes and the robot does not have
        // would otherwise read nothing all match.
        for (ServoConfig servo : config.servos().values()) {
            if (servo.continuous()) {
                builder.addCRServo(servo.name());
            } else {
                builder.addServo(servo.name());
            }
        }
        for (SensorConfig sensor : config.sensors().values()) {
            switch (sensor.kind()) {
                case TOUCH:
                    builder.addTouchSensor(sensor.name());
                    break;
                case COLOR:
                    builder.addColorSensor(sensor.name());
                    break;
                case DISTANCE:
                    builder.addDistanceSensor(sensor.name());
                    break;
                case VOLTAGE:
                    builder.addVoltageSensor(sensor.name());
                    break;
                default:
                    throw new IllegalStateException("this build cannot simulate a \""
                            + sensor.kind() + "\" sensor, which \"" + sensor.name()
                            + "\" is declared as");
            }
        }

        // The camera's view follows the robot, so its frame source needs the pose that the map it
        // is being added to will own. Hence the holder: the map cannot exist before the devices
        // that go in it, and the camera cannot read a pose before the map exists.
        final FakeHardwareMap[] built = new FakeHardwareMap[1];
        CameraConfig camera = config.camera();
        SceneFrameSource frames = new SceneFrameSource(
                scene(),
                new SimulatedCamera(camera.name(), NOMINAL_OPTICS, camera.mount().pose()),
                new SceneFrameSource.PoseSource() {
                    @Override
                    public Pose2d pose() {
                        return built[0].drive().pose();
                    }
                });
        builder.addWebcam(camera.name(), frames, camera.framesPerSecond());

        FakeHardwareMap hardware = builder.withDrivetrain(config, snapshot.field)
                .withMechanisms(config).build();
        built[0] = hardware;
        for (Map.Entry<String, MotorConfig> entry : config.motors().entrySet()) {
            MotorConfig motor = entry.getValue();
            hardware.motor(entry.getKey())
                    .state()
                    .setMaxSpeed(motor.rpm(), motor.ticksPerRevolution());
        }
        return hardware;
    }

    /**
     * What the camera is looking at.
     *
     * <p>Overridable so a subclass can decide at runtime; the scene it is handed defaults to the
     * official BioBuzz field, which needs no file to be correct.</p>
     */
    protected SimulatedScene scene() {
        return current.scene;
    }

    /** The robot the most recent read describes, for callers that need its geometry. */
    public RobotConfig config() {
        return current.robot;
    }

    /** The field this robot is simulated on. */
    public FieldConfig field() {
        return current.field;
    }

    /** The example robot is built for BioBuzz: its scenarios stage HIVEs and its field scores CELLs. */
    @Override
    public Season season() {
        return BioBuzz.SEASON;
    }

    /**
     * One reading of the configuration files: the robot, the field it drives on, and what its
     * camera is looking at.
     *
     * <p>Together, because they are only correct together. The scene is conformed to the field in
     * here rather than at each use, so there is nowhere for a caller to get a scene built for one
     * perimeter and a drive model built for another.</p>
     */
    private static final class Snapshot {

        final RobotConfig robot;
        final FieldConfig field;
        final SimulatedScene scene;

        Snapshot(RobotConfig robot, FieldConfig field) {
            this(robot, field, BioBuzzField.official());
        }

        Snapshot(RobotConfig robot, FieldConfig field, SimulatedScene scene) {
            this.robot = robot;
            this.field = field;
            // On this robot's field, whatever the scene was built with: a season's tag geometry is
            // fixed by the game manual and knows nothing about which perimeter is in the room,
            // while the drive model and the Dashboard Field View both take the configured one.
            // Skipping this is how the camera comes to render a competition field beside a field
            // view of a half one.
            this.scene = scene.on(field);
        }
    }
}
