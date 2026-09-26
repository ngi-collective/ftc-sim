# 7. Vision runs on the plain JVM

Date: 2026-09-26

## Status

Accepted.

## Context

There are two execution targets: the plain JVM, where a harness runs an OpMode in milliseconds,
and the Android emulator, where the SDK's own robot start and event loop do
([ADR-0001](0001-opmodes-run-unadulterated.md)). Everything in the test framework is plain Java
and runs on both — except vision, which ran only on the emulator.

The reason was four native libraries. The FTC SDK ships OpenCV, AprilTag, EasyOpenCV and RobotCore
as Android ELF objects for `arm64-v8a` and `armeabi-v7a`, and nothing else. A desktop JVM cannot
load any of them, so a vision OpMode started in the Driver Hub Dashboard died with

```
UnsatisfiedLinkError: 'long org.opencv.core.Mat.n_Mat()'
```

thrown from wherever the first `Mat` was allocated, and the two acceptance tests that exercise the
SDK's vision stack end to end were instrumented tests, deliberately outside CI.

That left the most valuable tests in the project — the only ones that would catch the simulator
lying about what a camera sees — running by hand, on one developer's machine, at 71 s each plus
20 s to boot an emulator. It also meant the Dashboard listed vision OpModes it could not run.

Moving the rest of the work to the emulator instead was considered and measured. It fails on
arithmetic: the whole plain-JVM suite is 420 tests in 20 s, one instrumented test is 71 s, and
**no GitHub-hosted runner can accelerate this APK at all** — the x86-64 runners have KVM but
cannot host an arm64 guest, and the arm64 runners have no `/dev/kvm`.

## Decision

**Give the plain JVM the four natives and the Android framework the SDK reaches for, and run the
vision acceptance tests there.**

Natives, in `tools/build-vision-natives.sh`:

- **OpenCV** comes from `org.openpnp:opencv`, which carries desktop builds and extracts the right
  one itself. `VisionNatives.ensureLoaded()` is the seam: `OpenCVLoader.initDebug()` on a Robot
  Controller, openpnp off-device, reached by reflection so a test-scope dependency cannot be
  named from code that ships in an APK.
- **AprilTag** is rebuilt from OpenFTC's own JNI sources at a pinned commit. It is portable C with
  one Android dependency, `android/log.h`, shimmed in `tools/vision-natives/shim`.
- **RobotCore and EasyOpenCV are stubs**: empty libraries with no symbols. RobotCore's
  ninety-seven native methods are libuvc, serial port and BMP work; EasyOpenCV's three copy a
  `Mat` into an Android `Surface`. Nothing on the simulated camera's path calls any of them, and
  a desktop JVM has no USB camera to drive and no Surface to draw on. Both are nonetheless loaded
  from static initializers that this path reaches, so both must exist.

The Android framework comes from **Robolectric**, and this is the load-bearing part rather than
the natives. Hand-mocking it got as far as `Application`, `PackageManager`, `Resources`,
`WifiManager` — the SDK's `CameraStreamServer` takes a Wi-Fi lock — and `ActivityManager`, and
then stopped at `CameraCalibrationManager`, which reads the SDK's built-in camera calibrations out
of a compiled XML resource. Stub that empty and detection still works while every pose is silently
wrong, which is the failure this simulation exists to avoid. Robolectric supplies the resource
table, and is the only off-the-shelf implementation that does.

`PlainJvmVision` supplies the four things Robolectric does not, being the plain-JVM counterpart of
`RobotUnderTest`: an Activity, the `LinearLayout` EasyOpenCV's LiveView casts its container to,
an `OpModeManagerImpl` for the stopped-listener both EasyOpenCV and `VisionPortalImpl` register,
and a pump for the paused main looper.

## Consequences

`SyntheticCameraAcceptanceTest` and `AimedLauncherAcceptanceTest` are plain-JVM tests, part of
`mise run test` and of CI. Together they take about 13 s, against 142 s of emulator invocations,
and they agree with what the emulator measured: range within 2.5 cm of the field's own geometry,
the same 3 % near bias, the same HIVE TIP after five nudges.

What still needs an emulator is what always genuinely did: `RealEventLoopAcceptanceTest`, which
proves the SDK's own robot start and event loop drive the camera, and
`WebcamFrameSourceTest`, which needs a physical camera. `mise run test-vision` is now those two.

Three costs, stated rather than hidden:

- **OpenCV is 4.9.0 off-device and 4.10.0 on a robot.** openpnp publishes no 4.10, and the Java
  classes must be the pair of the natives being loaded. Nothing in the vision path uses an API
  that moved between the two, but this is a skew nobody will remember in March.
- **Two stub libraries exist**, and they are a lie that is true only for a simulated robot. A
  desktop process that somehow reached `UvcDeviceHandle` would fail at the symbol rather than at
  the library, which is a worse error than the one it replaces.
- **Robolectric is a JUnit sandbox.** That is fine for tests. It is not yet a way to run the
  Dashboard Server, which is a long-running `main()` and would have to live inside a test that
  never returns; the Driver Hub Dashboard therefore still cannot run a vision OpMode. That is the
  next decision, and it is deliberately not taken here.
