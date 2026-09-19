# 0005 &mdash; The HIVE tips on a hinge, and nothing decides that it has

## Status

Accepted.

## Context

A HIVE TIP is worth 20 points, is the only thing on a BioBuzz field that moves without being a
ball or a robot, and is the whole point of having a launcher. Manual §9.6:

> Each HIVE is on a pivot and can tip so that one of the CELLS is facing upwards at any given
> time. Each HIVE is bi-stable and will hold its position until enough POLLEN or NECTAR are
> LAUNCHED into the upwards-facing CELL.

and §10.5.1 dates a completed TIP from the moment "the damper on the HIVE that was previously not
contacting the frame begins to contact the frame", with G417 making LAUNCHING into the
upward-facing CELL the only legal way to cause one.

Until now a tip was a two-valued setting per HIVE, chosen by a scenario file and fixed for the
session. Everything downstream was derived from it independently: the CELL panels, the scoring
volumes, and the AprilTag clusters each computed their own pose from the same two-valued state.

The obvious way to animate that is a trigger: watch for balls arriving in a CELL, count them, and
play a rotation when the count is high enough. It is a few dozen lines and it would look right.

## Decision

The HIVE is a rigid body on a real hinge joint, and nothing anywhere decides that a tip has
happened.

- Each HIVE's collision geometry hangs off one dynamic body, hinged to the static world on the
  measured pivot axis, with hard stops at the two stable states.
- The bi-stability is one explicit torque with the shape of a pendulum's:
  `hold * sin(angle - middle) / sin(half the travel)`. Strongest at a stop, zero at the midpoint,
  reversed past it.
- What turns a HIVE is contact: the weight of the balls resting in the raised CELL, and the impact
  of the next one arriving. The solver works that out; there is no code that adds up a load.
- A TIP is counted where the motion happens, by the hinge, when it arrives at the far stop. A stop
  *is* the manual's damper, so arriving at the far one is both clauses of §10.5.1 at once.
- Everything bolted to a HIVE &mdash; its panels, its two CELLs' scoring volumes, its two AprilTag
  clusters &mdash; is moved by one rigid rotation applied by the scene, from one angle reported by
  the world. Attachment is declared once, by name, and a tip is a `Structure.at(angle)`.

## Consequences

Things that come out of this without being written:

- **Balls spill when a HIVE goes over**, because the basket they were in is now pointing at the
  floor. A tip therefore trades the 2 points an element scores for the 20 the tip does, which is
  the game's own arithmetic.
- **A HIVE mid-tip has an angle and no state.** The score reads which way each CELL's mouth is
  facing rather than being told which CELL is up, because during a swing there is no answer to the
  second question.
- **The manual's own warning is reproducible**: LAUNCHING at a CELL while the HIVE is tipping
  really can disturb it, because the tip takes about two thirds of a second and the ball arrives
  in the middle of it.
- **The tip threshold is a threshold on arriving, not only on weight.** Three NECTAR placed in a
  CELL stay there and the same three dropped in from 10 cm tip it, because once the HIVE leaves
  its stop the hold falls away towards the middle while the balls roll outwards as the CELL
  flattens. That is why LAUNCHING is what tips a HIVE.
- **The A-frame and the HIVEs cannot collide with each other.** The frame's logo panel passes
  within a fraction of an inch of a HIVE's spine by design, and the lowered CELL is drawn beside
  the crossbar; the pivot is what relates them, so contacts between two pieces of field furniture
  are skipped.

## What this costs

One number nobody has measured: `BioBuzzHive.PIVOT_HOLD_NEWTON_METRES`. A real HIVE carries its
weight on its pivot &mdash; it has to, since the 5.53 in its CELLs measurably hang off the axis
would otherwise make a gravity well several joules deep that no number of 22 g balls could lift it
out of &mdash; and is held by dampers whose stiffness is published nowhere. So what is modelled is
the *effective* pivot: gravity carried by the axis, one hold torque, one inertia, one damper.

The hold is bracketed by two things the manual does print. A MATCH stages three NECTAR in each
upward-facing CELL, which weigh 0.34 N&nbsp;m on the axis and must not tip it; and a TIP is worth
ten elements, so a HIVE needing more than ten POLLEN would make tipping strictly worse than
filling. 0.6 sits between them: the staged NECTAR hold with three fifths to spare, five more
POLLEN dropped in tips it, and a tip takes 0.4&ndash;1.1 s depending on how hard it was hit.

Anyone with a real field can replace that number in a minute by putting POLLEN into a raised CELL
one at a time and counting. `HiveTipTest` is written to state what the current figure implies
rather than to enshrine it.

## Alternatives rejected

**A triggered animation.** Cheaper, and it cannot produce any of the consequences above: the
spill, the disruption, the mid-swing state and the arriving-versus-resting threshold would each
have to be written, and each would be a rule somebody chose rather than something the field does.

**Gravity bi-stability from the measured geometry.** Physically the honest model, and it makes the
HIVE impossible to tip: with the CELLs' real overhang the well is metres of ball-drop deep. The
real mechanism must be counterweighted, so modelling the overhang literally would be a simulator
that is wrong in a way the manual contradicts.

**Deriving each tipped pose from the tip angle where it is used.** What the code did before, and
what the plates' 180&deg; roll error came out of: a CELL's tag plate starts 60&deg; below the
horizon and 60&deg; of tip takes it off the end of the yaw-pitch-roll chart, so the state on the
other side needs a roll nobody thinks to add. The result renders mirrored 36h11 patterns, which
are mostly not codewords, so vision reports zero detections and looks like a broken detector.
One rotation applied to whole poses cannot make that mistake.
