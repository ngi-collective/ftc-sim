# ftc-sim

Run your FTC OpModes against a simulated robot on a simulated field: in unit tests, in a browser
dashboard, or in the real Robot Controller app on an emulator. The OpModes are unmodified; the
hardware, physics and camera underneath them are simulated.

Not affiliated with or endorsed by FIRST. FIRST Tech Challenge and FTC are trademarks of FIRST.

## Use it in your team's code

In your fork of `FtcRobotController` (SDK 12.x), `TeamCode/build.gradle`:

```groovy
plugins {
    id 'org.ngi-collective.ftc-sim' version '12.0.2'
}

// ...your existing lines...

ftcSim {
    version = '12.0.2'        // the simulator; its major version is the SDK's
    season = 'biobuzz'        // the game you are practising
    seasonVersion = '1.0.0'   // that game's own release
}
```

No `settings.gradle` change is needed: upstream's already lists `mavenCentral()`.

Then describe your robot:

1. **Its physics** in `TeamCode/robot-config/<name>.json`: chassis, wheels, motors, camera mount.
   Copy `TeamCode/robot-config/example.json` from this repository and edit the numbers.
2. **Its device names** in a class implementing `org.ngicollective.ftcsim.hardware.SimulatedRobot`,
   in `TeamCode/src/simulated/java/`. Device names are code so that a rename breaks the build.
   `ExampleRobot` here is a complete one. Return your season from `season()`.
3. **Register it** with one line naming the class in
   `TeamCode/src/simulated/resources/META-INF/services/org.ngicollective.ftcsim.hardware.SimulatedRobot`.

What you get:

- a `simulated` build flavour beside `robot`. The competition APK contains none of the simulator.
- unit tests that drive your OpModes headlessly and assert on where the robot ended up: see
  `TeamCode/src/testSimulated/` here
- `./gradlew :TeamCode:dashboard`: a Driver Hub in your browser at http://localhost:8765, with a
  3D field, gamepad input and the robot's camera view

The plugin raises TeamCode's compile SDK to 34 if it is lower (upstream ships 30). Minimum and
target SDK are unchanged. If Gradle runs out of memory, raise `org.gradle.jvmargs` in
`gradle.properties` to `-Xmx2048M`.

## Work on the simulator

The toolchain comes from [mise](https://mise.jdx.dev): `mise install`, then `mise run setup-sdk`
once.

```bash
mise run test        # every module's unit tests, the plugin's TestKit tests included
mise run dashboard   # the dashboard against the example TeamCode
mise tasks           # everything else
```

`CLAUDE.md` is the developer guide; the decisions behind the design are in `docs/adr/`.

## Licence

The simulator is BSD-3-Clause; see `LICENSE-ftc-sim` and `NOTICE`. `LICENSE` is FIRST's, and
covers the FTC SDK this repository is a fork of.
