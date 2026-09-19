# The world owns the robot's pose

The robot is a rigid body in the same ode4j world as the balls: a box with mass, moved
only by what its four mecanum contact patches can grip and by whatever it collides with.
`FieldPhysics.chassis()` is that body, and it is the single answer to "where is the
robot". `DriveModel` integrates nothing; it reads the wheels, hands their speeds to the
chassis before the world steps, and publishes the resulting heading to the IMU after.

## Why

**Because geometry a robot can drive through is a lie the driver can see.** The next
thing this simulator needs is the field's own structures — the HIVE at field centre, the
four FLOWERs on the perimeter. A kinematic chassis is carried to wherever wheel
kinematics say it is and can be stopped only by arithmetic, so adding HIVE colliders
would have produced a field where balls bounce off the structure and the robot drives
straight through it. Nothing about that is fixable in the structures; it is fixable only
in the chassis.

**Because the kinematic chassis had no legal answer to a pinch.** It pressed with
unbounded force and could not be slowed by what it pressed on, so a ball caught between
it and the perimeter was an over-constrained problem: every way it could yield was wrong.
Balls were squeezed through the wall into the gym, or under the floor, or into the
footprint, where the chassis held one down against a floor pushing back and neither won —
that case oscillated at about three metres a second while going nowhere, for the rest of
the session, and was found as hot fans. `repairImpossiblePlaces` existed to paper over
all of it and said in its own javadoc that it should be deleted when the chassis became
dynamic. It has been: what is left runs only after a teleport, which is the one case no
solver can fix, because a robot *authored* on top of a ball had no contact to resolve.

**Because one world must have one clock.** The wheels' forces depend on the robot's
current velocity, so they are recomputed every 4 ms substep rather than once per 20 ms
control cycle — a force computed once per cycle accelerates a standing robot as though it
had been standing for the whole cycle. That requires the chassis to be stepped by the
thing stepping the balls, off the same accumulator. Two accumulators over one world drift
apart, and a robot whose physics depends on which of them is read is not one anybody can
tune against.

**A slip model, not jointed wheels.** A mecanum wheel's rollers sit at 45 degrees, so its
patch pushes along one direction and rolls freely along the other. Hinged wheel bodies
with roller geometry would be dozens of joints solved every substep to reproduce a
relation already known exactly — and the SDK hands us shaft speeds, not torques, so the
wheels would be velocity-driven joints regardless. Instead each wheel contributes a force
proportional to how fast its patch is slipping, capped by `gripCoefficient` times the
weight it carries. Dotting each wheel's 45 degree direction with its patch velocity
reproduces the old forward kinematics term for term, which is how the signs were checked.

**Planar, by constraint.** A `plane2D` joint holds the body at `z = 0` and allows rotation
about `+Z` alone. An FTC robot on foam tiles does not meaningfully pitch, roll or leave
the ground, and simulating those would raise questions — when does it tip, where is the
centre of mass — that nothing here is calibrated to answer.

**The motors stay velocity sources.** A wheel that cannot move the robot keeps turning and
keeps counting encoder ticks. That is the failure this simulator exists to show: dead
reckoning drifting away from the truth while the numbers look healthy. A torque–speed
curve that let a stalled wheel slow down would hide it.

## Consequences

**A standing start is no longer instantaneous.** Grip bounds acceleration, so Verity takes
about three tenths of a second to reach its 1.57 m/s free speed. Three existing assertions
assumed otherwise and were rewritten: two in `MecanumDrivePoseTest`, which now bracket
travel between what a robot that got going must manage and what grip forbids, and one in
`SimControlSurfaceTest`, which compared a standing leg against a dilated one and now
measures the time multiplier from a running start. Assert against physical bounds — free
speed, grip — rather than pinning a distance the solver's tuning would change.

**The robot settles at exactly free speed.** A wheel's force is proportional to its slip,
so the slip converges to zero and stays there; the measured settled speed matches the
configured free speed to one floating-point bit. This is worth knowing because it means
the slip stiffness is a tuning constant with no steady-state consequence, which is why it
lives beside the other solver constants and not in `robot-config`.

**Mass and grip are now robot configuration.** `chassis.massKilograms` and
`drivetrain.gripCoefficient` join the physics source of truth in
`TeamCode/robot-config/*.json`. Rotational inertia is deliberately *not* configurable: it
is derived from the mass and the footprint as a uniform box, because a separately
configured inertia can contradict the dimensions beside it and nobody measures one.

**The robot can be moved by things that are not its wheels.** A ball barely registers
against 14 kg, but a wall stops the robot and a corner hit will rotate it — and the IMU
will report that rotation, because heading now comes from the body. This is deliberate: a
robot that spins off a wall corner is a real thing that ruins real autonomous runs, and
this simulator exists to show that class of failure rather than absorb it.

**Newton's third law now holds for the intake.** A running roller pushes back on the robot
it is bolted to, so a light chassis with its brakes off shifts slightly while dragging a
ball in — tens of microns, because most of the force pair cancels once the ball is seated
against the bumper. `IntakeTest`'s "the robot must not have driven" is a millimetre bound
rather than an exact zero for that reason.

**The chassis does not scrape the floor.** The robot's contact with the floor is its
wheels, and those are four analytic forces; the box-versus-slab contact is skipped so a
second, uncalibrated friction path cannot fight the first.

**A world without ode4j still drives.** `Chassis` is an interface, and
`Chassis.kinematic(...)` is the old arithmetic, kept behind `StillFieldPhysics` for a team
that drops the LGPL jar. It cannot be shoved by anything, which is what this simulator did
before a solver existed and is still enough to test vision against. The rule that the
world owns the pose holds on both: the difference between them is how the robot moves, not
who is allowed to say where it is.
