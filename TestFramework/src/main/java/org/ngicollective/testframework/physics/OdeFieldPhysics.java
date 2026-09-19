package org.ngicollective.testframework.physics;

import org.ngicollective.testframework.camera.GameElement;
import org.ngicollective.testframework.camera.Pivot;
import org.ngicollective.testframework.camera.Pose3d;
import org.ngicollective.testframework.camera.Solid;
import org.ngicollective.testframework.camera.Structure;
import org.ngicollective.testframework.camera.Vec3;
import org.ngicollective.testframework.sim.Chassis;
import org.ngicollective.testframework.sim.ChassisConfig;
import org.ngicollective.testframework.sim.ChassisVelocity;
import org.ngicollective.testframework.sim.FieldConfig;
import org.ngicollective.testframework.sim.LauncherConfig;
import org.ngicollective.testframework.sim.Pose2d;
import org.ngicollective.testframework.sim.RobotConfig;
import org.ngicollective.testframework.sim.SensorConfig;
import org.ngicollective.testframework.sim.ServoConfig;
import org.ngicollective.testframework.sim.VolumeConfig;
import org.ode4j.math.DMatrix3;
import org.ode4j.math.DQuaternionC;
import org.ode4j.math.DVector3;
import org.ode4j.math.DVector3C;
import org.ode4j.ode.DBody;
import org.ode4j.ode.DBox;
import org.ode4j.ode.DContact;
import org.ode4j.ode.DContactBuffer;
import org.ode4j.ode.DGeom;
import org.ode4j.ode.DJoint;
import org.ode4j.ode.DHingeJoint;
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
 * dynamic box driven by what its wheels can grip &mdash; see {@link OdeChassis}. Everything is in
 * the FTC field
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
    /** The robot's own numbers, or null when the session's robot has no drivetrain. */
    private final RobotConfig robotConfig;

    /** The robot in this world, or null when it has no drivetrain and so no place on the field. */
    private final OdeChassis chassis;

    /** The sweeping surfaces this robot carries, by the name their servo is configured under. */
    private final Map<String, Roller> rollers = new LinkedHashMap<>();

    /** The flywheel launchers it carries, by the name of the motor that spins each one. */
    private final Map<String, Launcher> launchers = new LinkedHashMap<>();

    /** The structures that can turn: a HIVE each, on a competition field. */
    private final List<Hinge> hinges = new ArrayList<>();

    /**
     * The floor slab, kept so the chassis can be excluded from scraping along it: see
     * {@link #isTheRobotOnTheFloor}.
     */
    private final DBox floor;

    /** Simulated time handed over but not yet stepped, in whole nanoseconds; see {@link #STEP_NANOS}. */
    private long pendingNanos;

    /** Whether the last {@link #advance} left anything somewhere new; see {@link #moving()}. */
    private boolean movedLastTick;

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

    OdeFieldPhysics(List<GameElement> arrangement, List<Structure> structures, FieldConfig field,
                    RobotConfig robot) {

        this.robotConfig = robot;
        this.world = OdeHelper.createWorld();
        this.space = OdeHelper.createHashSpace();
        this.contactGroup = OdeHelper.createJointGroup();

        world.setGravity(0.0, 0.0, GRAVITY_METRES_PER_SECOND_SQUARED);
        // A resting ball is allowed to sink a fraction of a millimetre into the floor rather than
        // being pushed back out hard: the surface layer is what buys stillness at rest.
        world.setContactSurfaceLayer(0.0005);
        world.setContactMaxCorrectingVel(2.0);
        world.setQuickStepNumIterations(20);

        this.floor = buildFloor(field);
        buildPerimeter(field);
        for (Structure structure : structures) {
            buildStructure(structure);
        }

        this.balls = new ArrayList<>(arrangement.size());
        for (int id = 0; id < arrangement.size(); id++) {
            balls.add(new Ball(id, arrangement.get(id)));
        }

        this.chassis = robot == null ? null : new OdeChassis(world, space, robot, field);
        if (chassis != null) {
            buildRollers(robot);
            buildLaunchers(robot);
            chassis.onPlaced(this::evictBallsFromFootprint);
        }
    }

    /**
     * How close to a stop counts as having arrived there, for the purpose of counting TIPs.
     *
     * <p>A degree. The solver satisfies a joint stop to within a fraction of one, and a HIVE
     * pressed against its damper by the hold torque sits a hair short of the limit forever, so an
     * exact comparison would count no TIPs at all.</p>
     */
    private static final double ARRIVED_RADIANS = Math.toRadians(1.0);

    /**
     * Marks a geom as part of the field's own furniture; see {@link #isFieldStructure}.
     *
     * <p>A marker on the geom rather than a set to look pairs up in, because this is read inside
     * the collision callback for every overlapping pair in the world.</p>
     */
    private static final Object FIELD_STRUCTURE = new Object();

    /**
     * One structure's collision geometry: static geoms straight off its own primitives, unless it
     * turns on something.
     *
     * <p>Static is the case that matters for almost all of it: a FLOWER is bolted to the
     * perimeter, and a geom with no body is the cheapest thing in the world, since the collision
     * sweep skips any pair of them and twenty-odd little boxes around a FLOWER's rim cost contacts
     * only against the balls and the robot that actually reach them.</p>
     *
     * <p>Built from {@link Structure#collided()} and never from what the structure draws. The
     * rim of a FLOWER is one open cylinder as a drawing and a dozen boxes here, because ode4j's
     * cylinders are solid and a solid one would plug the hole a robot is shooting POLLEN
     * through.</p>
     */
    private void buildStructure(Structure structure) {
        Pivot pivot = structure.pivot();
        DBody body = pivot == null ? null : buildPivotBody(structure.name(), pivot);

        for (Solid solid : structure.collided()) {
            DGeom geom = solid.shape() == Solid.Shape.BOX
                    ? OdeHelper.createBox(space,
                            solid.lengthX(), solid.lengthY(), solid.lengthZ())
                    : OdeHelper.createCylinder(space,
                            solid.radiusMetres(), solid.lengthMetres());
            geom.setData(FIELD_STRUCTURE);

            Pose3d pose = solid.pose();
            Vec3 at = pose.position();

            // A rotation's columns are the geom's own axes in world coordinates, and a Solid's
            // axes are exactly forward/left/up. A cylinder's axis is its local +Z in both
            // conventions, so a ring lying flat here needs no correction anywhere.
            //
            // Written out by rows, because DMatrix3.setCol does not set a column: ode4j stores
            // ODE's row-major dMatrix3, so setCol(i, v) writes row i, and assigning
            // forward/left/up through it installs the transpose. Every FLOWER survived that for
            // three reasons worth writing down, since between them they hid it completely. A
            // solid's position is set separately, so only its turn was ever wrong. Transposing a
            // yaw-only rotation gives a yaw of -yaw, which is the identity for the many solids at
            // yaw zero. And a FLOWER's twelve rim boxes stayed where they were and only faced the
            // wrong way, which still closes a ring of one-inch chords against a 2.8 in ball,
            // while an upright cylinder's axis is +Z either way round.
            //
            // The first solid that leaned was an A-frame leg, whose collider ended up somewhere a
            // ball fell straight past. The nine arguments below are in (row, column) order, so
            // each line here is one row and each axis reads downward as one column.
            DMatrix3 rotation = new DMatrix3(
                    pose.forward().x(), pose.left().x(), pose.up().x(),
                    pose.forward().y(), pose.left().y(), pose.up().y(),
                    pose.forward().z(), pose.left().z(), pose.up().z());

            if (body == null) {
                geom.setPosition(at.x(), at.y(), at.z());
                geom.setRotation(rotation);
            } else {
                // Attached to the body, so the pose becomes an offset from it: the body sits on
                // the pivot with no rotation of its own at the angle the structure was built at,
                // which makes each offset the solid's own field pose less the pivot point. Set
                // after setBody, which resets any offset already on the geom.
                geom.setBody(body);
                geom.setOffsetPosition(at.x() - pivot.point().x(), at.y() - pivot.point().y(),
                        at.z() - pivot.point().z());
                geom.setOffsetRotation(rotation);
            }
        }
    }

    /**
     * The body a pivoting structure's geometry hangs off, hinged to the field.
     *
     * <p>The body sits <em>on</em> the pivot and carries its centre of mass there, which is what
     * makes gravity produce no torque about the axis: the weight goes straight into the anchor.
     * That is deliberate and it is the whole model &mdash; see {@link Pivot} for why a HIVE's
     * measured overhang cannot be what holds it &mdash; so the only things that can turn one are
     * the hold torque, the damper, and whatever hits it.</p>
     *
     * <p>The inertia is the same figure about all three axes. Only the one about the hinge can
     * ever be felt, because the joint removes the other five degrees of freedom, and inventing a
     * plausible-looking tensor for the two nobody can observe would be five more numbers to keep
     * consistent with a geometry that is already only an effective model.</p>
     */
    private DBody buildPivotBody(String name, Pivot pivot) {
        DBody body = OdeHelper.createBody(world);
        body.setPosition(pivot.point().x(), pivot.point().y(), pivot.point().z());

        DMass mass = OdeHelper.createMass();
        double inertia = pivot.inertiaKilogramMetresSquared();
        mass.setParameters(pivot.massKilograms(), 0.0, 0.0, 0.0,
                inertia, inertia, inertia, 0.0, 0.0, 0.0);
        body.setMass(mass);

        DHingeJoint joint = OdeHelper.createHingeJoint(world);
        // To the static environment, which is the A-frame: a HIVE is held by a field that cannot
        // be moved, so there is no second body for the reaction to go into.
        joint.attach(body, null);
        joint.setAnchor(pivot.point().x(), pivot.point().y(), pivot.point().z());
        joint.setAxis(pivot.axis().x(), pivot.axis().y(), pivot.axis().z());

        // Stops are relative to the angle the joint was created at, which is the angle the
        // structure's geometry was built at. They are the manual's dampers: reaching one is both
        // halves of its definition of a completed TIP at once.
        joint.setParamLoStop(pivot.lowRadians() - pivot.angleRadians());
        joint.setParamHiStop(pivot.highRadians() - pivot.angleRadians());
        // No bounce: a HIVE arriving at its damper stays there. With any, one hard shot could
        // rebound over centre and score two TIPs.
        joint.setParamBounce(0.0);
        // And a stop that does not give. A compliant one lets a load below the hold torque push
        // the HIVE a few degrees off its damper, and a few degrees is all it takes: the hold falls
        // away towards the middle while the balls inside roll outwards as the CELL flattens, so
        // anything that leaves the stop at all runs away. That put the tip threshold about an
        // eighth below the hold torque and made it a property of the solver's error correction
        // rather than of the number this class states.
        joint.setParam(DJoint.PARAM_N.dParamStopERP1, 1.0);
        joint.setParam(DJoint.PARAM_N.dParamStopCFM1, 0.0);

        hinges.add(new Hinge(name, pivot, joint));
        return body;
    }

    /**
     * Holds every pivoting structure in place, or lets it go, once per solver step.
     *
     * <p>Two torques: the bi-stable hold, which is what "enough POLLEN or NECTAR" has to beat, and
     * the damper, which is viscous and is what stops a tip from accelerating the whole way round
     * and slamming. Everything else that turns a HIVE is a contact the solver worked out for
     * itself &mdash; the weight of the balls resting in a raised CELL, and the impact of the next
     * one arriving. That is the point of hinging it rather than scripting the tip: nothing here
     * decides that a tip has happened.</p>
     */
    private void driveHinges() {
        for (Hinge hinge : hinges) {
            double angle = hinge.angle();
            double torque = hinge.pivot.holdAt(angle)
                    - hinge.pivot.dampingNewtonMetreSecondsPerRadian() * hinge.joint.getAngleRate();
            hinge.joint.addTorque(torque);
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
     * A throwing zone per launcher: where the wheel is, how hard it grips, and where it points.
     *
     * <p>The same shape as {@link #buildRollers} and for the same reason &mdash; a mechanism is a
     * region of space with a state, not a body &mdash; but the two do opposite things to what they
     * find. A roller adds a force and lets the world argue with it. A launcher sets a velocity,
     * because the few centimetres over which a flywheel accelerates a ball are below anything this
     * step size can resolve: at 250&nbsp;Hz a ball leaving at 7&nbsp;m/s moves 28&nbsp;mm in a
     * step, so an acceleration modelled as a force would have to happen inside one anyway.</p>
     */
    private void buildLaunchers(RobotConfig configured) {
        for (LauncherConfig launcher : configured.launchers().values()) {
            launchers.put(launcher.motorName(), new Launcher(launcher));
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
        if (chassis == null) {
            return;
        }
        Pose2d pose = chassis.pose();
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

                // And the same push back on the robot, which is what a light robot being pulled
                // towards the ball it is intaking actually feels. While the chassis could not be
                // moved by anything this was simply missing, and a roller that pushes a ball
                // without being pushed back is the one thing in this world that broke Newton.
                chassis.addReaction(-force * cos, -force * sin,
                        roller.sweep.forwardMetres(), roller.sweep.leftMetres());
            }
        }
    }

    /**
     * Throws whatever is inside a spinning launcher, once per solver step.
     *
     * <p>Exit speed is the wheel's surface speed times what the mechanism can transfer, so a ball
     * fed to a flywheel that has not finished spinning up dribbles out instead of flying &mdash;
     * which is the mistake this whole seam exists to let an OpMode make. Nothing decides that a
     * shot has "happened": a ball in the mouth is given the velocity the wheel can give it, on
     * every step it is still in there, which is what a hood accelerating a ball over its length
     * amounts to. The second and third of those steps add nothing, because by then the ball is
     * already going that fast.</p>
     *
     * <p>Where the ball leaves from is moving too, so the launch point's own velocity is added:
     * the chassis's, plus what its turn rate contributes at the mouth's lever arm. A shot taken
     * while driving forwards really does go further, and one taken mid-spin really does pull off
     * line, and both are things a driver has to learn rather than artefacts to design away.</p>
     */
    private void driveLaunchers() {
        if (chassis == null) {
            return;
        }
        Pose2d pose = chassis.pose();
        double cos = Math.cos(pose.heading());
        double sin = Math.sin(pose.heading());
        ChassisVelocity carried = chassis.velocity();

        for (Launcher launcher : launchers.values()) {
            // A wheel that is stopped, or running the wrong way, throws nothing. Skipped rather
            // than launched at zero: setting a resting ball's velocity to nothing every step would
            // pin it wherever it was, including in mid-air.
            if (launcher.surfaceMetresPerSecond <= 0.0) {
                continue;
            }
            VolumeConfig mouth = launcher.mouth;
            double exit = launcher.surfaceMetresPerSecond * launcher.transferEfficiency;

            // The aim, in the field frame. Yaw turns with the robot; pitch does not, because the
            // launcher is bolted to a chassis this world keeps flat on the tiles.
            double aim = pose.heading() + Math.toRadians(launcher.exitYawDegrees);
            double pitch = Math.toRadians(launcher.exitPitchDegrees);
            double flat = exit * Math.cos(pitch);
            double upward = exit * Math.sin(pitch);

            // The mouth's own velocity: the chassis's, plus omega cross r for a mouth held out
            // ahead of the centre it is spinning about.
            double alongNose = carried.forwardMetresPerSecond()
                    - carried.yawRadiansPerSecond() * mouth.leftMetres();
            double alongLeft = carried.lateralMetresPerSecond()
                    + carried.yawRadiansPerSecond() * mouth.forwardMetres();

            double velocityX = flat * Math.cos(aim) + alongNose * cos - alongLeft * sin;
            double velocityY = flat * Math.sin(aim) + alongNose * sin + alongLeft * cos;

            for (Ball ball : balls) {
                if (!ball.inside(pose, mouth)) {
                    continue;
                }
                // Copied out, not held onto. ode4j's getLinearVel() hands back a live view of the
                // body's own vector, so reading it after the launch reads the launch: the first
                // version of this took the difference against a "before" that had already become
                // the after, worked out an impulse of exactly zero, and threw balls across the
                // field off a robot that never felt a thing.
                DVector3C before = ball.velocity();
                double wasX = before.get0();
                double wasY = before.get1();
                ball.launch(velocityX, velocityY, upward);

                // Newton, as the roller has it: the momentum the ball leaves with came out of the
                // robot. As a force over this step, because that is what a solver takes, and only
                // the horizontal half of it -- the vertical goes into the tiles through a chassis
                // this world holds flat, which is what a launcher bolted to a robot standing on
                // the floor does with it. Worth having even though 22 g at 7 m/s barely moves
                // 14 kg: the force pair is what stays true when somebody bolts this launcher to a
                // 3 kg robot, and the roller went a year without it.
                double forceX = ball.mass * (velocityX - wasX) / STEP_SECONDS;
                double forceY = ball.mass * (velocityY - wasY) / STEP_SECONDS;
                chassis.addReaction(-forceX, -forceY,
                        mouth.forwardMetres(), mouth.leftMetres());
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
    private DBox buildFloor(FieldConfig field) {
        double span = field.sizeMetres() + 4.0 * WALL_THICKNESS_METRES;
        DBox slab = OdeHelper.createBox(space, span, span, FLOOR_DEPTH_METRES);
        slab.setPosition(0.0, 0.0, -FLOOR_DEPTH_METRES / 2.0);
        return slab;
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
     * Moves any ball the robot was just teleported on top of out from under it.
     *
     * <p>This used to run every tick, and it had to. A kinematic chassis pressed with unbounded
     * force and could not be slowed by what it pressed on, so a ball pinched against the perimeter
     * was an over-constrained problem: every way it could yield was wrong. Balls were squeezed
     * through the wall into the gym, or under the floor, or into the footprint, where the chassis
     * held one down against a floor pushing back and neither won &mdash; that last case oscillated
     * at about three metres a second while going nowhere, for the rest of the session, and was
     * found as hot fans.</p>
     *
     * <p>A dynamic chassis can be slowed by what it presses on, so the solver has a legal answer
     * to all of that and every one of those cases is now ordinary physics. What is left is the one
     * thing no solver can fix: a robot <em>authored</em> on top of a ball. A teleport is not a
     * motion, no contact preceded it, and the overlap is already there when the next step starts.
     * Out through the nearest side, because that is where a ball under a robot goes, and stopped
     * dead rather than flung, because it is being moved out of the way rather than hit. The
     * threshold is a quarter of a radius so a ball merely touching the bumper is left to the
     * contact solver.</p>
     */
    private void evictBallsFromFootprint() {
        Pose2d pose = chassis.pose();
        double cos = Math.cos(pose.heading());
        double sin = Math.sin(pose.heading());
        ChassisConfig chassis = robotConfig.chassis();

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
            if (!insideTheRobot) {
                continue;
            }

            // Whichever way out is shorter, and straight out along that axis.
            if (outForward < outLeft) {
                forward += Math.signum(forward == 0.0 ? 1.0 : forward) * outForward;
            } else {
                left += Math.signum(left == 0.0 ? 1.0 : left) * outLeft;
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
        if (chassis != null) {
            // Contact is a fact about this tick and nothing else would ever clear it: a robot that
            // touched a wall and has driven away since is not in contact.
            chassis.clearContact();
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
        // A HIVE counts. It keeps turning for the better part of a second after the shot that
        // tipped it, and the balls it has just thrown out may well have settled by then: a world
        // that called itself still would freeze the tip halfway round in every view of it, since
        // this is the flag the scene rebuild and the wire's body frames are both gated on.
        for (Hinge hinge : hinges) {
            if (hinge.movedFromBaseline()) {
                moved = true;
                break;
            }
        }
        if (moved) {
            for (Ball ball : balls) {
                ball.rebase();
            }
            for (Hinge hinge : hinges) {
                hinge.rebase();
            }
        }
        movedLastTick = moved;
    }

    private void step() {
        if (chassis != null) {
            chassis.step(STEP_SECONDS);
        }
        driveSweeps();
        // After the sweeps, so that a ball being dragged into a spinning flywheel leaves at the
        // wheel's speed rather than at the wheel's speed minus one step of the roller still
        // holding onto it. Both mechanisms reach the same space on a shoot-through robot, and the
        // launcher is the one that wins.
        driveLaunchers();
        driveHinges();
        space.collide(null, this::resolve);
        world.quickStep(STEP_SECONDS);
        contactGroup.empty();

        // After the step, because a stop is satisfied by the solver and not before it: asking
        // beforehand reads the angle the HIVE had when it was still a degree short of its damper.
        for (Hinge hinge : hinges) {
            hinge.note();
        }
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
        // Nor can two pieces of field furniture, even though a HIVE is a body the solver can
        // turn: what relates it to the A-frame is its pivot, and the frame's own logo panel
        // passes within a fraction of an inch of the HIVE's spine by design. Left in, those
        // contacts are a fight between the joint and the collider that the joint has to win 250
        // times a second, and the lowered CELL would be resting on the crossbar it is drawn
        // beside.
        if (isFieldStructure(first) && isFieldStructure(second)) {
            return;
        }
        if (isTheRobotOnTheFloor(first, second)) {
            return;
        }

        int found = OdeHelper.collide(first, second, MAX_CONTACTS, contacts.getGeomBuffer());
        if (found > 0) {
            noteRobotContact(first, second);
        }
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
     * Whether a geom is part of the field's own furniture: a FLOWER, the A-frame, or a HIVE.
     *
     * <p>Marked at construction rather than worked out from whether it has a body, because the
     * whole point is that one of these does: a HIVE is a body the solver turns, and it still must
     * not collide with the frame it hangs in.</p>
     */
    private static boolean isFieldStructure(DGeom geom) {
        return geom.getData() == FIELD_STRUCTURE;
    }

    /**
     * Whether this pair is the chassis meeting the floor it stands on.
     *
     * <p>Skipped, because the robot's contact with the floor is its <em>wheels</em>, and those are
     * four analytic forces rather than geometry. Letting the box scrape the slab as well would be
     * a second friction path, uncalibrated and fighting the first, and the robot would accelerate
     * worse than its grip allows for no reason a reader could find.</p>
     */
    private boolean isTheRobotOnTheFloor(DGeom first, DGeom second) {
        if (chassis == null) {
            return false;
        }
        DBody body = chassis.body();
        return (first == floor && second.getBody() == body)
                || (second == floor && first.getBody() == body);
    }

    /**
     * Records that the robot is touching something that cannot be moved.
     *
     * <p>Static geometry only. A ball against the bumper is not the robot being held up &mdash; it
     * gets shoved &mdash; and reporting it as contact would light the dashboard's indicator every
     * time a robot drove past a POLLEN.</p>
     */
    private void noteRobotContact(DGeom first, DGeom second) {
        if (chassis == null) {
            return;
        }
        DBody body = chassis.body();
        boolean firstIsRobot = first.getBody() == body;
        boolean secondIsRobot = second.getBody() == body;
        if ((firstIsRobot && isImmovable(second)) || (secondIsRobot && isImmovable(first))) {
            chassis.noteContact();
        }
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
    public List<PivotState> pivots() {
        List<PivotState> states = new ArrayList<>(hinges.size());
        for (Hinge hinge : hinges) {
            states.add(hinge.state());
        }
        return Collections.unmodifiableList(states);
    }

    /** The robot in this world, or null when the session's robot has no drivetrain. */
    @Override
    public Chassis chassis() {
        return chassis;
    }

    /** Whether anything has moved since the last tick that said so; see {@link #advance}. */
    @Override
    public boolean moving() {
        return movedLastTick;
    }

    @Override
    public List<GameElement> touching(VolumeConfig volume) {
        if (chassis == null) {
            // No drivetrain, so no robot, so nothing to bolt a volume to. An empty answer is the
            // truth: a sensor on a robot that cannot be anywhere is looking at nothing.
            return Collections.emptyList();
        }
        final Pose2d pose = chassis.pose();
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
        if (chassis == null) {
            return Double.NaN;
        }
        Pose2d pose = chassis.pose();
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
        if (chassis != null && hit.getBody() == chassis.body()) {
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

    @Override
    public void setLauncherSpeed(String motorName, double surfaceMetresPerSecond) {
        Launcher launcher = launchers.get(motorName);
        if (launcher == null) {
            throw new IllegalArgumentException("no launcher is driven by a motor named \""
                    + motorName + "\"; the ones this robot declares are " + launchers.keySet());
        }
        launcher.surfaceMetresPerSecond = surfaceMetresPerSecond;
    }

    /**
     * One launcher: where its wheel is, what leaving it does to a ball, and how fast it is running.
     *
     * <p>The geometry is unpacked from the configuration once rather than read through it on every
     * ball on every step. The speed is written from outside on each tick and read inside the
     * solver's loop, on the same thread in both cases.</p>
     */
    private static final class Launcher {

        private final VolumeConfig mouth;
        private final double transferEfficiency;
        private final double exitYawDegrees;
        private final double exitPitchDegrees;
        private double surfaceMetresPerSecond;

        Launcher(LauncherConfig configured) {
            this.mouth = configured.mouth();
            this.transferEfficiency = configured.transferEfficiency();
            this.exitYawDegrees = configured.exitYawDegrees();
            this.exitPitchDegrees = configured.exitPitchDegrees();
        }
    }

    /**
     * One pivoting structure: the joint that holds it, and how far round it has been.
     *
     * <p>{@link #completedSwings} is the count of HIVE TIPs. It is kept here rather than derived
     * by anyone watching the angle because it is a fact about the motion, and the motion happens
     * 250 times a second inside a solver step: a watcher sampling at a control cycle's 50&nbsp;Hz
     * would miss a fast swing outright, and one comparing two samples would score a HIVE that
     * bounced off its damper twice.</p>
     */
    private static final class Hinge {

        private final String structureName;
        private final Pivot pivot;
        private final DHingeJoint joint;

        /** Whether it is resting against the stop nearer {@link Pivot#lowRadians()}. */
        private boolean againstLow;

        private int completedSwings;

        /** The angle this pivot was at the last time the world said something had moved. */
        private double baseAngle;

        Hinge(String structureName, Pivot pivot, DHingeJoint joint) {
            this.structureName = structureName;
            this.pivot = pivot;
            this.joint = joint;
            this.againstLow = pivot.angleRadians() - pivot.lowRadians()
                    <= pivot.highRadians() - pivot.angleRadians();
            this.baseAngle = pivot.angleRadians();
        }

        /**
         * Its angle in the pivot's own absolute measure.
         *
         * <p>The joint reports its angle relative to the configuration it was created in, which is
         * the angle the structure's geometry was built at, so the two have to be added. A HIVE
         * staged tipped back and one staged upright therefore report angles a scene can use
         * directly rather than each being right relative to its own start.</p>
         *
         * <p>Clamped to the travel, because a constraint solver satisfies a stop approximately:
         * a HIVE pressed against its damper settles a ten-thousandth of a radian past it, which
         * is four thousandths of a degree of nothing and enough to make the structure it belongs
         * to refuse to be drawn.</p>
         */
        double angle() {
            double angle = pivot.angleRadians() + joint.getAngle();
            if (angle < pivot.lowRadians()) {
                return pivot.lowRadians();
            }
            return angle > pivot.highRadians() ? pivot.highRadians() : angle;
        }

        /**
         * Notes arrival at the far stop, which is one completed TIP.
         *
         * <p>Only ever the <em>far</em> one: a HIVE shoved by a shot that does not get it over
         * centre falls back where it came from, and that has to score nothing. Manual &sect;10.5.1
         * needs the HIVE to reach "the other stable state", and nothing short of it counts.</p>
         */
        void note() {
            double far = againstLow ? pivot.highRadians() : pivot.lowRadians();
            if (Math.abs(angle() - far) < ARRIVED_RADIANS) {
                completedSwings++;
                againstLow = !againstLow;
            }
        }

        PivotState state() {
            return new PivotState(structureName, angle(), completedSwings);
        }

        /** Whether it has turned far enough to be worth republishing; see {@link #MOVED_METRES}. */
        boolean movedFromBaseline() {
            return Math.abs(angle() - baseAngle) > TURNED_RADIANS;
        }

        void rebase() {
            baseAngle = angle();
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

        /**
         * Throws this ball, which is the one thing here that overrides the solver outright.
         *
         * <p>A velocity rather than a force, and deliberately not {@link #placeAt}: the ball stays
         * exactly where it is, keeps every contact it has, and is then part of ordinary physics
         * again on the very next step &mdash; so it arcs under gravity, bounces off a CELL's back
         * panel and rolls where it lands, with nothing marking it as "in flight". What is given up
         * is spin: a real shot leaves with heavy backspin, and this world has no aerodynamics for
         * that to act through.</p>
         */
        void launch(double metresPerSecondX, double metresPerSecondY, double metresPerSecondZ) {
            body.setLinearVel(metresPerSecondX, metresPerSecondY, metresPerSecondZ);
        }
    }
}
