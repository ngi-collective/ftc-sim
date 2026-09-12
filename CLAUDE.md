# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

A team fork of the FIRST Tech Challenge SDK (`FtcRobotController` v11.2, DECODE 2025-2026 season). It builds an Android APK that runs on the Robot Controller phone / Control Hub. Team-authored robot code lives in `TeamCode`; everything under `FtcRobotController/` is vendor SDK code and samples, left alone so upstream SDK updates merge cleanly.

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
mise run dashboard   # local Driver Hub server (see below)
mise run dashboard-ui
mise run lint
mise run clean
mise run setup-sdk         # (re)install the SDK packages the build needs
mise run verify-toolchain  # assert CLI and IDE toolchains can both still build (CI gate)

mise tasks           # list the above with descriptions
```

Gradle flags pass straight through, with or without a `--` separator: `mise run build --info`, `mise run check -- --dry-run`.

`TeamCode` is the only application module (`FtcRobotController`, `TestFramework` and `Dashboard` are libraries; `AndroidShims` is a plain `java-library`). It builds in two product flavors, so Gradle task names carry one: `robot` is the competition APK (`assembleRobotDebug`), `simulated` swaps in fake hardware for the emulator (`assembleSimulatedDebug`). Unit tests run on the simulated variant — `testSimulatedDebugUnitTest` — because that variant is the one with `VerityRobot` and the framework on its classpath. Raw Gradle needs the environment: `mise exec -- ./gradlew <task>`.

`mise run test` is a real gate. `TeamCode/src/test/` holds the pure-logic tests, `TeamCode/src/testSimulated/` the headless runs of whole OpModes (they need the flavor's `VerityRobot`), and `TestFramework/src/test/` plus `Dashboard/src/test/` cover the simulation infrastructure. On-robot verification via Driver Station telemetry is still the final word on anything touching real hardware behaviour, but a change that breaks an OpMode's wiring or drive maths should be caught here first.

## CI

`.github/workflows/ci.yml` runs on pushes to `master`, on PRs, and on demand. It installs the toolchain with `jdx/mise-action`, so CI and local builds run the same `mise.toml` pins and the same tasks: `verify-toolchain` → `test` → `lint` → `build`. Adding a task to `mise.toml` is what changes CI behavior; the workflow itself carries no build logic.

The Android SDK packages that `setup-sdk` installs are cached separately from the mise tool install, keyed on `mise.toml` + `build.common.gradle`. Every run uploads the lint report, and a successful run uploads `TeamCode-debug.apk` — a team member can install from a CI run without a local Android toolchain.

`.github/dependabot.yml` covers GitHub Actions only. The `org.firstinspires.ftc.*` artifacts are deliberately excluded: they are locked to the season's SDK release and must move together as an upstream merge, never one bot PR at a time.

Toolchain: Gradle 8.9, AGP 8.7.0, `compileSdk 34` / `minSdk 24` / `targetSdk 28`, build-tools 34.0.0, Java 8 source and target compatibility.

**Run Gradle under JDK 21.** `mise.toml` pins `java = "temurin-21"` for exactly this reason. The system JDK is 25, and Gradle 8.9 cannot read class file major version 69 — any build script it has to compile fresh dies with `Unsupported class file major version 69`. Cached scripts mask this, so a build can appear healthy right up until `clean`. AGP 8.7.0 independently requires JDK 17+, making 21 the LTS that satisfies both. If mise is not active in the shell, prefix commands with `mise exec --`.

**mise owns the Android SDK too.** `mise.toml` pins `android-sdk = "22.0"` and exports `ANDROID_HOME` at that install. `sdk.dir` is deliberately left unset in `local.properties`, because it takes precedence over `ANDROID_HOME` and would silently reintroduce a second SDK. Outside a mise environment the build now fails with `SDK location not found` — that error means mise is not active, not that anything is misconfigured.

The mise `android-sdk` plugin ships **cmdline-tools only**. The platform and build-tools are installed underneath it by `mise run setup-sdk`, which accepts the SDK licenses and installs the platform matching `compileSdkVersion` (currently `platforms;android-34`), `build-tools;34.0.0`, and `platform-tools`. Both `setup-sdk` and `verify-toolchain` scrape that version out of `build.common.gradle`, so they cannot disagree. Those live inside the versioned install directory, so bumping the pinned `android-sdk` version yields an empty SDK until `setup-sdk` is rerun — that is why the version is pinned rather than `latest`.

Android Studio may rewrite `local.properties` and re-add `sdk.dir` pointing at its own SDK. If the CLI and the IDE start disagreeing, that is the cause; delete the line again or point it at `mise where android-sdk`.

**The IDE is not on the mise toolchain, because wiring it up is fiddly — not because the split is wanted.** Android Studio takes its Gradle JVM from `.gradle/config.properties` (`java.home` → the bundled JBR) and its SDK from its own IDE setting, and it inherits no shell environment when launched from the Dock. Pointing both at mise means hand-editing IDE settings that reference mise's versioned install paths, which then break on any `mise.toml` version bump. Until that is worth doing, the two toolchains drift independently, and `mise run verify-toolchain` is the guard: it asserts each side can still build the project — JDK within 17-21, and an SDK containing `android-34` plus build-tools 34.0.0 — rather than asserting they match. The required platform is derived from `compileSdkVersion` in `build.common.gradle`, so it follows an SDK bump automatically. Every IDE-side input (`.gradle/`, `local.properties`, `.idea/`) is gitignored, so in CI those two checks report `skip` and only the mise side is enforced.

`ndkVersion '21.3.6528147'` is declared in `build.common.gradle`, but no NDK is installed and the build succeeds anyway — there are no native sources, so `mergeDebugNativeLibs` is `NO-SOURCE`. Don't chase an NDK install to fix an unrelated build failure.

## Module layout and build wiring

- `settings.gradle` includes five modules: `:FtcRobotController` (vendor), `:TeamCode` (team OpModes, the only application module), `:TestFramework` (fake hardware + OpMode harness), `:Dashboard` (local Driver Hub server), and `:AndroidShims` (working `android.*` classes for unit tests). Only `:TestFramework` ever reaches an APK, and only in the `simulated` flavor.
- `build.common.gradle` holds all shared Android config and is explicitly upstream-owned — do not edit it. Module customization goes in `TeamCode/build.gradle`.
- `build.dependencies.gradle` pins every `org.firstinspires.ftc:*` artifact to a single SDK version (currently `11.2.0`). Bumping the SDK means bumping all of them together.
- `build.common.gradle` scrapes `versionCode` / `versionName` out of `FtcRobotController/src/main/AndroidManifest.xml` with a regex at configure time, so that manifest is the single source of app version truth.
- Only `mavenCentral()` and `google()` are declared. Third-party FTC libraries (Pedro Pathing, Panels, FTCLib, Road Runner) each need their own maven repo added before their coordinates will resolve.
- Debug and release both sign with `libs/ftc.debug.keystore` unless `APK_SIGNING_STORE_FILE` and friends are set in the environment.

## OpMode model

OpModes are discovered by annotation (`@TeleOp` / `@Autonomous`, optionally `@Disabled`), not by registration. `FtcRobotController/.../FtcOpModeRegister.java` exists only as a retired manual-registration hook and should stay empty. A new OpMode is just a new annotated class under `org.firstinspires.ftc.teamcode`.

Two OpMode styles appear in the samples: `LinearOpMode` (imperative `runOpMode()` with `waitForStart()` and an `opModeIsActive()` loop) and `OpMode` (callbacks `init` / `init_loop` / `start` / `loop`).

Hardware is resolved by string name against the configuration file stored on the Robot Controller device (`hardwareMap.get(DcMotorEx.class, "FL")`). A name mismatch is a runtime crash on init, not a compile error, so hardware names must be kept consistent across every OpMode and constants class in the module.

## Headless OpMode tests

`:TestFramework` runs real, unmodified OpModes on a plain JVM — no robot, no emulator, no Robolectric. See `TeamCode/src/test/java/.../IamYouHeadlessTest.java` for a worked example.

```java
FakeHardwareMap hardware = FakeHardwareMap.builder()
        .addImu("imu", ImuBehaviors.followingYawRate())
        .addMotor("FL").addMotor("FR").addMotor("BL").addMotor("BR")
        .build();
LinearOpModeHarness harness = OpModeHarness.forLinear(new iamyou(), hardware);

harness.launch();                          // runOpMode() starts on its own thread
harness.pressStart();                      // the driver station PLAY button
harness.gamepad1().left_stick_y = -1.0f;   // driver input
hardware.advance(1.0);                     // one simulated second of motor travel
harness.pressStop();
harness.awaitCompletion(2000);
```

Three things to know before writing one:

- **Simulated time drives fake devices only.** `hardware.advance(seconds)` moves encoders and headings. `LinearOpMode.sleep()` is `final` and calls `Thread.sleep`, and `ElapsedTime` reads the real clock, so a sleep-heavy OpMode still takes that long in a test.
- **A linear OpMode runs concurrently with the test.** Poll for the exact state you expect (see `awaitWheelPowers` in the example) rather than assuming the OpMode has reached a particular line.
- **The simulated robot is `TeamCode/src/simulated/java/.../simulated/VerityRobot.java`.** Device names live there, once, shared by the headless tests, the dashboard and the simulated app.
- **A few Android classes are supplied, not stubbed.** `:AndroidShims` holds working `android.opengl.Matrix` (every IMU reading goes through it), `android.util.Log` (so `RobotLog.*` works — set `-Dftc.log.level=VERBOSE|INFO|NONE` to retune) and `android.os.SystemClock`. It is a `testImplementation` dependency of every module that runs unit tests, and a plain `java-library` on purpose so those classes can never be dexed into an APK.

## Local Driver Hub dashboard

A browser stand-in for the Driver Station, driving the same simulated robot as the headless tests. Two processes:

```bash
mise run dashboard      # JVM: discovers OpModes + VerityRobot, serves ws://localhost:8765
mise run dashboard-ui   # Vite: the browser UI on http://localhost:5183
```

Select an OpMode, `init`, `start`, then drive with the keyboard (WASD, arrows to turn, shift for slow mode). Telemetry streams into the log; the right rail shows live device state and lets you swap a device's behavior mid-run — click `stalled` on a motor to watch an OpMode keep commanding full power while that wheel stops.

- `:Dashboard` holds the wire protocol (`protocol/`), the `DashboardBackend` seam, the in-process `LocalDashboardBackend`, and the WebSocket server. `driver-hub-dashboard/` is the React UI, outside Gradle.
- Every message is `{namespace, type, payload}` over one socket, namespaces `opmode`/`telemetry`/`gamepad`/`device` — the shapes are listed on `DashboardServer` and mirrored in `driver-hub-dashboard/src/protocol.ts`. Keep the two in step.
- Behavior overrides name a behavior from a closed catalog; the wire never carries code.
- A tick thread advances simulated time in step with the wall clock and drives an iterative OpMode's `loop()`. Each `init` builds a fresh robot, so a fault never leaks into the next run.
- The backend seam is what the phase-2 emulator target reuses: same protocol, same UI, a second `DashboardBackend`.
- Java-WebSocket and gson come from inside the FTC SDK's own AARs — the dashboard adds no new Java dependencies.

## Simulated Robot Controller app

The `simulated` flavor is the whole Robot Controller app running against fake hardware — useful when the plain-JVM harness is not enough, because here an OpMode takes exactly the code path it takes on a Control Hub (real event loop, real OpMode manager, real telemetry, real web server).

```bash
mise run simulator   # installs and launches it on whatever adb is pointing at
```

- `SimulatedHardwareFactory` overrides the one public, non-final `HardwareFactory.createHardwareMap` and returns `VerityRobot.create()` instead of scanning USB.
- `SimulatedRobotControllerActivity` exists only to construct that factory: the stock activity builds its own inside a **private** `requestRobotSetup()`, so the subclass repeats those few lines with a different factory. Every collaborator it touches is `protected`. **If an SDK update changes `requestRobotSetup`, re-read this method against it** — that is the price of editing no vendor code.
- `SimulatedPermissionValidatorWrapper` reuses the SDK's permission screen and hands off to that activity; the flavor manifest drops the stock launcher entry so there is one icon.
- Verified boot on `system-images;android-30;default;arm64-v8a`: the app reaches `Robot Status: running` and logs `building simulated robot "Verity" - no USB scan`, with every OpMode registered.
- Driving an OpMode here still needs a Driver Station. Wiring the dashboard to this target — a second `DashboardBackend` inside the app — is the next piece of work.

## Conventions

Sample naming in `FtcRobotController/.../external/samples/` follows `Basic` / `Sensor` / `Robot` / `Concept` prefixes (plus some `Utility` classes); the scheme is documented in `sample_conventions.md` alongside them. Copy a sample into `TeamCode` rather than editing it in place.
