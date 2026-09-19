package org.ngicollective.testframework.physics;

import org.ngicollective.testframework.camera.GameElement;
import org.ngicollective.testframework.camera.Structure;
import org.ngicollective.testframework.sim.Chassis;
import org.ngicollective.testframework.sim.FieldConfig;
import org.ngicollective.testframework.sim.RobotConfig;
import org.ngicollective.testframework.sim.SensorConfig;
import org.ngicollective.testframework.sim.VolumeConfig;

import java.util.List;

/**
 * The balls on the field, and what happens to them.
 *
 * <p>One world, read by everything: the camera renders {@link #elements()}, the wire publishes
 * {@link #bodies()}, and a test asserts on either. That is the same rule ADR-0002 states for the
 * camera view &mdash; two pictures of one field that can disagree are worse than one picture,
 * because then a driver cannot tell which of them is lying.</p>
 *
 * <p>The robot is one of the bodies in it. It arrives with mass and is moved only by what its
 * wheels can grip and by whatever it runs into, so it can be slowed by a ball, stopped by a wall
 * and spun by a corner &mdash; which is what makes the field's own structures worth building, as
 * geometry a robot drives straight through is a lie the driver can see. {@link #chassis()} is the
 * robot in this world, and where its pose comes from.</p>
 *
 * <p>An interface rather than a class because of the licence. ode4j is LGPL-2.1, and while the
 * module graph already keeps it out of the competition APK, a team that wants it out of the build
 * entirely should be able to drop the jar and still run the simulator &mdash; with balls that sit
 * still, which is exactly what the simulator did before this existed. {@link #of} is what makes
 * that a supported outcome instead of a {@code NoClassDefFoundError} halfway through a match.</p>
 */
public interface FieldPhysics {

    /**
     * Steps the world forward by {@code seconds} of simulated time.
     *
     * <p>Whatever the caller's tick length, the solver's is fixed; see
     * {@code OdeFieldPhysics.STEP_SECONDS} for why that is not negotiable.</p>
     */
    void advance(double seconds);

    /**
     * The balls as the renderers want them: name, colour, radius, and where they are now.
     *
     * <p>In id order, so the nth element is body n. Fresh instances every call, because
     * {@link GameElement} is immutable and a scene holding one from two ticks ago would draw the
     * past.</p>
     */
    List<GameElement> elements();

    /** The same balls as the wire wants them: id, position, orientation. In id order. */
    List<BodyState> bodies();

    /**
     * Every structure on the field that can move, and where it has got to.
     *
     * <p>Two of them on a competition field: the red HIVE and the blue one. Empty when the world
     * was built without structures, which is what most tests want, and empty for every world
     * ode4j is absent from &mdash; nothing to tip is the same answer as nothing that can tip.</p>
     *
     * <p>What a caller does with this is hand it to {@code SimulatedScene.tipped}, which swings
     * the panels, the scoring volumes and the AprilTags together. Reading it is how a test asks
     * "did that shot tip the HIVE", and it is the only place the count of TIPs exists.</p>
     */
    List<PivotState> pivots();

    /**
     * The robot in this world, or null when the session's robot declares no drivetrain.
     *
     * <p>This is the one source of the robot's pose. Handing it to {@code DriveModel} rather than
     * letting the drive model keep its own is what stops two answers to "where is the robot"
     * existing at once, which is the same rule ADR-0002 states for the camera view: two pictures
     * of one field that can disagree are worse than one, because then nobody can tell which is
     * lying.</p>
     */
    Chassis chassis();

    /**
     * Whether anything is still in motion.
     *
     * <p>Asked on every tick so a still field costs no traffic and no allocation: a scenario nobody
     * has driven into yet publishes its arrangement once and then says nothing, which is also what
     * makes a moving ball visible in a socket log rather than buried in it.</p>
     */
    boolean moving();

    /**
     * The balls touching a box bolted to the robot &mdash; an intake's mouth, a sensor's field of
     * view.
     *
     * <p>This is the whole of what a trigger volume is, and it is deliberately the whole of it. A
     * sensor that reads a configured value tests nothing: an OpMode that waits for a ball in its
     * intake has to be waiting on a ball that is really there, or the test passes on a robot that
     * would sit in a match waiting forever.</p>
     *
     * <p>Nearest first, so a sensor that can only report one thing reports the thing a real one
     * would &mdash; whatever is closest to the middle of the volume.</p>
     */
    List<GameElement> touching(VolumeConfig volume);

    /**
     * Metres from a distance sensor's lens to the first thing its beam meets, or {@code NaN} when
     * that is nothing within its range.
     *
     * <p>{@code NaN} rather than the range, or zero, because {@code NaN} is what the SDK's own
     * sensors return when they see nothing, and an OpMode that checks for it is an OpMode that
     * works on the real robot. A range would read as a wall exactly two metres away.</p>
     *
     * <p>The robot's own chassis is not a thing its sensors can see. A sensor mounted at the nose
     * is looking past the bumper it is bolted to, and a beam that stopped dead on its own robot
     * would read zero forever.</p>
     */
    double rangeAlong(SensorConfig sensor);

    /**
     * Runs a sweeping servo's surface at {@code power}, from {@code -1} to {@code 1}.
     *
     * <p>Positive drags a ball inward, the way an intake roller does. Nothing is teleported and
     * nothing is captured: the roller is a real surface in the world whose skin moves, so a ball
     * enters because friction carried it in and leaves the same way. A "held" flag would have been
     * less code and would have made every question about whether a ball can be knocked loose
     * unanswerable.</p>
     *
     * @throws IllegalArgumentException if no servo of that name sweeps anything, because a
     *     mechanism nobody declared is a configuration mistake rather than a no-op
     */
    void setSweepPower(String servoName, double power);

    /**
     * Runs a launcher's flywheel with its surface moving at {@code surfaceMetresPerSecond}.
     *
     * <p>A surface speed and not a power, because a flywheel's whole point is that its speed lags
     * its command: the caller has read the simulated shaft, applied the wheel's radius and the
     * mounting mirror, and is telling the world what the rubber is doing right now. Negative is a
     * wheel running backwards, which throws nothing &mdash; that is a launcher whose motor wants
     * {@code REVERSE} and never got it, and it should be as visibly useless here as it is on a
     * real robot.</p>
     *
     * <p>What the world does with it is turn surface speed into ball speed, which is the part that
     * is physics rather than hardware: see {@code LauncherConfig.transferEfficiency}.</p>
     *
     * @param motorName the motor the launcher is keyed under
     * @throws IllegalArgumentException if no launcher is driven by that motor, because a mechanism
     *     nobody declared is a configuration mistake rather than a no-op
     */
    void setLauncherSpeed(String motorName, double surfaceMetresPerSecond);

    /**
     * Releases this world.
     *
     * <p>Worth having rather than left to the garbage collector because a dashboard session builds
     * a fresh world on every INIT and every STOP, and ode4j's worlds, spaces and geoms reference
     * each other in ways that keep a whole discarded field reachable from its own contents.</p>
     */
    void destroy();

    /**
     * A world containing {@code arrangement} and {@code structures}, walled in by {@code field},
     * with {@code robot} in it.
     *
     * @param structures the field's own furniture, whose {@code collided()} geometry becomes
     *     static obstacles; empty for a bare field, which is what most tests want
     * @param robot the robot to put on the field, or null for a hardware map that declares no
     *     drivetrain and therefore has no place on one
     * @return an ode4j-backed world, or one whose balls never move if ode4j is not on the classpath
     */
    static FieldPhysics of(List<GameElement> arrangement, List<Structure> structures,
                           FieldConfig field, RobotConfig robot) {
        try {
            return new OdeFieldPhysics(arrangement, structures, field, robot);
        } catch (NoClassDefFoundError absent) {
            // Said once, loudly, and then the simulator carries on: the balls are where the
            // scenario put them and nothing can move them. Anyone who dropped the jar on purpose
            // knows why; anyone who did it by accident needs to be told, because "the robot drives
            // through the balls" is otherwise a physics bug they will go looking for in here.
            System.err.println("[physics] ode4j is not on the classpath (" + absent.getMessage()
                    + "); the balls on the field will not move. Add org.ode4j:core to"
                    + " TestFramework's dependencies to simulate them.");
            return new StillFieldPhysics(arrangement, field, robot);
        }
    }
}
