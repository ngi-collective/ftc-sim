package org.ngicollective.testframework.physics;

import org.ngicollective.testframework.camera.GameElement;
import org.ngicollective.testframework.camera.Vec3;
import org.ngicollective.testframework.sim.ChassisConfig;
import org.ngicollective.testframework.sim.ChassisVelocity;
import org.ngicollective.testframework.sim.DriveModel;
import org.ngicollective.testframework.sim.FieldConfig;
import org.ngicollective.testframework.sim.Pose2d;
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
import org.ode4j.ode.DSpace;
import org.ode4j.ode.DSphere;
import org.ode4j.ode.DWorld;
import org.ode4j.ode.OdeConstants;
import org.ode4j.ode.OdeHelper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

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
     * Depth of the perimeter wall boxes, measured outwards from the field edge.
     *
     * <p>Half a metre for one reason: a box collider pushes a body out of whichever face it is
     * nearest, so a ball squeezed past the middle of a thin wall is ejected through the outside of
     * it. A robot pinning a ball against the perimeter is an ordinary thing to do &mdash; the drive
     * model lets the bumper reach the wall exactly &mdash; and with a 50&nbsp;mm wall that pinch
     * put the ball in the gym. Nothing is drawn from this number, so depth is free.</p>
     */
    private static final double WALL_THICKNESS_METRES = 0.5;

    /**
     * Contacts kept per geom pair. A sphere against a plane produces one, against a box at most a
     * few; four leaves room without inviting the solver to spend time on a pair that cannot need it.
     */
    private static final int MAX_CONTACTS = 4;

    /**
     * Speeds below which a body counts as still: a tenth of a millimetre a second, and a
     * thousandth of a radian a second.
     *
     * <p>Not zero, because a constraint solver resolving gravity against a contact leaves a
     * residue in the last few digits forever. Treating that as motion would publish a body frame
     * on every tick of a field nobody is touching.</p>
     */
    private static final double STILL_METRES_PER_SECOND = 1e-4;
    private static final double STILL_RADIANS_PER_SECOND = 1e-3;

    private final DWorld world;
    private final DSpace space;
    private final DJointGroup contactGroup;
    private final DContactBuffer contacts = new DContactBuffer(MAX_CONTACTS);

    private final List<Ball> balls;
    private final DriveModel drive;

    /** The robot's collision box, or null when the session's robot has no drivetrain. */
    private final DBody robot;

    /** Simulated time handed over but not yet stepped, in whole nanoseconds; see {@link #STEP_NANOS}. */
    private long pendingNanos;

    /** Reused across ticks so a stepping world allocates nothing per tick. */
    private final DQuaternion heading = new DQuaternion();

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

        OdeHelper.createPlane(space, 0.0, 0.0, 1.0, 0.0);
        buildPerimeter(field);

        this.balls = new ArrayList<>(arrangement.size());
        for (int id = 0; id < arrangement.size(); id++) {
            balls.add(new Ball(id, arrangement.get(id)));
        }

        this.robot = drive == null ? null : buildRobot(drive.robot().chassis());
        if (robot != null) {
            carryRobot();
        }
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

    @Override
    public void advance(double seconds) {
        if (seconds < 0.0) {
            throw new IllegalArgumentException("cannot advance physics backwards");
        }
        if (robot != null) {
            carryRobot();
        }

        pendingNanos += Math.round(seconds * NANOS_PER_SECOND);
        while (pendingNanos >= STEP_NANOS) {
            step();
            pendingNanos -= STEP_NANOS;
        }
    }

    private void step() {
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

    @Override
    public boolean moving() {
        for (Ball ball : balls) {
            if (ball.moving()) {
                return true;
            }
        }
        return false;
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

        Ball(int id, GameElement template) {
            this.id = id;
            this.template = template;

            double radius = template.radiusMetres();
            this.body = OdeHelper.createBody(world);

            DMass mass = OdeHelper.createMass();
            double volume = 4.0 / 3.0 * Math.PI * radius * radius * radius;
            mass.setSphereTotal(volume * BALL_DENSITY_KILOGRAMS_PER_CUBIC_METRE, radius);
            body.setMass(mass);

            DSphere sphere = OdeHelper.createSphere(space, radius);
            sphere.setBody(body);

            Vec3 centre = template.centre();
            body.setPosition(centre.x(), centre.y(), centre.z());
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

        boolean moving() {
            return body.getLinearVel().length() > STILL_METRES_PER_SECOND
                    || body.getAngularVel().length() > STILL_RADIANS_PER_SECOND;
        }
    }
}
