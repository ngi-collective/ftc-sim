# OpModes run unadulterated

The test framework must run a student's OpMode exactly as they wrote it. No test-only
API in OpMode code, no `#ifdef`-style branching on execution target, no "simulation
variant" of a class an OpMode names. If an OpMode compiles and runs on a Control Hub,
it must also run against the simulated robot without a character changing.

## Why

The people this framework serves are students who did not ask for it. They will not
write their code to be testable, will not plan for a seam, and will not keep a
simulation-friendly variant in step with the real one. Any requirement we place on
OpMode source is a requirement most of them will not meet, and a framework that only
works on code written for it is a framework that works on almost nothing. It would
also invert the value: the OpModes most worth exercising are the ones nobody thought
carefully about.

This is also what makes results trustworthy. A simulated run is only evidence about
competition behaviour if the code path is the same code path, and every concession in
OpMode source is a place where the simulated and real robot quietly diverge.

## Considered and rejected

**A small documented adaptation per subsystem.** This was the decision in ticket #19:
vision OpModes would call a `LocalVisionHost.builder()` where they now call
`new VisionPortal.Builder()`, because `VisionPortal` resolves cameras through
`WebcamName`, built on USB enumeration and native libuvc, which cannot work in an
emulator that exposes its webcam only through Camera2. One line per OpMode looked
cheap. It is not: it makes every vision OpMode in the repo bifurcate, and it fails
precisely on the unmodified sample-derived code students actually write.

## Consequences

The cost lands on us instead of on OpMode authors, and it is real: where the vendor
SDK reaches hardware we cannot provide, we have to impersonate the SDK's own seams
rather than ask the OpMode to look elsewhere. That means more work, and more exposure
to vendor internals changing under us on an SDK update.

Two carve-outs are worth stating so they are not mistaken for violations. Behaviour
that the SDK itself makes uninterceptable stays uninterceptable: `LinearOpMode.sleep()`
is `final` and `ElapsedTime` reads the system clock, so a sleep-heavy OpMode takes real
wall-clock time in the plain-JVM harness. And an OpMode still has to be *selected* and
started by something, which is the harness's job, not the OpMode's.
