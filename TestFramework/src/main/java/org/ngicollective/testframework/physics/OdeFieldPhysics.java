package org.ngicollective.testframework.physics;

import org.ngicollective.testframework.camera.GameElement;
import org.ngicollective.testframework.camera.Vec3;
import org.ngicollective.testframework.sim.ChassisConfig;
import org.ngicollective.testframework.sim.ChassisVelocity;
import org.ngicollective.testframework.sim.DriveModel;
import org.ngicollective.testframework.sim.FieldConfig;
import org.ngicollective.testframework.sim.Pose2d;
import org.ngicollective.testframework.sim.RobotConfig;
import org.ngicollective.testframework.sim.SensorConfig;
import org.ngicollective.testframework.sim.ServoConfig;
import org.ngicollective.testframework.sim.VolumeConfig;
import org.ode4j.math.DQuaternion;
import org.ode4j.math.DQuaternionC;
import org.ode4j.math.DVector3C;
import org.ode4j.ode.DBody;
import org.ode4j.ode.DBox;
import org.ode4j.ode.DContact;
import org.ode4j.ode.DContactBuffer;
import org.ode4j.ode.DGeom;
import org.ode4j.ode.DJointGroup;
import org.ode4j.ode.DMass;
import org.ode4j.ode.DRay;
import org.ode4j.ode.DSpace;
import org.ode4j.ode.DSphere;
import org.ode4j.ode.DWorld;
import org.ode4j.ode.OdeConstants;
import org.ode4j.ode.OdeHelper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Rigid-body physics for the field, on ode4j.
 *
 * <p>Balls are spheres, the floor and the perimeter are static geometry, and the robot is a
 * kinematic box dragged to wherever the drive model says it is. Everything is in the FTC field
 * frame &mdash; metres, origin at field centre, {@code +Z} up &mdash; so gravity is
 * {@code -Z} and no coordinates are converted anywhere in this class. A solver working in its own
 * frame is a conversion at every read and write, and a sign error there looks exactly like broken
 * physics.</p>
 *
 * <h2>Why the step size is fixed</h2>
 *
 * <p>A constraint solver's output depends on its step size: the same push with a longer step gives
 * a different answer, and a long enough step lets a fast ball pass clean through a wall between
 * one evaluation and the next. The dashboard's tick is 20&nbsp;ms of simulated time at 1&times;
 * and 160&nbsp;ms at 8&times;, and the on-device clock hands over whatever the last frame actually
 * took. Stepping the solver with that directly would mean the robot behaves differently at
 * different speed multipliers &mdash; a simulator whose physics depends on how fast you are
 * watching it is not one anybody can tune against.</p>
 *
 * <p>So the caller's seconds go into an accumulator and come out as a whole number of
 * {@link #STEP_SECONDS} substeps, with the remainder carried to the next call. The multiplier
 * changes how <em>many</em> substeps a tick runs, never how long one is.</p>
 */
final class OdeFieldPhysics implements FieldPhysics {

    /**
     * The solver's step: 4&nbsp;ms, or 250&nbsp;Hz, held in whole nanoseconds.
     *
     * <p>The rate is chosen against the fastest thing in the world, which is the robot. Verity's
     * free speed is about 1.6&nbsp;m/s, so a 4&nbsp;ms substep advances the chassis 6.3&nbsp;mm
     * &mdash; under a tenth of a POLLEN ball's 71&nbsp;mm diameter, so a ball cannot end up on the
     * far side of the bumper without a contact having been evaluated in between. At the dashboard's
     * own 50&nbsp;Hz the robot would cover 32&nbsp;mm in a step, which is half a ball.</p>
     *
     * <p>Nanoseconds, and an integer, because the accumulator has to be exact. Adding 0.02 fifty
     * times does not give 1.0 in binary floating point, and a step count taken from that sum comes
     * out one short &mdash; so the same second of simulation delivered as fifty ticks and as one
     * call would diverge, which is precisely the determinism this fixed step exists to provide. A
     * 4&nbsp;ms step is also exactly 4,000,000&nbsp;ns and divides the 20&nbsp;ms control cycle
     * five times, so the common case carries no remainder at all.</p>
     */
    private static final long STEP_NANOS = 4_000_000L;

    private static final double STEP_SECONDS = STEP_NANOS / 1e9;

    private static final double NANOS_PER_SECOND = 1e9;

    /** Standard gravity, downward along field {@code -Z}. */
    private static final double GRAVITY_METRES_PER_SECOND_SQUARED = -9.806;

    /**
     * Nominal ball density, for want of a published mass.
     *
     * <p>The game manual gives BioBuzz ball diameters but not their weights, so this is an
     * estimate for a hollow plastic ball: 71&nbsp;mm of POLLEN comes out at 22&nbsp;g and
     * 91&nbsp;mm of NECTAR at 47&nbsp;g, both in the right part of the range for something a robot
     * flings across a field. Nothing observable today depends on the exact figure &mdash; the robot
     * is kinematic, so it pushes with infinite authority, and rolling resistance decelerates a ball
     * independently of its mass &mdash; but ball-on-ball collisions do, and a measured mass belongs
     * here the day somebody puts one on a scale.</p>
     */
    private static final double BALL_DENSITY_KILOGRAMS_PER_CUBIC_METRE = 120.0;

    /** Friction between a ball and anything else: foam tile, polycarbonate wall, robot bumper. */
    private static final double FRICTION = 0.6;

    /**
     * Rolling resistance, as ode4j's rolling-friction coefficient.
     *
     * <p>Without this a ball nudged on a flat floor rolls until it hits something, because an
     * ideal sphere rolling without slipping loses nothing to sliding friction. Foam tiles deform
     * under a ball and that deformation is where the energy goes, so a ball on an FTC field stops
     * within a couple of metres. A simulator where they never stop would teach a driver the wrong
     * thing about every shot they take.</p>
     */
    private static final double ROLLING_RESISTANCE = 0.02;

    /** How much of an impact speed comes back. A game ball on a hard wall is bouncy but not lively. */
    private static final double RESTITUTION = 0.35;

    /** Below this impact speed the bounce is dropped, so a settling ball comes to rest instead of buzzing. */
    private static final double RESTITUTION_THRESHOLD_METRES_PER_SECOND = 0.05;

    /**
     * Contact softness. A small amount of give stops a resting ball from being ejected by the
     * solver's own correction, which is the classic hard-contact jitter.
     */
    private static final double CONTACT_SOFTNESS = 1e-4;

    /**
     * Depth of the perimeter wall boxes, measured outwards from the field edge, and of the floor
     * slab below it.
     *
     * <p>Half a metre for one reason: a box collider pushes a body out of whichever face it is
     * nearest, so a ball squeezed past the middle of a thin wall is ejected through the outside of
     * it. A robot pinning a ball against the perimeter is an ordinary thing to do &mdash; the drive
     * model lets the bumper reach the wall exactly &mdash; and with a 50&nbsp;mm wall that pinch
     * put the ball in the gym, while an infinitely thin floor let the same pinch put one under the
     * field. Nothing is drawn from these numbers, so depth is free.</p>
     */
    private static final double WALL_THICKNESS_METRES = 0.5;

    private static final double FLOOR_DEPTH_METRES = 0.5;

    /**
     * Contacts kept per geom pair. A sphere against a box produces at most a few; four leaves room
     * without inviting the solver to spend time on a pair that cannot need it.
     */
    private static final int MAX_CONTACTS = 4;

    /**
     * How hard a sweep may pull, as a multiple of the ball's own weight.
     *
     * <p>A roller reaches a ball through friction, so what it can apply is bounded by what it
     * presses with &mdash; past that it slips, and a slipping roller is the normal state of an
     * intake with a ball already seated in it. Twice the ball's weight snatches a POLLEN off the
     * floor in seventy milliseconds and holds it against the chassis without arguing.</p>
     *
     * <p>This started at a flat ten newtons, chosen to reach surface speed in a single solver
     * step, which is about forty-six times a POLLEN's weight. A held ball has nowhere to go, so
     * that force went on being applied into the chassis forever: balls ended up crushed two
     * centimetres into the floor and one was forced inside the robot's own footprint. A roller
     * that cannot slip is not a roller, it is a hydraulic ram.</p>
     */
    private static final double SWEEP_TRACTION = 2.0;

    /**
     * How far a body has to move before this world calls itself moving.
     *
     * <p>A fifth of a millimetre. This is a <em>displacement</em> test and not a velocity one for a
     * reason worth writing down: a ball held in a running intake has a velocity forever &mdash; the
     * roller pushes, the chassis pushes back, and the solver leaves a residue in the last digits
     * &mdash; while going precisely nowhere. Reporting that as motion published fifty identical
     * body frames a second for as long as a driver held the trigger, rebuilt the camera's scene
     * just as often, and had the browser interpolating and redrawing a field that was not
     * changing.</p>
     */
    private static final double MOVED_METRES = 2e-4;

    /** The same, for spin: a thousandth of a radian between reports. */
    private static final double TURNED_RADIANS = 1e-3;

    private final DWorld world;
    private final DSpace space;
    private final DJointGroup contactGroup;
    private final DContactBuffer contacts = new DContactBuffer(MAX_CONTACTS);
    private final List<Ball> balls;
    private final DriveModel drive;

    /** The sweeping surfaces this robot carries, by the name their servo is configured under. */
    private final Map<String, Roller> rollers = new LinkedHashMap<>();

    /** The robot's collision box, or null when the session's robot has no drivetrain. */
    private final DBody robot;

    /** Simulated time handed over but not yet stepped, in whole nanoseconds; see {@link #STEP_NANOS}. */
    private long pendingNanos;

    /** Whether the last {@link #advance} left anything somewhere new; see {@link #moving()}. */
    private boolean movedLastTick;

    /** Reused across ticks so a stepping world allocates nothing per tick. */
    private final DQuaternion heading = new DQuaternion();

    /**
     * The beam every distance sensor is measured with, outside the space so the ordinary collision
     * sweep never finds it. Its length and aim are set per reading; see {@link #rangeAlong}.
     */
    private final DRay beam = OdeHelper.createRay(null, 1.0);

    /**
     * ode4j's collider tables are process-wide, and so is their initialisation.
     *
     * <p>In a static initialiser rather than in the constructor because {@code initODE2} is not
     * idempotent: calling it again while another world is alive resets state that world is using,
     * and what that produces is not an exception but slightly different physics. A dashboard
     * session rebuilds this world on every INIT, and the unit tests build one per test method, so
     * "a second world exists" is the normal case rather than the exotic one. This cost an afternoon
     * to a test that passed alone and failed beside its neighbours.</p>
     */
    static {
        OdeHelper.initODE2(0);
    }

    OdeFieldPhysics(List<GameElement> arrangement, FieldConfig field, DriveModel drive) {

        this.drive = drive;
        this.world = OdeHelper.createWorld();
        this.space = OdeHelper.createHashSpace();
        this.contactGroup = OdeHelper.createJointGroup();

        world.setGravity(0.0, 0.0, GRAVITY_METRES_PER_SECOND_SQUARED);
        // A resting ball is allowed to sink a fraction of a millimetre into the floor rather than
        // being pushed back out hard: the surface layer is what buys stillness at rest.
        world.setContactSurfaceLayer(0.0005);
        world.setContactMaxCorrectingVel(2.0);
        world.setQuickStepNumIterations(20);

        buildFloor(field);
        buildPerimeter(field);

        this.balls = new ArrayList<>(arrangement.size());
        for (int id = 0; id < arrangement.size(); id++) {
            balls.add(new Ball(id, arrangement.get(id)));
        }

        this.robot = drive == null ? null : buildRobot(drive.robot().chassis());
        if (robot != null) {
            buildRollers(drive.robot());
            carryRobot();
        }
    }

    /**
     * A driven zone per sweeping servo: where the mechanism reaches, and how fast it runs.
     *
     * <p>Not a rigid surface, and that was the second attempt. The first put a box across the
     * mouth with ODE's own moving-surface contacts, which is exactly what an intake roller is
     * &mdash; and it threw balls away at speed. A box's front face gives the solver a contact whose
     * normal is the direction the surface is supposed to run in, and there is no tangent left to
     * run it along; the drag became a shove. A box also has to be solid, so the mouth it modelled
     * was a wall a ball could never get into.</p>
     *
     * <p>So the roller is a region that pulls what is inside it, as a compliant wheel spinning
     * faster than the ball does. Everything around it is still the world: the ball is stopped by
     * the chassis it is dragged against, another ball can knock it out, reversing the servo spits
     * it back out, and nothing anywhere is marked "held". What is given up is the roller as an
     * obstacle &mdash; a ball cannot bounce off an idle intake &mdash; and that is the trade a
     * jointed mechanism with real geometry would buy back.</p>
     */
    private void buildRollers(RobotConfig configured) {
        for (ServoConfig servo : configured.servos().values()) {
            if (servo.sweepsBalls()) {
                rollers.put(servo.name(),
                        new Roller(servo.sweep(), servo.surfaceMetresPerSecond()));
            }
        }
    }

    /**
     * Drags whatever is inside a running sweep towards the robot, once per solver step.
     *
     * <p>A force rather than a velocity, so the ball is still something the rest of the world can
     * argue with: it stops when it reaches the chassis, it is slowed by the floor, and it can be
     * blocked by another ball. The force is whatever would reach the roller's surface speed in one
     * step, capped &mdash; past the cap a real roller slips rather than pushing harder, and without
     * one a ball pinned against the chassis would be crushed into it.</p>
     */
    private void driveSweeps() {
        if (drive == null) {
            return;
        }
        Pose2d pose = drive.pose();
        double cos = Math.cos(pose.heading());
        double sin = Math.sin(pose.heading());

        for (Roller roller : rollers.values()) {
            if (roller.power == 0.0) {
                continue;
            }
            // Rearwards for a positive power: an intake pulls in.
            double wanted = -roller.power * roller.surfaceMetresPerSecond;
            for (Ball ball : balls) {
                if (!ball.inside(pose, roller.sweep)) {
                    continue;
                }
                DVector3C moving = ball.velocity();
                double along = moving.get0() * cos + moving.get1() * sin;
                double force = (wanted - along) * ball.mass / STEP_SECONDS;

                // Whatever it would take to reach surface speed, or whatever the roller can
                // actually grip with -- whichever is less. A ball already seated has nowhere to go,
                // so this is the limit that applies for as long as it is held.
                double traction = SWEEP_TRACTION * ball.mass
                        * Math.abs(GRAVITY_METRES_PER_SECOND_SQUARED);
                if (force > traction) {
                    force = traction;
                } else if (force < -traction) {
                    force = -traction;
                }
                ball.push(force * cos, force * sin);
            }
        }
    }

    /**
     * The floor, as a slab rather than the infinite plane it used to be.
     *
     * <p>A plane is the obvious answer and it cannot recover a mistake. Planes are one-sided and
     * thin: a ball pushed through one is simply on the wrong side of it, and there is nothing to
     * push it back. That happened &mdash; a ball pinched between the perimeter and a chassis that
     * pushes with unbounded force was ejected downwards and ended up under the field. A slab
     * pushes a body out through its nearest face, and for anything that has just been squeezed
     * into the top of it, that is the top.</p>
     *
     * <p>Deep and wide for the same reason the walls are: this only works while the body is nearer
     * the face it came in through than any other.</p>
     */
    private void buildFloor(FieldConfig field) {
        double span = field.sizeMetres() + 4.0 * WALL_THICKNESS_METRES;
        DBox slab = OdeHelper.createBox(space, span, span, FLOOR_DEPTH_METRES);
        slab.setPosition(0.0, 0.0, -FLOOR_DEPTH_METRES / 2.0);
    }

    /**
     * Four walls as boxes rather than as the infinite planes that would have been cheaper.
     *
     * <p>A plane per side makes the field a sealed box, and then "no ball ever leaves the field" is
     * a property of the collision geometry rather than anything worth testing. A real perimeter is
     * 312&nbsp;mm high and a ball can go over it, which is a thing that happens in matches and a
     * thing an OpMode that launches game elements should be able to do wrong in simulation.</p>
     */
    private void buildPerimeter(FieldConfig field) {
        double half = field.halfExtentMetres();
        double height = field.wallHeightMetres();
        double span = field.sizeMetres() + 2.0 * WALL_THICKNESS_METRES;
        double offset = half + WALL_THICKNESS_METRES / 2.0;

        wall(span, WALL_THICKNESS_METRES, height, 0.0, offset, height / 2.0);
        wall(span, WALL_THICKNESS_METRES, height, 0.0, -offset, height / 2.0);
        wall(WALL_THICKNESS_METRES, span, height, offset, 0.0, height / 2.0);
        wall(WALL_THICKNESS_METRES, span, height, -offset, 0.0, height / 2.0);
    }

    private void wall(double lengthX, double lengthY, double lengthZ,
                      double x, double y, double z) {
        DBox box = OdeHelper.createBox(space, lengthX, lengthY, lengthZ);
        box.setPosition(x, y, z);
    }

    /**
     * The robot as one box, reaching from the floor to the deck.
     *
     * <p>The footprint is the same rectangle {@link DriveModel} stops against the walls with, so
     * the robot that shoves a ball is the robot that hits the perimeter. It reaches the floor
     * because a mecanum robot's wheels and frame rails do: balls do not roll under Verity, they get
     * shoved, and a box floating at deck height would swallow them instead.</p>
     *
     * <p>Kinematic, not dynamic. It is moved by the drive model and by nothing else, so a ball
     * cannot slow the robot down &mdash; which is wrong, and is exactly what making the chassis
     * dynamic will fix. Until then the asymmetry is at least honest: the robot pushes, the ball is
     * pushed.</p>
     */
    private DBody buildRobot(ChassisConfig chassis) {
        DBody body = OdeHelper.createBody(world);
        body.setKinematic();

        DBox box = OdeHelper.createBox(space, chassis.lengthMetres(), chassis.widthMetres(),
                chassis.deckHeightMetres());
        box.setBody(body);
        // The geom sits half the box above the body's origin, so the body's origin is the pose the
        // drive model publishes: on the floor, at the centre of the footprint.
        box.setOffsetPosition(0.0, 0.0, chassis.deckHeightMetres() / 2.0);
        return body;
    }

    /**
     * Puts back any ball that has ended up somewhere no ball can be: inside the robot, or below
     * the floor.
     *
     * <p>This is a repair, not physics, and it is here because the solver has no legal answer to
     * give. The chassis is kinematic: it presses with unbounded force and cannot be slowed by what
     * it presses on. A ball caught between it and the perimeter is an over-constrained problem, so
     * something has to yield, and every way it yields is wrong &mdash; the ball squeezed through
     * the wall and into the gym, or under the floor, or into the footprint, where the chassis then
     * held it down against a floor pushing back and neither won. That last one oscillated at about
     * three metres a second while going nowhere, for the rest of the session: a body frame
     * published fifty times a second, the camera's scene rebuilt as often, and a browser burning
     * six cores redrawing a field that was not changing. It was found as hot fans.</p>
     *
     * <p>Two other fixes were tried first and are worth knowing about. Dropping the downward
     * contacts left the ball with no contacts at all, so the robot drove straight through it.
     * Thickening the floor into a slab, which does fix a ball pushed <em>through</em> a plane,
     * cannot fix this one: the chassis is still above the ball and still infinitely strong.</p>
     *
     * <p>So the world restores the invariant the solver cannot: a ball is never inside the robot
     * and never below the floor. Out through the nearest side, because that is where a ball under
     * a robot goes, and stopped dead rather than flung, because it is being moved out of the way
     * rather than hit. The threshold is a quarter of a radius so that a ball merely touching the
     * bumper is left entirely to the contact solver.</p>
     *
     * <p>This should be deleted when the chassis becomes dynamic. A robot that can be slowed by a
     * ball cannot crush one, and then the solver has an answer again.</p>
     */
    private void repairImpossiblePlaces() {
        Pose2d pose = drive.pose();
        double cos = Math.cos(pose.heading());
        double sin = Math.sin(pose.heading());
        ChassisConfig chassis = drive.robot().chassis();

        for (Ball ball : balls) {
            double radius = ball.radius();
            double margin = radius * 0.25;
            DVector3C at = ball.position();

            double awayX = at.get0() - pose.x();
            double awayY = at.get1() - pose.y();
            double forward = awayX * cos + awayY * sin;
            double left = -awayX * sin + awayY * cos;

            double outForward = chassis.lengthMetres() / 2.0 + radius - Math.abs(forward);
            double outLeft = chassis.widthMetres() / 2.0 + radius - Math.abs(left);
            boolean insideTheRobot = outForward > margin && outLeft > margin
                    && at.get2() - radius < chassis.deckHeightMetres();
            boolean belowTheFloor = at.get2() < radius / 2.0;
            if (!insideTheRobot && !belowTheFloor) {
                continue;
            }

            if (insideTheRobot) {
                // Whichever way out is shorter, and straight out along that axis.
                if (outForward < outLeft) {
                    forward += Math.signum(forward == 0.0 ? 1.0 : forward) * outForward;
                } else {
                    left += Math.signum(left == 0.0 ? 1.0 : left) * outLeft;
                }
            }
            ball.placeAt(
                    pose.x() + forward * cos - left * sin,
                    pose.y() + forward * sin + left * cos,
                    radius);
        }
    }

    @Override
    public void advance(double seconds) {
        if (seconds < 0.0) {
            throw new IllegalArgumentException("cannot advance physics backwards");
        }
        if (robot != null) {
            carryRobot();
            repairImpossiblePlaces();
        }

        pendingNanos += Math.round(seconds * NANOS_PER_SECOND);
        while (pendingNanos >= STEP_NANOS) {
            step();
            pendingNanos -= STEP_NANOS;
        }

        // Asked once per tick, here, rather than recomputed by each of the two callers that want
        // to know. The baseline only moves when something actually did, so a ball creeping by less
        // than the threshold per tick still gets reported once its creep adds up -- rebasing every
        // tick would let a slow drift go unpublished forever.
        boolean moved = false;
        for (Ball ball : balls) {
            if (ball.movedFromBaseline()) {
                moved = true;
                break;
            }
        }
        if (moved) {
            for (Ball ball : balls) {
                ball.rebase();
            }
        }
        movedLastTick = moved;
    }

    private void step() {
        driveSweeps();
        space.collide(null, this::resolve);
        world.quickStep(STEP_SECONDS);
        contactGroup.empty();
    }

    /**
     * Turns one overlapping pair into contact joints the solver can satisfy.
     *
     * <p>Every surface in this world is the same material pairing as far as anything observable
     * goes &mdash; a ball against foam, plastic or a robot &mdash; so there is one set of constants
     * rather than a material table nobody could calibrate.</p>
     */
    private void resolve(Object data, DGeom first, DGeom second) {
        // Two things bolted to the field, or both to the same kinematic robot, cannot move each
        // other: the contact would be built, solved and thrown away every step for nothing.
        if (isImmovable(first) && isImmovable(second)) {
            return;
        }

        int found = OdeHelper.collide(first, second, MAX_CONTACTS, contacts.getGeomBuffer());
        for (int i = 0; i < found; i++) {
            DContact contact = contacts.get(i);
            contact.surface.mode = OdeConstants.dContactBounce
                    | OdeConstants.dContactSoftCFM
                    | OdeConstants.dContactApprox1
                    | OdeConstants.dContactRolling;
            contact.surface.mu = FRICTION;
            contact.surface.bounce = RESTITUTION;
            contact.surface.bounce_vel = RESTITUTION_THRESHOLD_METRES_PER_SECOND;
            contact.surface.soft_cfm = CONTACT_SOFTNESS;
            contact.surface.rho = ROLLING_RESISTANCE;
            contact.surface.rho2 = ROLLING_RESISTANCE;
            // Spinning friction, which is what stops a ball pirouetting on one point forever.
            contact.surface.rhoN = ROLLING_RESISTANCE;

            OdeHelper.createContactJoint(world, contactGroup, contact)
                    .attach(first.getBody(), second.getBody());
        }
    }

    /** Whether a geom has nothing the solver can push: static field geometry, or the robot. */
    private static boolean isImmovable(DGeom geom) {
        DBody body = geom.getBody();
        return body == null || body.isKinematic();
    }

    /**
     * Puts the robot's box where the drive model says the robot is, and gives it the velocity the
     * drive model says it has.
     *
     * <p>Both, not just the position. A kinematic body's velocity is what the solver uses to work
     * out how hard it hits something: teleported frame by frame with a velocity of zero, the robot
     * would still displace balls, but it would displace them as though it had arrived from nowhere
     * &mdash; a shove with no speed behind it, which reads as balls squirting out at random.</p>
     */
    private void carryRobot() {
        Pose2d pose = drive.pose();
        ChassisVelocity velocity = drive.velocity();
        double cos = Math.cos(pose.heading());
        double sin = Math.sin(pose.heading());

        robot.setPosition(pose.x(), pose.y(), 0.0);
        // ODE's quaternion order is (w, x, y, z); a heading is a rotation about field +Z alone.
        heading.set(Math.cos(pose.heading() / 2.0), 0.0, 0.0, Math.sin(pose.heading() / 2.0));
        robot.setQuaternion(heading);

        robot.setLinearVel(
                velocity.forwardMetresPerSecond() * cos - velocity.lateralMetresPerSecond() * sin,
                velocity.forwardMetresPerSecond() * sin + velocity.lateralMetresPerSecond() * cos,
                0.0);
        robot.setAngularVel(0.0, 0.0, velocity.yawRadiansPerSecond());
    }

    @Override
    public List<GameElement> elements() {
        List<GameElement> moved = new ArrayList<>(balls.size());
        for (Ball ball : balls) {
            moved.add(ball.element());
        }
        return Collections.unmodifiableList(moved);
    }

    @Override
    public List<BodyState> bodies() {
        List<BodyState> states = new ArrayList<>(balls.size());
        for (Ball ball : balls) {
            states.add(ball.state());
        }
        return Collections.unmodifiableList(states);
    }

    /** Whether anything has moved since the last tick that said so; see {@link #advance}. */
    @Override
    public boolean moving() {
        return movedLastTick;
    }

    @Override
    public List<GameElement> touching(VolumeConfig volume) {
        if (drive == null) {
            // No drivetrain, so no robot, so nothing to bolt a volume to. An empty answer is the
            // truth: a sensor on a robot that cannot be anywhere is looking at nothing.
            return Collections.emptyList();
        }
        final Pose2d pose = drive.pose();
        List<GameElement> found = new ArrayList<>(2);
        for (Ball ball : balls) {
            GameElement element = ball.element();
            if (RobotFrame.touches(pose, volume, element.centre(), element.radiusMetres())) {
                found.add(element);
            }
        }
        // Nearest to the middle of the volume first, so a sensor with one reading to give reports
        // the ball a real one would be looking at.
        final double middleX = RobotFrame.fieldX(pose, volume.forwardMetres(), volume.leftMetres());
        final double middleY = RobotFrame.fieldY(pose, volume.forwardMetres(), volume.leftMetres());
        Collections.sort(found, new Comparator<GameElement>() {
            @Override
            public int compare(GameElement left, GameElement right) {
                return Double.compare(distanceTo(left), distanceTo(right));
            }

            private double distanceTo(GameElement element) {
                double awayX = element.centre().x() - middleX;
                double awayY = element.centre().y() - middleY;
                double awayZ = element.centre().z() - volume.heightMetres();
                return awayX * awayX + awayY * awayY + awayZ * awayZ;
            }
        });
        return Collections.unmodifiableList(found);
    }

    /**
     * Casts the sensor's beam as an ODE ray and reports what it hits first.
     *
     * <p>A ray geom rather than sphere and plane arithmetic of this class's own, because the beam
     * has to meet whatever is in the world: balls today, and the field's real CAD geometry once
     * that lands. Hand-rolled intersections would have to grow a case per shape, and the cases it
     * did not have would read as a sensor that sees through things.</p>
     *
     * <p>The ray is created outside the space and collided against it by hand. A ray living in the
     * space would be found by the ordinary collision sweep every step, and a measurement would
     * start pushing the things it measures.</p>
     */
    @Override
    public double rangeAlong(SensorConfig sensor) {
        if (drive == null) {
            return Double.NaN;
        }
        Pose2d pose = drive.pose();
        double aim = pose.heading() + Math.toRadians(sensor.yawDegrees());
        double pitch = Math.toRadians(sensor.pitchDegrees());

        double originX = RobotFrame.fieldX(pose, sensor.forwardMetres(), sensor.leftMetres());
        double originY = RobotFrame.fieldY(pose, sensor.forwardMetres(), sensor.leftMetres());

        // One ray, reused: a sensor is read on every tick, and a geom per reading would be a few
        // hundred allocations a second to answer a question that never changes shape. It lives
        // outside the space on purpose -- a ray the ordinary collision sweep could find would
        // start pushing the things it measures.
        beam.setLength(sensor.maxRangeMetres());
        beam.set(originX, originY, sensor.heightMetres(),
                Math.cos(pitch) * Math.cos(aim), Math.cos(pitch) * Math.sin(aim), Math.sin(pitch));

        Nearest nearest = new Nearest(originX, originY, sensor.heightMetres());
        beam.collide2(space, nearest, this::measure);
        return nearest.metres;
    }

    /** Keeps the closest thing a beam met, and where the beam started, to measure against. */
    private static final class Nearest {

        private final double x;
        private final double y;
        private final double z;
        private double metres = Double.NaN;

        Nearest(double x, double y, double z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }
    }

    private void measure(Object data, DGeom beam, DGeom hit) {
        // Its own robot is not something a sensor can see: a beam that stopped on the bumper it is
        // bolted to would read zero for the whole match.
        if (robot != null && hit.getBody() == robot) {
            return;
        }
        Nearest nearest = (Nearest) data;
        int found = OdeHelper.collide(beam, hit, MAX_CONTACTS, contacts.getGeomBuffer());
        for (int i = 0; i < found; i++) {
            DVector3C where = contacts.get(i).getContactGeom().pos;
            double metres = Math.sqrt(
                    (where.get0() - nearest.x) * (where.get0() - nearest.x)
                            + (where.get1() - nearest.y) * (where.get1() - nearest.y)
                            + (where.get2() - nearest.z) * (where.get2() - nearest.z));
            if (Double.isNaN(nearest.metres) || metres < nearest.metres) {
                nearest.metres = metres;
            }
        }
    }

    @Override
    public void setSweepPower(String servoName, double power) {
        Roller roller = rollers.get(servoName);
        if (roller == null) {
            throw new IllegalArgumentException("no sweeping servo named \"" + servoName
                    + "\"; the ones this robot declares are " + rollers.keySet());
        }
        roller.power = power;
    }

    /**
     * One sweeping mechanism: where it reaches, how fast its surface runs, and what it is doing.
     *
     * <p>The power is written from outside on each tick and read inside the solver's loop, on the
     * same thread in both cases &mdash; simulated time only passes on the thread advancing it.</p>
     */
    private static final class Roller {

        private final VolumeConfig sweep;
        private final double surfaceMetresPerSecond;
        private double power;

        Roller(VolumeConfig sweep, double surfaceMetresPerSecond) {
            this.sweep = sweep;
            this.surfaceMetresPerSecond = surfaceMetresPerSecond;
        }
    }

    /** Innermost first: joints belong to the world, geoms to the space. */
    @Override
    public void destroy() {
        contactGroup.destroy();
        space.destroy();
        world.destroy();
    }

    /** One ball: the sphere the solver moves, and the description the renderers draw. */
    private final class Ball {

        private final int id;
        private final GameElement template;
        private final DBody body;

        /** Kept because a sweep's force is worked out from it, once per ball per solver step. */
        private final double mass;

        /** Where this ball was the last time the world said something had moved. */
        private double baseX;
        private double baseY;
        private double baseZ;
        private double baseQx;
        private double baseQy;
        private double baseQz;
        private double baseQw = 1.0;

        Ball(int id, GameElement template) {
            this.id = id;
            this.template = template;

            double radius = template.radiusMetres();
            this.body = OdeHelper.createBody(world);

            double volume = 4.0 / 3.0 * Math.PI * radius * radius * radius;
            this.mass = volume * BALL_DENSITY_KILOGRAMS_PER_CUBIC_METRE;

            DMass inertia = OdeHelper.createMass();
            inertia.setSphereTotal(mass, radius);
            body.setMass(inertia);

            DSphere sphere = OdeHelper.createSphere(space, radius);
            sphere.setBody(body);

            Vec3 centre = template.centre();
            body.setPosition(centre.x(), centre.y(), centre.z());
            // Where it starts is where its first movement is measured from, so the arrangement
            // itself never reads as motion.
            rebase();
        }

        GameElement element() {
            DVector3C position = body.getPosition();
            return template.movedTo(new Vec3(position.get0(), position.get1(), position.get2()));
        }

        BodyState state() {
            DVector3C position = body.getPosition();
            DQuaternionC rotation = body.getQuaternion();
            return new BodyState(id,
                    position.get0(), position.get1(), position.get2(),
                    rotation.get1(), rotation.get2(), rotation.get3(), rotation.get0());
        }

        /**
         * Whether this ball has been somewhere else since the last time the world reported it.
         *
         * <p>Not whether it has a velocity. A ball held in a running intake has one of those
         * forever and goes nowhere.</p>
         */
        boolean movedFromBaseline() {
            DVector3C at = body.getPosition();
            if (Math.abs(at.get0() - baseX) > MOVED_METRES
                    || Math.abs(at.get1() - baseY) > MOVED_METRES
                    || Math.abs(at.get2() - baseZ) > MOVED_METRES) {
                return true;
            }
            // Two unit quaternions an angle apart have a dot product of cos(angle/2), so a small
            // turn shows up as a dot product a hair under one.
            DQuaternionC turned = body.getQuaternion();
            double dot = Math.abs(turned.get0() * baseQw + turned.get1() * baseQx
                    + turned.get2() * baseQy + turned.get3() * baseQz);
            return dot < Math.cos(TURNED_RADIANS / 2.0);
        }

        /** Takes where it is now as the place its next movement will be measured from. */
        void rebase() {
            DVector3C at = body.getPosition();
            baseX = at.get0();
            baseY = at.get1();
            baseZ = at.get2();
            DQuaternionC turned = body.getQuaternion();
            baseQw = turned.get0();
            baseQx = turned.get1();
            baseQy = turned.get2();
            baseQz = turned.get3();
        }

        /** Whether this ball's surface reaches into a box bolted to the robot. */
        boolean inside(Pose2d pose, VolumeConfig volume) {
            DVector3C at = body.getPosition();
            return RobotFrame.touches(pose, volume,
                    at.get0(), at.get1(), at.get2(), template.radiusMetres());
        }

        double radius() {
            return template.radiusMetres();
        }

        DVector3C position() {
            return body.getPosition();
        }

        /**
         * Puts this ball somewhere and stops it dead, for the one caller that has the right to:
         * whatever a teleport landed on is being moved out of the way, not hit.
         */
        void placeAt(double x, double y, double z) {
            body.setPosition(x, y, z);
            body.setLinearVel(0.0, 0.0, 0.0);
            body.setAngularVel(0.0, 0.0, 0.0);
            // Deliberately not rebased. Being moved out from under a robot is the largest single
            // jump a ball ever makes, and it is the one a watching browser most needs to hear
            // about: rebasing here hid the correction from the movement check, so the ball was
            // teleported on the server and left drawn where it had been.
        }

        DVector3C velocity() {
            return body.getLinearVel();
        }

        /** Adds a horizontal force for this step, which is how a sweep pulls. */
        void push(double newtonsX, double newtonsY) {
            body.addForce(newtonsX, newtonsY, 0.0);
        }
    }
}
