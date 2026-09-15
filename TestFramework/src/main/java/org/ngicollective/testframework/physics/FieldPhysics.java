package org.ngicollective.testframework.physics;

import org.ngicollective.testframework.camera.GameElement;
import org.ngicollective.testframework.sim.DriveModel;
import org.ngicollective.testframework.sim.FieldConfig;
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
 * <p>The robot is not simulated here, yet. It enters the world as a kinematic box carried to
 * wherever {@link DriveModel} says the robot is: it shoves balls and is shoved by nothing, because
 * the drive model still integrates wheel speeds straight into a pose. Making the chassis dynamic is
 * a later step and a bigger one &mdash; it needs mass, traction and a torque&ndash;speed curve
 * &mdash; and doing it in the same breath as the balls would have left no way to tell which half
 * was wrong.</p>
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
     * Releases this world.
     *
     * <p>Worth having rather than left to the garbage collector because a dashboard session builds
     * a fresh world on every INIT and every STOP, and ode4j's worlds, spaces and geoms reference
     * each other in ways that keep a whole discarded field reachable from its own contents.</p>
     */
    void destroy();

    /**
     * A world containing {@code arrangement}, walled in by {@code field}, with {@code drive}'s robot
     * pushing things around.
     *
     * @param drive the drive model whose pose the robot's collision box follows, or null for a
     *     robot that has no drivetrain and therefore no place on the field
     * @return an ode4j-backed world, or one whose balls never move if ode4j is not on the classpath
     */
    static FieldPhysics of(List<GameElement> arrangement, FieldConfig field, DriveModel drive) {
        try {
            return new OdeFieldPhysics(arrangement, field, drive);
        } catch (NoClassDefFoundError absent) {
            // Said once, loudly, and then the simulator carries on: the balls are where the
            // scenario put them and nothing can move them. Anyone who dropped the jar on purpose
            // knows why; anyone who did it by accident needs to be told, because "the robot drives
            // through the balls" is otherwise a physics bug they will go looking for in here.
            System.err.println("[physics] ode4j is not on the classpath (" + absent.getMessage()
                    + "); the balls on the field will not move. Add org.ode4j:core to"
                    + " TestFramework's dependencies to simulate them.");
            return new StillFieldPhysics(arrangement, drive);
        }
    }
}
