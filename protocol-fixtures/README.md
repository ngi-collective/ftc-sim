# Protocol fixtures

Frames captured verbatim off a live dashboard session — `mise run dashboard` serving TeamCode's
`VerityRobot` and `org.firstinspires.ftc.teamcode.iamyou`, with a motor stalled and the robot
driving, so the divergence and non-default-behavior paths are real rather than constructed.

`sim-scene.json` and `sim-bodies.json` come from a second session, `--scenario practice-balls`,
with `BasicMecanumTeleOp` driven into the balls. Both need a field that has something on it:
before scenarios could be loaded the scene's `elements` array was always empty, so the capture
pinned the key's presence and nothing about an element, and a body frame had nothing to describe.
`sim-bodies.json` is taken mid-shove — every ball moving, every orientation off the identity — so
a resting field could not be mistaken for it.

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
mise run dashboard --args="--port 8775 --camera-port 8776"
```

Connect, drive an OpMode, stall a motor, and write the first frame of each `namespace/type`.
Two values are deliberately **not** verbatim, because they are environment-dependent:

- `layout-list.json` — `payload.directory` is replaced with `<layout-directory>`.
- `camera-stream.json` — `payload.url` holds the port the capture ran on (8776), not the
  default 8766. Tests assert the URL's shape, not its port.

Timestamps and pose floats are left exactly as captured; nothing asserts their values.
