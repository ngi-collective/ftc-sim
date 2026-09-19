# Protocol fixtures

Frames captured verbatim off a live dashboard session — `mise run dashboard` serving TeamCode's
`VerityRobot` and `org.firstinspires.ftc.teamcode.iamyou`, with a motor stalled and the robot
driving, so the divergence and non-default-behavior paths are real rather than constructed.

`sim-scene.json` and `sim-bodies.json` come from a second session, `--scenario practice-balls`,
with `BasicMecanumTeleOp` driven into the balls. Both need a field that has something on it:
before scenarios could be loaded the scene's `elements` array was always empty, so the capture
pinned the key's presence and nothing about an element, and a body frame had nothing to describe.
`sim-bodies.json` is taken mid-shove, so the balls the robot reached have moved from where the
scene staged them and are spinning; one it never touched is still at its staged position with an
identity orientation, which is honest and is why the test asserts the law &mdash; a ball that has
moved has rolled &mdash; rather than a count.

Both carry the tipping HIVE's seams. Each of `sim-scene.json`'s two HIVEs has a `pivot` (a point
on its axis, the axis, and the angle its solids are drawn at) and the four FLOWERs and the A-frame
have `"pivot": null`; every tag carries `attachedTo`, naming the HIVE whose CELL it is stuck to.
`sim-bodies.json` carries `pivots`, one entry per HIVE, which is where a live tip angle arrives.
Both HIVEs are at a stop in this capture &mdash; red at 0 and blue at 60&deg; &mdash; because the
session that produced it was shoving balls about on the floor, not shooting.

`sim-scenarios.json` comes from a session started `--scenario match-staging`, so `active` names a
file. The interesting field is the one the capture cannot show: `active` is null for a session on
the robot's own field, and it has to be **present** and null rather than omitted, or a browser
cannot tell "no scenario loaded" from "a server that has never heard of scenarios". `WireFormatTest`
asserts that case separately. The same is true of `pivot`: this protocol serialises nulls on
purpose, so a bolted-down structure carries the key with a null rather than dropping it.

`sim-score.json` comes from a third session, `--scenario match-staging`, which is the manual's own
match setup: three NECTAR in each upward-facing CELL and nothing else on the field. So the capture
is 6-6 before anyone has driven, which is a figure the game manual can be checked against rather
than one this repository chose. It holds two CELLs and not four because only an upward-facing CELL
can score, and it is in the connect greeting &mdash; unlike `sim-bodies.json` &mdash; because
nothing else on the wire lets the browser work a score out for itself. `redTips` and `blueTips`
are zero for the same reason the pivots are at their stops: nobody has shot at anything.

These files are the **only** shared vocabulary between
`Dashboard/src/main/java/org/ngicollective/testframework/dashboard/protocol/` and
`driver-hub-dashboard/src/protocol.ts`. Both sides pin them:

- `Dashboard/src/test/.../WireFormatTest.java` builds each protocol record with the values in the
  fixture and asserts Gson still produces that exact JSON tree. Renaming a Java field fails here.
- `driver-hub-dashboard/src/protocol.test.ts` parses each fixture through `protocol.ts` and
  asserts every field the Java side sends is a field the browser consumes. Updating the fixture
  without updating `protocol.ts` fails here.

So a Java rename fails the Java test; updating the fixture to match then fails the TS test. That
chain is the mechanism `protocol.ts`'s "keep in step with the Java side" comment asks for and
never had.

## Recapturing

Start a server on spare ports so a live session is undisturbed:

```
mise run dashboard-headless --args="--port 8775 --camera-port 8776"
```

Connect, drive an OpMode, stall a motor, and write the first frame of each `namespace/type`.
Two values are deliberately **not** verbatim, because they are environment-dependent:

- `layout-list.json` — `payload.directory` is replaced with `<layout-directory>`.
- `camera-stream.json` — `payload.url` holds the port the capture ran on (8776), not the
  default 8766. Tests assert the URL's shape, not its port.

Timestamps and pose floats are left exactly as captured; nothing asserts their values.
