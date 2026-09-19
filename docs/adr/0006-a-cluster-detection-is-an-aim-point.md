# 6. A cluster detection is an aim point

Date: 2026-09-17

## Status

Accepted.

## Context

The season's loop is: see a CELL, work out how hard to throw, throw that hard. Everything up to
this point built the halves — real detections against a rendered field
([ADR-0001](0001-opmodes-run-unadulterated.md)), a flywheel whose exit speed comes from a
simulated shaft, a HIVE that tips because something hit it
([ADR-0005](0005-the-hive-tips-on-a-hinge-nothing-decides-it.md)) — and left the range-to-speed
arithmetic to a driver's thumb on a D-pad.

Closing it needs an answer to a question the SDK does not answer: **what point on the field does a
detection refer to, and where is that relative to the muzzle?**

Three things make it harder than it sounds.

**The SDK's convenient fields are measured in the camera's own frame.** `ftcPose.range` is
`hypot(x, y)` — the distance in the plane of the image, with the vertical component dropped —
and `bearing` and `elevation` are angles about the camera's own axes. This robot's camera aims up
by 35°, because BioBuzz puts its tags on the undersides of things four feet in the air. So every
one of those fields is wrong about the field by an amount that looks entirely plausible.

**`robotPose` is not available this season.** The SDK's own BioBuzz library declares all four
clusters at `fieldPosition = (0, 0, 0)` with an identity orientation
(`AprilTagGameDatabase.getBioBuzzTagLibrary`), so the field-relative pose the SDK can compute from
a detection is meaningless. Anything absolute has to be built from relative measurements.

**A cluster's origin is not on the plate.** The SDK's member offsets carry a `+7.1874 in` rise and
a `-5.622 in` depth, so the pose a cluster detection reports is some distance from the tags it was
solved from. Where, exactly, was unknown.

## Decision

**Aim at the cluster origin, levelled through the camera mount, and solve one exit speed from the
range and the rise.** Concretely:

- `LensMount` consumes only `ftcPose.x`, `y` and `z` — the raw translation, which carries no
  convention beyond its axes — and turns them into a position in the robot's frame with a height
  above the tiles. It knows the mount's six numbers and nothing about launching.
- `ShotSolver` turns that into a turn and a wheel speed. It knows the muzzle's position, the launch
  angle and one calibration constant, and nothing about cameras.
- `LaunchGeometry` holds the measurements both need, in one place.
- `AimedLauncherTeleOp` puts them together, picks the CELL worth shooting at, and leaves the driver
  with an assist button and a feed trigger.

The load-bearing part is the first clause. **The cluster origin lands 1.42 in from the centre of
the CELL opening** the tags are stuck under — 1.40 in down the opening's own rise and 0.21 in
outside its plane, identically for all four CELLs in both tip states. An eighth of the opening's
height. So a detection *is* the aim point, and there is no offset table.

That is not a choice; it is a measurement, and it took finding a bug to make it true. The two
CELLs of a HIVE are one part mounted two ways — the second is the first turned 180° about the
CELL's own rise axis, which the CAD confirms to a hundredth of an inch — and the tag plates were
being built with the same roll for both. With the wrong roll the origin lands two feet away,
behind the closed end of the basket, on exactly two of the four CELLs.

## Consequences

**Three sources that never mention each other now agree, and one test says so.** The SDK's member
offsets, FIRST's CAD for the plate, and the manual's 20 × 14 × 12 in CELL put the origin in the
same place to an inch and a half. `BioBuzzFieldTest.everyClusterOriginSitsAtItsCellsOpening` is
the assertion that fails if any of the three is re-measured into disagreement, and it fails by
two feet rather than by a hair, which is the useful kind of failure.

**The roll bug was close to invisible and is now impossible.** Tags still landed on the measured
plate and still detected, because the row offsets are symmetric; only the ids ran the other way.
The symptom was a range quietly wrong by the depth of a CELL on half the field. `aPlatesAxesAreItsCellsAxes`
states the law — a cluster's normal is its CELL's up, negated, and its up is its CELL's forward,
negated — so the two CELLs cannot drift.

**Nothing reads the configuration file.** `LaunchGeometry` is a second copy of six numbers from
`verity.json`, and it has to be: the competition APK ships no configuration and a Control Hub has
no checkout. `LaunchGeometryTest` compares the two through the pose the renderer itself looks
along, so a re-aimed camera fails a test instead of missing every shot.

**The detector reads about 3 % near.** Measured on the emulator: a cluster whose origin is 0.92 m
away reports 0.89 m, and one 1.48 m up reports 1.44 m. Uniform, and small enough not to matter
here — a 75° lob needs almost the same speed either side of its minimum — but it is the reason
the acceptance test's tolerances are 5 cm rather than 5 mm, and it would matter to anything
navigating by tags.

**This robot cannot shoot on the move.** Its mouth *is* its wheel: the intake's sweep and the
launcher's mouth occupy the same 6 cm of space, so a ball comes within reach and goes. At a third
of power the chassis adds 0.55 m/s to a 1.45 m/s horizontal component and the ball sails over the
far lip, and the CELL's lips are only 9 cm apart in the direction of flight. A driver therefore
spins up, then rolls the last couple of centimetres — which is what
`AimedLauncherAcceptanceTest` does, and what `LauncherTest.spunUpOver` already did for the same
reason. **Anything that wants to fire while driving needs a feeder holding balls back from the
wheel**, which is now a known gap in the robot rather than a mystery about the simulator.

**The tags swap, and losing the target is the correct behaviour.** Both plates of a HIVE share a
normal, so a robot on one side of the field sees all four of that HIVE's tags and has to pick: the
raised CELL is the higher one, and `LaunchGeometry.RAISED_CELL_HEIGHT_METRES` sits in the 54 cm gap
between the two openings. When the HIVE tips, both plates swing to face the *other* side, so the
robot that just scored has nothing to aim at and the telemetry says so. That is the game, not a
recovery problem.

## Alternatives considered

**Carry a per-CELL offset from the cluster origin to the mouth.** What the wrong-roll geometry
forced: the offsets differed between the audience-side and scoring-side CELLs, which is the
signature of the bug rather than a fact about the field. It also needs the cluster's orientation
at every use, which means either reconstructing a rotation from `ftcPose`'s yaw/pitch/roll — whose
chart the SDK does not document — or reading `rawPose.R` and guessing at the raw frame's
conventions. Both were written and both were thrown away when the geometry turned out not to need
them.

**Use the robot's field pose from the IMU and a known CELL position.** Works, and needs a
field-oriented IMU plus a correct field map: two more things to be wrong, in exchange for aiming
at where the CELL is *supposed* to be rather than where the camera can see it is. Kept in reserve
for a shot at a CELL that is out of view.

**Calibrate range to rpm empirically, as a lookup table.** What a team does on the day, and it
subsumes every constant here into one measured curve. Rejected as the primary path because a table
cannot say *why* a shot is impossible: the minimum distance, the non-monotonic speed, and "too
close, back up" all fall out of the arithmetic and none of them falls out of a table. A table
remains the right way to trim the one constant the arithmetic cannot derive, the transfer
efficiency.
