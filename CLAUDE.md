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
mise run test        # unit tests: TeamCode + TestFramework + Dashboard
mise run build       # competition debug APK
mise run install     # build + adb install to a connected Robot Controller device
mise run simulator   # install + launch the simulated app on a running emulator
mise run test-vision # vision tests against a running emulator's camera (not in CI)
mise run dashboard   # local Driver Hub server (ws://localhost:8765)
mise run dashboard-ui  # Driver Hub browser UI (http://localhost:5183)
mise run lint
mise run clean
mise run setup-sdk         # (re)install the SDK packages the build needs
mise run verify-toolchain  # assert CLI and IDE toolchains can both still build (CI gate)

mise tasks           # list the above with descriptions
```

## Workflow

- Make changes to TeamCode only.
- Run `mise run test` for tests and `mise run lint` before considering any work done.
-

## OpMode model

OpModes are discovered by annotation (`@TeleOp` / `@Autonomous`, optionally `@Disabled`).

## Simulation

The headless sim drives a real pose, not just spinning motors. `TestFramework`'s
`org.ngicollective.testframework.sim` package reads each wheel's simulated physical velocity,
applies mecanum forward kinematics, and integrates an `(x, y, heading)` pose on a 3.58 m FTC field
with perimeter walls. `mise run dashboard` streams it at 50 Hz and the 3D view drives the robot
from it.

**Robot start never completes on an emulator.** It waits for a Wi-Fi Direct network, which no
emulator provides, and gives up:

```
Robot Status: stopped, waiting for Wi-Fi Direct to enable
Robot Status: stopped, internal error
```

Robot start is owned by the SDK's `FtcRobotControllerService`, the Android service the Robot
Controller activity binds to; it also owns the network connection and the web server.
Consequences, all verified rather than assumed:

- The event loop never runs, so `SimulatedHardwareFactory.createHardwareMap` is never called,
  `SimulatedClock` never starts, and the OpMode registry stays empty — `initOpMode` on any name
  falls through to `$Stop$Robot$`. On a real Control Hub with a Driver Station, all of this works.
- So on an emulator no OpMode can be selected from the app's own UI, and the SDK's
  `OpModeManagerImpl` cannot be driven there. `NetworkType.LOOPBACK` exists in the SDK but
  `NetworkConnectionFactory` answers it with `return null; // not yet implemented`, so there is no
  supported network mode to switch to.
- Running OpModes in the app on an emulator therefore needs a **harness**, which drives an
  OpMode's lifecycle in place of the event loop and so needs no robot start at all. That is the
  route an in-process dashboard server should take.

- **`TeamCode/robot-config/*.json` is the physics source of truth** — wheel radius, gear ratio,
  track width, strafe efficiency, encoder resolution, chassis dimensions, per-motor mounting
  mirror. `VerityRobot` reads it; do not hardcode these numbers anywhere else. The files are also
  packaged as APK resources, so the simulated app works on a device with no checkout.
- **`TeamCode/robot-layouts/*.json` is cosmetic** — mesh choice, scale, label offsets. It never
  affects how the robot moves.
- Poses are stored in the **FTC field frame**: origin at field centre, metres, heading radians
  CCW-positive, heading 0 facing +X. Only the browser converts to three.js coordinates.
- The drivetrain IMU uses `ImuBehaviors.followingChassis()`, so heading is derived from the wheels.
  The `stationary` / `rotating` / `followingYawRate` presets remain as deliberate fault injection.
- Wall contact **slides** and deliberately leaves the encoders counting, so dead-reckoning drift
  is reproducible rather than hidden.
- Assert drive behaviour on **pose**, not wheel powers — see
  `TeamCode/src/testSimulated/.../MecanumDrivePoseTest.java`.

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
- **Tag +X points leftward as seen**, so `+Z` faces *into* the mounting surface and the visible
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
- The acceptance test is instrumented and deliberately outside CI:
  `./gradlew :TeamCode:connectedSimulatedDebugAndroidTest`.

## Conventions

Sample naming in `FtcRobotController/.../external/samples/` follows `Basic` / `Sensor` / `Robot` / `Concept` prefixes (plus some `Utility` classes); the scheme is documented in `sample_conventions.md` alongside them. Copy a sample into `TeamCode` rather than editing it in place.

## Agent skills

### Issue tracker

Issues live in this repo's GitHub Issues (ngi-collective/FtcRobotController2026-2027), via the `gh` CLI. See `docs/agents/issue-tracker.md`.

### Triage labels

Default label vocabulary: `needs-triage`, `needs-info`, `ready-for-agent`, `ready-for-human`, `wontfix`. See `docs/agents/triage-labels.md`.

### Domain docs

Single-context layout: `CONTEXT.md` + `docs/adr/` at the repo root (not yet created; created lazily by `/domain-modeling`). See `docs/agents/domain.md`.
