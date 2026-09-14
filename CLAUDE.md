# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

A team fork of the FIRST Tech Challenge SDK (`FtcRobotController` v11.2, DECODE 2025-2026 season). It builds an Android APK that runs on the REV Control Hub; the REV Driver Hub runs the Driver Station app. Team-authored robot code lives in `TeamCode`; everything under `FtcRobotController/` is vendor SDK code and samples, left alone so upstream SDK updates merge cleanly.

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
ones) run unmodified against a development machine's webcam through
`org.ngicollective.testframework.vision.LocalVisionHost`.

- **It only runs in an Android runtime** — emulator or device. OpenCV and AprilTag ship
  Android-only native libraries, so there is no plain-JVM vision path and never will be.
- **It replaces `VisionPortal`, not the processors.** `VisionPortal` finds a camera by USB
  enumeration; an emulator exposes the host webcam only through Camera2, with no USB device node,
  so `WebcamName` lookup cannot work there. `LocalVisionHost` opens the Camera2 device and calls
  `init` / `processFrame` / `onDrawFrame` itself. This is the one sanctioned OpMode change in the
  framework — see `ConceptLocalVision` in the `simulated` flavor.
- **Processors are handed RGBA (`CV_8UC4`) Mats.** The SDK's own processors run conversions
  (`COLOR_RGBA2GRAY`, `COLOR_RGBA2RGB`) that OpenCV rejects on 3-channel input.
- Overlays come from each processor's real `onDrawFrame`, so a preview shows what the Driver
  Station would. `VisionFrameListener` is the seam a dashboard `vision` namespace will consume.
- The AVD needs a `google_apis` image and `hw.camera.back=webcam0`; see `mise.toml`. Tests are
  instrumented (`mise run test-vision`) and deliberately outside CI.

## Conventions

Sample naming in `FtcRobotController/.../external/samples/` follows `Basic` / `Sensor` / `Robot` / `Concept` prefixes (plus some `Utility` classes); the scheme is documented in `sample_conventions.md` alongside them. Copy a sample into `TeamCode` rather than editing it in place.

## Agent skills

### Issue tracker

Issues live in this repo's GitHub Issues (ngi-collective/FtcRobotController2026-2027), via the `gh` CLI. See `docs/agents/issue-tracker.md`.

### Triage labels

Default label vocabulary: `needs-triage`, `needs-info`, `ready-for-agent`, `ready-for-human`, `wontfix`. See `docs/agents/triage-labels.md`.

### Domain docs

Single-context layout: `CONTEXT.md` + `docs/adr/` at the repo root (not yet created; created lazily by `/domain-modeling`). See `docs/agents/domain.md`.
