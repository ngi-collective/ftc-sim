# 8. The season is a module

Date: 2026-10-03

## Status

Accepted.

## Context

The simulator is meant to outlive one game (#24). FIRST releases a new one every September, and
everything that knows about BioBuzz was inside `:TestFramework`: the field (`BioBuzzField`,
`BioBuzzHive`, `BioBuzzFlowers`), the scoring (`BioBuzzScore`), the scenario schema
(`sim/ScenarioConfig`, which named HIVE tip states and CELLs), and the ball factories on
`GameElement`. `LocalDashboardBackend` called `BioBuzzScore.of` directly, and the `sim/score` wire
frame had `cells`, `redTips` and `blueTips`, which the browser printed as "CELLS" and "(1 TIP)".

The physics and camera layers were already generic. Structures are posed primitives on optional
pivots ([ADR-0004](0004-structures-are-published-as-posed-primitives.md)), a scoring volume is a
posed box, and a tag cluster is a list of tag poses. So the coupling was a handful of call sites,
not a design problem, and each season left in the core would make the next one a fork.

## Decision

**Move the game into its own Gradle module, `:Season-BioBuzz`, which depends on `:TestFramework`.
The core talks to it through one interface, `sim.Season`, and never imports it.**

- `Season` has two jobs: `scenario(ConfigJson)` reads a scenario file into a `SimulatedScene`, and
  `score(volumes, elements, swings)` returns a `FieldScore`, or null when there is nothing to score.
  The file's schema version is the season's, so `ConfigJson.read(path)` no longer checks it and
  the season calls `requireVersion`.
- `FieldScore` is the season-neutral answer: alliance totals, **volumes** (a region and what it
  holds) and **tallies** (something counted that is not an element in a place). A BioBuzz CELL is a
  volume and a HIVE TIP is a tally, sent for each alliance even at zero.
- The team's `SimulatedRobot` names its season through `season()`, which defaults to
  `Season.none()`. The team is the one who knows which game it is practising, and putting it on
  the robot left all 15 `LocalDashboardBackend` call sites unchanged. A `ServiceLoader` lookup
  was the alternative; it is a global, and nothing needed one yet.
- `sim/score` carries `volumes` and `tallies`. The browser shows the names it is sent and adds an
  S to a tally above one, so a new game needs no change to the page.
- `GameElement` keeps only what a ball is. POLLEN and NECTAR are `BioBuzzElements`. The core's
  own intake and push tests, which were tuned against those sizes, use a test-only `TestBalls`
  with the same numbers.

## Consequences

- A second season is a new module beside this one plus a `season()` override, with no edits to
  the core or the page.
- The Gradle dependency direction is the guard: `TestFramework` and `Dashboard` cannot import
  `season` without a cycle. `Dashboard`'s score and body publication tests depend on the season at
  test scope only.
- The `sim/score` frame changed shape. A browser built before this change shows no score against
  a newer server; `parseSimScore` returns null on the missing keys, so the page stays up.
- TIPs are still BioBuzz's word, but now only the season uses it. The browser's pluralisation
  ("2 TIPS") is English and assumes a noun that takes an S. A season whose word does not would
  need the plural on the wire.
