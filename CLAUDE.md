# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

A team fork of the FIRST Tech Challenge SDK (`FtcRobotController` v12.0, BIOBUZZ 2026-2027 season). It builds an Android APK that runs on the REV Control Hub; the REV Driver Hub runs the Driver Station app. Team-authored robot code lives in `TeamCode`; everything under `FtcRobotController/` is vendor SDK code and samples, left alone so upstream SDK updates merge cleanly. Building from Android Studio requires Narwhal 3 Feature Drop or later.

Two remotes, and the distinction matters:

- `origin` → `ngi-collective/FtcRobotController2026-2027` — the team fork. All team work goes here.
- `upstream` → `FIRST-Tech-Challenge/FtcRobotController` — the official SDK, pull-only.

`.github/CONTRIBUTING.md` is upstream's, and its main point applies: team code is never meant to go back to `upstream`. Never push or open a PR against it.

## Commands

**Every Gradle invocation must run under mise** — it supplies both the JDK and the Android SDK, and the build fails without it (see Toolchain). Prefer the tasks in `mise.toml`, which run inside that environment already:

```bash
mise run check       # compile TeamCode - fastest correctness check while editing OpModes
mise run test        # unit tests: TeamCode + TestFramework + Dashboard, vision included
mise run build       # competition debug APK
mise run install     # build + adb install to a connected Robot Controller device
mise run simulator   # install + launch the simulated app on a running emulator
mise run test-vision # the two tests that still need an emulator: event loop, physical camera
mise run dashboard   # local Driver Hub: simulation + browser UI (http://localhost:5183)
mise run dashboard --scenario tipping-hive   # the same, with a field arrangement loaded
mise run dashboard --dev                     # ...served by Vite with hot reload, for UI work
mise run dashboard-headless  # the simulation socket alone, for scripts (ws://localhost:8765)
mise run dashboard-headless --args="--port 8775 --camera-port 8776"  # ...on spare ports
mise run lint
mise run clean
mise run setup-sdk         # (re)install the SDK packages the build needs
mise run verify-toolchain  # assert CLI and IDE toolchains can both still build (CI gate)

mise tasks           # list the above with descriptions
```

The two dashboard tasks take flags differently, which is worth knowing before losing ten minutes
to it: `dashboard` is a shell script, so its flags follow the task name, while
`dashboard-headless` is a bare Gradle invocation, so its flags go inside `--args=`. Either way an
unrecognised flag is dropped in silence, and the symptom is a session sitting on the robot's own
field wondering where the scenario went.

Supervised processes: `hub ps` before starting anything, one name per service, and reuse a live one with `hub restart <name>`. Stop a process in the turn its work finishes. The one worth keeping between turns is `mise run dashboard`, which idles at 0% CPU once no browser is attached.

## Workflow

- Avoid making changes in `FtcRobotController` - that is managed upstream and will make conflicts more painful as the official SDK gets updated.
- Run `mise run test` for tests and `mise run lint` before considering any work done.

## OpMode model

OpModes are discovered by annotation (`@TeleOp` / `@Autonomous`, optionally `@Disabled`).

## Simulation

There is a side-project occurring in this same directory. It is a simulation and test framework for OpModes.

Use Chrome for Testing for browser work, through `browser.open`: those tabs freeze when the turn settles. A Chromium launched as a supervised process never freezes — the dashboard's 3D view and camera stream keep rendering through software GL, which is a runaway GPU process an hour later. Launch one only when a check needs raw CDP, and stop it in the same turn.

The headless sim drives a real pose, not just spinning motors, and **the physics world owns that
pose**. `FieldPhysics.chassis()` is the robot: a box with mass, accelerated by four mecanum
contact patches and stopped by whatever it runs into, so a ball slows it, a wall stops it and a
corner spins it. `DriveModel` no longer integrates anything — it reads each wheel's simulated
physical velocity, hands the four speeds to the chassis before the world steps, and publishes the
resulting heading to the IMU after. See `docs/adr/0003-the-world-owns-the-robots-pose.md`.
`mise run dashboard` streams the pose at 50 Hz and the 3D view drives the robot from it.

**The dashboard is one command, on one port.** `tools/dashboard.sh` builds the UI, serves it and
starts the JVM, deciding the port once; the page connects to its own origin at `/ws` and Vite
proxies that to the simulation, so the browser never names a port and the two halves cannot
disagree. Move both with `DASHBOARD_PORT=9000 mise run dashboard` — a bare `--port` is refused,
because a port only the JVM knows about is exactly the bug this replaced: the UI silently
connected to whatever else was on 8765, green indicator and all. Scripts that drive the socket
directly want `mise run dashboard-headless`, which has no UI to point anywhere.

**It serves a production build**, and `--dev` is the opt-out for anyone editing the UI. React's
development build emits a `performance.measure` per component per commit and this page commits at
20 Hz: profiled at 6× CPU throttle, the dev build spent 28% of the main thread in `clearMeasures`
and 20% in `jsxDEV`, ran at 27–30 fps and stalled for 100 ms at a time, where the production
build of the same commit held 60 fps with a 16.7 ms p95. That is the single biggest thing between
a driver and a smooth field view, and it is a build flag, not a scene.

**The simulated flavour starts its own robot**, because the SDK's robot start cannot finish on an
emulator: it waits for a Wi-Fi Direct network that is not there and gives up with
`Robot Status: stopped, internal error`. Without a robot start the event loop never runs, no
hardware map is ever built, `SimulatedClock` never ticks, and the OpMode registry stays empty, so
`initOpMode` on any name falls through to `$Stop$Robot$`. `NetworkType.LOOPBACK` exists in the SDK
but `NetworkConnectionFactory` answers it with `return null; // not yet implemented`, so there is
no supported network mode to switch to either.

`SimulatedRobotStart` does the rest of the SDK's own sequence and skips that one step, so the
event loop, `OpModeManagerImpl`, the OpMode registry and LiveView are all the genuine article. It
replaces `FtcRobotControllerService.setupRobot`, which is what waits for the network.

- **Measured, not assumed**: an iterative OpMode's `loop()` runs at roughly 600 Hz and a vision
  OpMode streams at its configured frame rate (90 frames in 3 s at 30 fps). An earlier reading of
  the SDK predicted 2 Hz, from a heartbeat throttle the control flow turns out never to reach.
- **What is given up** is all Driver Station facing: no Robocol connection, so no Driver Station,
  no telemetry off the device and no DS camera stream; the web server is not started, so no
  OnBotJava. The app's "Restart Robot" menu goes through the SDK's own path and will hang on the
  network wait — restart the app instead.
- **Instrumented tests cannot use the app's robot.** The test runner stops any activity it did not
  start, and the SDK shuts a robot down with its activity, so the tests start their own through
  the same `SimulatedRobotStart`. See `RobotUnderTest`.

- **`TeamCode/robot-config/*.json` is the physics source of truth** — wheel radius, gear ratio,
  track width, strafe efficiency, grip coefficient, chassis dimensions and mass, encoder
  resolution, per-motor mounting mirror, launcher wheel and aim. `VerityRobot` reads it; do not
  hardcode these numbers anywhere else. The files are also
  packaged as APK resources, so the simulated app works on a device with no checkout.
  Read again on every `create()`, so **editing a config file takes effect at the next INIT** — no
  dashboard restart. The camera mount is six-DOF (`forwardMetres`, `leftMetres`, `heightMetres`,
  `yawDegrees`, `pitchDegrees`, `rollDegrees`); a session keeps its alliance, its field
  arrangement and where the robot is standing across that rebuild, and a file that will not parse
  costs an INIT rather than the session.
- **`TeamCode/robot-layouts/*.json` is cosmetic** — mesh choice, scale, label offsets. It never
  affects how the robot moves.
- **Structures vs surfaces.** A **structure** is field furniture a robot can hit — the HIVE, the
  FLOWERs. Java owns its geometry and publishes it in `sim/scene` as posed boxes and cylinders;
  the browser draws what it is sent. One that moves carries a `pivot` there — a point, an axis and
  the angle its solids are drawn at — and its live angle arrives through `sim/bodies` beside the
  balls, keyed by structure **name**, since there are two of them and list order is not a promise.
  The browser turns the group by the *difference* between the live angle and the drawn one;
  rotating by the absolute angle draws a HIVE tipped twice over, which looks nearly right at small
  angles. A **field surface** — tiles, perimeter faces — is cosmetic and stays procedural in both
  renderers, derived from the dimensions already in `sim/config`. Never retype CAD numbers into
  TypeScript. See `docs/adr/0004-structures-are-published-as-posed-primitives.md`.
- ode4j has **no cylinder-versus-cylinder collider** (`CollideCylinderSphere`,
  `CollideCylinderBox` and `CollideCylinderPlane` exist; nothing for two cylinders). A hollow
  thing that has to hold a ball — a FLOWER tube — is a ring of boxes as a collider even when it is
  one cylinder as a drawing.
- A collider's **rotation** is set by rows, not by `DMatrix3.setCol`: ode4j stores ODE's row-major
  `dMatrix3`, so `setCol(i, v)` writes row `i` and assigning forward/left/up through it installs
  the transpose. Every FLOWER hid that completely — a yaw-only solid transposes to a mirrored yaw,
  which is the identity at yaw zero and is the same ring for twelve identical rim boxes — and the
  first thing that leaned, an A-frame leg, had its collider somewhere a ball fell straight past.
  Anything with pitch or roll needs a test that a ball or a robot actually hits it.
- What has to cross a static box in one 250 Hz step is **the whole ball, not its centre**, so a
  quarter-inch rim catches anything under about 19 m/s. Thin colliders are fine; a ball squeezing
  *through* one is a rotation bug, not a tunnelling one.
- The **HIVE Structure is three structures**: `HIVE FRAME`, which never moves, and one per
  alliance, each on a **pivot**. Only the frame is reachable — a HIVE's lowest point is 25.5 in
  up and a legal robot is 18 — so the frame's legs and the bars its feet sit on are the only part
  a robot can hit. The CELL face carrying an AprilTag Cluster is **collided with but not drawn**:
  a panel coplanar with the sticker would take turns painting over it in a renderer that sorts
  whole polygons by depth, and the tags would vanish from some viewpoints only.
- **A HIVE really tips, on a real hinge, and nothing decides that it has.** The body is hinged on
  the measured pivot axis with hard stops at the two stable states and one explicit bi-stable
  torque; what turns it is the weight of the balls in the raised CELL and the impact of the next
  one arriving, worked out by the solver. Balls spill out because the basket is now pointing at
  the floor, a swing takes about two thirds of a second, and the manual's own warning about
  LAUNCHING at a tipping HIVE is reproducible. `FieldPhysics.pivots()` reports each HIVE's angle
  and its count of completed TIPs. See
  `docs/adr/0005-the-hive-tips-on-a-hinge-nothing-decides-it.md`.
- **Everything on a HIVE moves as one body.** Its panels, its two CELLs' scoring volumes and its
  two AprilTag clusters declare which structure carries them, and `SimulatedScene.tipped(angles)`
  applies one rigid rotation to the lot. Re-deriving any of them from a tip angle separately is
  how the tag plates came to be mirrored: a plate starts 60° below the horizon and 60° of tip
  takes it off the end of the yaw-pitch-roll chart, so the far state needs 180° of **roll**, and
  without it the four ids reverse along the row with every pattern upside down. A detector reports
  zero detections, because a rotated 36h11 codeword is usually not a codeword.
- The one number nobody has measured is **`BioBuzzHive.PIVOT_HOLD_NEWTON_METRES`**, the torque
  holding a HIVE against its stop. It is bracketed by the manual: the three NECTAR a MATCH stages
  in each CELL weigh 0.34 N·m and must not tip it, and a TIP is worth ten elements so more than
  ten POLLEN would make tipping pointless. Measure it on a real field by putting POLLEN into a
  raised CELL one at a time and counting; `HiveTipTest` states what the current figure implies
  rather than enshrining it.
- **Scoring is a containment test against a posed box**, not a flag on a structure. A
  `ScoringVolume` is the manual's own term (§10.5.2) for a region where an element counts, it is
  invisible, and it rides on `SimulatedScene` beside the structures, swinging with the HIVE it is
  cut in. All four CELLs are published; **which of them score is read off the geometry**, because
  a HIVE halfway through a tip has no answer to "which CELL is up". `BioBuzzScore` takes the
  volumes, the balls and the TIP counts, and knows nothing about a HIVE beyond which names are
  red.
- The score is **live, and a HIVE TIP is 20 points of it**, in AUTO and TELEOP alike and again for
  every TIP. The manual scores a CELL at the end of the match; a driver practising needs to know
  what *would* score now, so a ball knocked out takes its two points with it — and a tip empties
  the basket that earned it, so the strip reports the TIP count beside the totals or a total that
  rose by 8 while a CELL went to zero would be unreadable. A downward-facing CELL is absent from
  the list rather than present with a zero, which is also how `sim/score` reaches the browser —
  and unlike `sim/bodies` it is in the connect greeting, because nothing else on that socket lets
  the browser derive a score.
- A CELL counts **any** POLLEN or NECTAR, of either colour: points follow the basket, not the ball
  (manual §10.5.1, and the same rule as GARDEN and FLOWER). Containment is of the ball's
  **centre**, not "at least partially", which is the CELL rule and not the FLOWER one.
- A scenario stages a ball either on the tiles (`xMetres`/`yMetres`) or inside a CELL
  (`"cell": "RED AUDIENCE"`), which is where the manual's three NECTAR per alliance start — four
  and a half feet up, on a basket whose position depends on the tip. `match-staging` is that setup
  and reads 6-6 before anyone has driven; `tipping-hive` is the same three NECTAR plus four POLLEN
  lying in launcher range of red's raised CELL, which is a HIVE two good shots short of going
  over.
- **A scenario is chosen in the browser, not at startup.** `sim/scenarios` carries the names in
  `TeamCode/scenarios/`, the directory they live in, and which one is loaded; `sim/scenario`
  loads one by name, and a **null** name is the real request that means "the robot's own field",
  which is the only way back off a scenario without restarting. The session remembers the
  arrangement the robot built for itself so that null has something to restore — loading a
  scenario overwrites the camera's scene, and before that was kept there was no way back. The
  `--scenario` flag still works and now goes through the same call, so one place knows what is in
  force.
- **A launcher is a flywheel, and the only motor that touches the world.** Servos sweep balls;
  `launchers` in `robot-config` is a block keyed by the *motor* that spins one, and
  `FieldPhysics.setLauncherSpeed` is fed the wheel's **physical surface speed** — shaft ticks
  through the wheel's radius, with `mirrored` applied here exactly as `DriveModel` applies it to a
  wheel. Reading the shaft and not the command is the whole point: a shot fired before spin-up
  falls short, and that is why the launcher's motor is the one motor built with
  `MotorBehaviors.ramping` (from `spinUpSeconds`) while every other one is ideal.
- The mouth is **where the wheel is**, not a trigger: anything inside it while the wheel spins
  leaves at `surfaceSpeed × transferEfficiency` (about 0.5 for one wheel against a hood, near 1
  for two counter-rotating), aimed by `exitYawDegrees`/`exitPitchDegrees` plus the launch point's
  own velocity — the chassis's, plus `omega × r` at the mouth. So a driver must spin up *before*
  collecting, one ball goes at a time because one ball arrives at a time, and nothing anywhere is
  marked "in flight". The mouth must clear the chassis footprint, since a ball inside a robot is
  evicted from under it.
- That discipline is not advice, it is the model: presenting a ball to a wheel that is still
  spinning up throws it at whatever speed the wheel has *now*, so it dribbles a foot and lands on
  the tiles out of reach. Driving onto a ball with the flywheel already at speed is the difference
  between a shot and a nudge, and it is the same order `LauncherTest.spunUpOver` uses.
- **Shots are steep lobs, and the field forces it.** A raised CELL's mouth is 1.51 m up and 0.30 m
  from the field's centre line, and a legal robot cannot get its nose further back than about
  1.4 m from it, so the straight line to the target is already near 60° — anything flatter cannot
  reach. The basket's back panel also returns a third of what hits it, so a flat hard shot bounces
  out of the opening it came in through. Verity is aimed at 75°, which scores from that stand-off
  at 35% of a bare 5203's free speed, and the whole scoring band is about ±5% of that.
- Poses are stored in the **FTC field frame**: origin at field centre, metres, heading radians
  CCW-positive, heading 0 facing +X. Only the browser converts to three.js coordinates.
- The drivetrain IMU uses `ImuBehaviors.followingChassis()`, so heading is derived from the wheels.
  The `stationary` / `rotating` / `followingYawRate` presets remain as deliberate fault injection.
- A standing start **is not instantaneous**: grip bounds acceleration, so Verity reaches its
  1.57 m/s free speed in about three tenths of a second (measured on a live session: 0.50 m/s at
  0.1 s, 1.12 at 0.2 s, 1.564 at 0.3 s). Assertions that assume a robot leaves the
  line at full speed are wrong, and were rewritten as brackets when the chassis went dynamic.
- Wall contact is a **real collision** with the perimeter, and deliberately leaves the encoders
  counting, so dead-reckoning drift is reproducible rather than hidden. `inWallContact()` is
  reported by the solver, not inferred from a velocity of zero.
- Assert drive behaviour on **pose**, not wheel powers — see
  `TeamCode/src/testSimulated/.../MecanumDrivePoseTest.java`. Pose is now solver output, so
  compare against physical bounds (free speed, grip) rather than pinning a distance.

## Vision

Real `VisionProcessor` implementations (`AprilTagProcessor`, `ColorBlobLocatorProcessor`, custom
ones) run against a **simulated camera** bolted to the simulated robot, which renders the BioBuzz
field. OpMode code is unmodified — see `docs/adr/0001-opmodes-run-unadulterated.md`.

- **The seam is `OpenCvCameraFactory.theInstance`**, which is static, package-private and not
  final, and is read afresh for every `VisionPortal`. `org.openftc.easyopencv.SyntheticCameras`
  assigns it — no reflection, and an SDK rename becomes a compile error. Installed in
  `SimulatedRobotControllerActivity.onServiceBind()`, so the competition APK is untouched.
- **The simulated camera reports itself as a Logitech C920.** `VisionPortal` decides a processor's
  calibration by looking the camera's identity up in the SDK's built-in table; with no identity,
  `AprilTagProcessor` solves with `fx=fy=cx=cy=0` and every pose is nonsense while detection still
  works. `WebcamCalibrations` reads the SDK's calibration back to configure the renderer, so
  frames are drawn through the very lens the solver inverts.
- **Geometry only**: correct tag quads through a pinhole model, plus flat coloured game elements.
  No lighting or photorealism — what is under test is the OpMode's reaction to detections.
- **The renderer is pure Java** (`camera` package): no OpenCV, no Android, so projection and
  pixels are unit-tested on a plain JVM in milliseconds. `Tag36h11` holds all 587 committed
  codewords; regenerate with `tools/generate-tag36h11.py`.
- **Tag +X points leftward as seen**, so `+Z` faces _into_ the mounting surface and the visible
  normal is `−Z`. Get it backwards and tags render mirrored, which reads as zero detections
  because mirrored 36h11 patterns are mostly not valid codewords. See `FieldTag`.
- **BioBuzz tags face the floor**, 35–50 in up and tilted 30°, so a camera **must aim up**; the
  mount in `robot-config` is 6-DOF for that reason. A recognised cluster is reported as one
  `AprilTagClusterDetection` with **no** per-tag singles.
- **Processors are handed RGBA (`CV_8UC4`) Mats.** The SDK's own processors run conversions
  (`COLOR_RGBA2GRAY`, `COLOR_RGBA2RGB`) that OpenCV rejects on 3-channel input.
- `WebcamFrameSource` keeps a **physical** camera behind the same `FrameSource` seam, as an
  oracle for "is the detector broken, or is my renderer?". It needs a `google_apis` AVD with
  `hw.camera.back=webcam0`; see `mise.toml`.
- **Vision runs on a plain JVM, and therefore in CI.** `SyntheticCameraAcceptanceTest` (detections
  are right, read off an unmodified OpMode's telemetry) and `AimedLauncherAcceptanceTest` (the
  whole season loop) are ordinary `mise run test` tests, about 13 s for the pair against 142 s of
  emulator invocations. `tools/build-vision-natives.sh` rebuilds AprilTag from OpenFTC's sources
  and stubs `libRobotCore`/`libEasyOpenCV`; OpenCV comes from `org.openpnp` through
  `VisionNatives.ensureLoaded()`; Robolectric supplies the Android framework, and `PlainJvmVision`
  the four things it does not — an Activity, the LiveView container, an `OpModeManagerImpl` and
  a pump for the paused main looper. **Call `PlainJvmVision.pump()` once per control cycle** or a
  camera that opens asynchronously never finishes opening. See
  `docs/adr/0007-vision-runs-on-the-plain-jvm.md`.
- **The Dashboard runs vision too, and that is why it starts through a JUnit runner.** A
  Robolectric sandbox comes from one, so `mise run dashboard` starts `DashboardLauncher`, which
  runs `DashboardHost` — a `@Test` method that serves until the process is killed and pumps the
  main looper every 5 ms. It is excluded from the test task by name; unexclude it and CI waits
  forever. `DashboardMain.start()` is the non-blocking half that makes this possible, and
  `DashboardMain.main()` still exists for a session with no vision in it.
- **Two things still need an emulator**, and no native library will change that:
  `RealEventLoopAcceptanceTest`, which proves the SDK's own robot start and event loop drive the
  camera, and `WebcamFrameSourceTest`, which needs a physical camera. `mise run test-vision` is
  those two. Each still wants its own Gradle invocation: a second LiveView portal in one process
  fails with "Viewport container specified by user is not empty!", which on the plain JVM is
  avoided instead by Robolectric giving each test class its own sandbox.

## Aiming, and shooting what you aimed at

`AimedLauncherTeleOp` closes the loop: detect the cluster, range off it, set the flywheel from the
range, shoot. `LensMount` → `ShotSolver` → `LaunchGeometry` in `TeamCode/src/main`, all three
plain-JVM testable. See `docs/adr/0006-a-cluster-detection-is-an-aim-point.md`.

**To watch the whole game work at once**, run `mise run dashboard --scenario auto-sweep`, pick
**Auto: sweep and shoot**, drag the robot onto the start square the INIT telemetry names
(`x -0.55, y -1.43, heading 90`), and press START. Nine seconds later the score reads
`RED 20 (1 TIP)`. `SweepAndShootAuto` is the routine to read first: five timed steps, no vision,
no odometry, and `SweepAndShootAutoTest` runs it headlessly on every commit — as, since
`docs/adr/0007-vision-runs-on-the-plain-jvm.md`, does the vision loop's equivalent.

- **An AUTO aims off the field drawing, not off a camera.** It starts on a known square facing a
  known direction, so the range is arithmetic before the robot has moved and with no tag in view.
  Same `ShotSolver`, different source of range — and the weakness is the obvious one: set the robot
  down half a metre out and every ball lands on the tiles, cheerfully.
- **It slides sideways rather than driving at the balls**, for the reason below: forward motion
  ruins a shot and sideways motion costs a quarter of an opening. Two rows, because the mouth
  reaches 24 cm and the bumper is at 20, so the band a ball can sit in without being shoved along
  by the chassis is one ball wide.
- **A routine that ends undoes its own work.** A dashboard session rebuilds the field when an
  OpMode stops, so the last step of an AUTO worth watching is `while (opModeIsActive())` holding
  still — which is also what a real one does while it waits for the buzzer.

- **A cluster detection is the aim point.** The SDK's cluster origin lands 1.42 in from the centre
  of the CELL opening its tags hang under — an eighth of the opening's height — identically for all
  four CELLs in both tip states, so there is no offset table. Measured, not chosen: the SDK's
  member offsets, FIRST's CAD and the manual's CELL dimensions agree there and none of them
  mentions the others.
- **Never use `ftcPose.range`, `bearing` or `elevation` on this robot.** They are measured in the
  camera's own frame — `range` is `hypot(x, y)` with the vertical dropped — and the camera aims up
  35°, so all three are wrong about the field by a plausible-looking amount. Use `x`, `y`, `z` and
  level them through the mount.
- **`robotPose` is useless this season.** The SDK declares all four BioBuzz clusters at
  `fieldPosition = (0,0,0)` with an identity orientation, so anything absolute must be built from
  relative measurements.
- **The two CELLs of a HIVE are one part mounted two ways**, the second turned 180° about the
  CELL's own rise axis. Their tag plates therefore differ by 180° of roll, and getting that wrong
  is nearly invisible: the tags still land on the measured plate and still detect, only the ids run
  the other way and the cluster origin lands two feet away, behind the closed end of the basket.
  `BioBuzzFieldTest.everyClusterOriginSitsAtItsCellsOpening` is the guard.
- **The detector reads about 3 % near** — a cluster 0.92 m away reports 0.89 m. Uniform, harmless
  for a steep lob, and the reason the acceptance test works in centimetres.
- **Which CELL to shoot at is a question about height.** Both plates of a HIVE share a normal, so a
  camera sees all four of its tags at once; the raised opening is at 1.51 m and the lowered one at
  0.97 m, and `LaunchGeometry.RAISED_CELL_HEIGHT_METRES` sits in the gap.
- **A 75° lob has a minimum range** (`rise / tan p`, about 40 cm) and needs its *least* speed at
  twice that. Closer shots are harder than mid-range ones, which is the opposite of the intuition a
  flat shooter gives a driver.
- **This robot cannot shoot on the move.** The intake's sweep and the launcher's mouth are the same
  6 cm of space, so a ball comes within reach and goes; at a third of power the chassis adds
  0.55 m/s to a 1.45 m/s horizontal component and the ball clears the far lip, which is 9 cm past
  the near one. Spin up, then roll the last couple of centimetres. Firing while driving needs a
  feeder, which this robot has not got.

## Conventions

Sample naming in `FtcRobotController/.../external/samples/` follows `Basic` / `Sensor` / `Robot` / `Concept` prefixes (plus some `Utility` classes); the scheme is documented in `sample_conventions.md` alongside them. Copy a sample into `TeamCode` rather than editing it in place.

## Agent skills

### Issue tracker

Issues live in this repo's GitHub Issues (ngi-collective/FtcRobotController2026-2027), via the `gh` CLI. See `docs/agents/issue-tracker.md`.

### Triage labels

Default label vocabulary: `needs-triage`, `needs-info`, `ready-for-agent`, `ready-for-human`, `wontfix`. See `docs/agents/triage-labels.md`.

### Domain docs

Single-context layout: `CONTEXT.md` + `docs/adr/` at the repo root (not yet created; created lazily by `/domain-modeling`). See `docs/agents/domain.md`.

### Dashboard probes

Dashboard slow, choppy, or suspected of leaking: measure with `driver-hub-dashboard/src/probe.ts`
(open the UI with `?probe`) and `tools/rss-curve.mjs` before theorising. A JS heap counter and a
DevTools profile both answer this question wrongly. See `docs/agents/dashboard-probes.md`, which
also carries the 3D view's measured budget — draw calls, GPU milliseconds per frame, and what
each of resolution, shadows and materials actually costs — plus the levers that were tried and
left alone.
