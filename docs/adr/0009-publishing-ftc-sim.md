# 9. Publishing ftc-sim

Date: 2026-10-03

## Status

Accepted. Decided in #30.

## Context

The simulator is moving out of this repository so that any FTC team can use it (#24). Several
outward-facing choices are hard to undo once other teams depend on the result: names, hosting,
licence, version policy, and how the app layer compiles against code that FIRST does not
publish.

Facts these decisions rest on, checked against primary sources on 2026-10-03:

- FIRST publishes only library AARs to Maven Central under `org.firstinspires.ftc`: RobotCore,
  Hardware, FtcCommon, RobotServer, Inspection, Blocks, OnBotJava, Vision, Tfod and game assets.
  `FtcRobotControllerActivity` is in none of them. It exists only as source in each team's fork,
  under a Qualcomm BSD notice.
- Maven Central grants `io.github.<username>` automatically, but not `io.github.<org>`. A domain
  can be verified with a DNS TXT record, and NGI controls `ngi-collective.org`. Hyphens are valid
  in a groupId.
- A Gradle plugin resolves by id from any repository listed in `pluginManagement`. Upstream's
  `settings.gradle` already lists `mavenCentral()`, so the Gradle Plugin Portal is not needed.
- Licences:
  - FIRST's SDK is BSD-3 with a no-patents clause and a no-endorsement clause.
  - ode4j is dual LGPL-2.1 / BSD-3, but its POM declares only LGPL.
  - AprilTag is BSD-2.
  - OpenFTC's AprilTag JNI is MIT, stated in per-file headers with no LICENSE file.
  - Java-WebSocket and Robolectric are MIT.

## Decision

- **Audience.** Publish properly now. Announce publicly only after a season has run on it.
- **Home and name.** The repository is `ngi-collective/ftc-sim`. The README says it is not
  affiliated with or endorsed by FIRST.
- **Hosting.** Maven Central only, verified through `ngi-collective.org` DNS.
- **Coordinates.**
  - Group: `org.ngi-collective.ftc-sim`.
  - Artifacts: `core` (was TestFramework), `dashboard`, `camera-stream`, `android-shims`, `app`,
    `testing` (was SimulatedApp-Testing), `season-<game>`, and `vision-natives` with one
    classifier per OS and architecture.
  - Plugin id: `org.ngi-collective.ftc-sim`.
- **Packages.** Rename `org.ngicollective.testframework.*` to `org.ngicollective.ftcsim.*` before
  the first release. `org.openftc.easyopencv.SyntheticCameras` keeps its package, because the seam
  it uses is package-private.
- **Licence.** BSD-3-Clause. A NOTICE file credits:
  - AprilTag (BSD-2)
  - OpenFTC's JNI (MIT)
  - ode4j, taken under its BSD-3 option

  Files that mirror SDK code keep their Qualcomm and FIRST notices.
- **The app layer ships as source.** `app` is a sources artifact. The plugin adds it, and its
  manifest, to the team's `simulated` source set, and it compiles against the team's own
  `FtcRobotController`. There is no stub jar to keep in step with the SDK. An SDK change that
  breaks it fails in the team's build, naming the line.
- **SDK support.** Only the current FTC SDK. The core's major version is the SDK's: `12.x.y`
  runs on SDK 12, and the first release is `12.0.0`.
- **Seasons.** Each season is released and versioned on its own, with semver from `1.0.0`.
  Each depends on the core through a range bounded by the SDK major, `[12.0, 13.0)`. A team
  writes `ftcSim { version = '12.0.0'; season = 'biobuzz'; seasonVersion = '1.0.0' }`. A season
  is frozen once the next game is announced, and its releases stay resolvable.
- **Releases.**
  - A `v12.x.y` tag builds the natives matrix, runs every test, signs, and uploads the core
    artifacts at one version.
  - A `season-<game>/vX.Y.Z` tag releases only that season.
  - Each upload waits for a manual "Publish" in the Central Portal, until a few releases have
    gone through cleanly.
- **Signing.** A project key, `ftc-sim releases <jarrod@ngi-collective.org>`, protected by a
  passphrase. It is stored as GitHub Actions secrets in a `release` environment that needs a
  maintainer's approval, with an offline backup held by the maintainer.
- **Maintainers.** jarrod@ngi-collective.org and jsoverson@gmail.com, who own the GitHub org
  and the Central namespace account.

## Consequences

- A team adds one plugin id and an `ftcSim` block. Nothing changes in `settings.gradle`.
- Shipping `app` as source means the plugin, not a dependency, controls how it is compiled.
  The library manifest that `:SimulatedApp` contributes today has to be supplied by the plugin.
- Every published file needs a signature, sources and javadoc jars (placeholders are accepted),
  and full POM metadata, plugin markers included. Sonatype ships no Gradle plugin, so publishing
  uses a community one.
- ode4j's POM says LGPL only, so licence scanners will flag it. The NOTICE file records the BSD
  election.
- **Known risk:** both maintainer addresses belong to one person. That protects against losing
  an account, not against the person being unavailable. Add a second adult to the GitHub org,
  the Central namespace and the release environment before the announcement.
