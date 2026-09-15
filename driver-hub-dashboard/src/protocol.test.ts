import { describe, expect, it } from 'vitest';
import cameraStreamFrame from '../../protocol-fixtures/camera-stream.json';
import deviceErrorFrame from '../../protocol-fixtures/device-error.json';
import deviceStateFrame from '../../protocol-fixtures/device-state.json';
import handshake from '../../protocol-fixtures/handshake.json';
import layoutListFrame from '../../protocol-fixtures/layout-list.json';
import opModeErrorFrame from '../../protocol-fixtures/opmode-error.json';
import opModeListFrame from '../../protocol-fixtures/opmode-list.json';
import opModeStatusFrame from '../../protocol-fixtures/opmode-status.json';
import simBodiesFrame from '../../protocol-fixtures/sim-bodies.json';
import simCameraFrame from '../../protocol-fixtures/sim-camera.json';
import simCameraSavedFrame from '../../protocol-fixtures/sim-camera-saved.json';
import simConfigFrame from '../../protocol-fixtures/sim-config.json';
import simPoseFrame from '../../protocol-fixtures/sim-pose.json';
import simSceneFrame from '../../protocol-fixtures/sim-scene.json';
import simStatusFrame from '../../protocol-fixtures/sim-status.json';
import telemetryFrame from '../../protocol-fixtures/telemetry-frame.json';
import unknownErrorFrame from '../../protocol-fixtures/unknown-error.json';
import {
  parseCameraMount,
  parseDeviceStates,
  parseEnvelope,
  parseLayoutList,
  parseOpModeList,
  parseSavedCameraMount,
  type CameraMountPayload,
  type CameraStreamInfo,
  type DeviceState,
  type Namespace,
  type OpModeInfo,
  type OpModeState,
  type OpModeStatus,
  type ScenePayload,
  type SceneCorner,
  type SceneElement,
  type SceneTag,
  type SimBodies,
  type SimBody,
  type SimChassis,
  type SimConfig,
  type SimDrivetrain,
  type SimField,
  type SimPose,
  type SimStatus,
  type TelemetryFrame,
} from './protocol';

/**
 * The wire contract, checked against frames captured off a live server.
 *
 * <p>Both halves of each assertion matter. The key list is declared
 * {@code satisfies (keyof SomeType)[]}, so renaming or dropping a field in {@code protocol.ts}
 * fails to compile; the same list is then compared against the fixture's own keys, so a field the
 * Java server sends and this file does not name fails at run time. Neither half can be satisfied by
 * restating the other: one is only about the TypeScript type, the other only about the JSON.</p>
 *
 * <p>That is what closes the loop with {@code WireFormatTest}: a Java rename fails there, updating
 * the fixture to match then fails here, and the browser cannot quietly stop consuming a field the
 * robot is still reporting.</p>
 */

/** Every key of {@code value}, and nothing else. Order-independent; nesting is asserted separately. */
function expectFields(value: unknown, named: readonly string[]): void {
  expect(value).toBeTypeOf('object');
  expect(value).not.toBeNull();
  expect(Object.keys(value as object).sort()).toEqual([...named].sort());
}

/**
 * The namespaces `DashboardServer` actually put on the wire during the capture. Declared against
 * `Namespace` rather than as bare strings: dropping one from the union — 'camera' was missing from
 * it for the whole life of the `camera/stream` handler — stops this file compiling.
 */
const SERVER_NAMESPACES = [
  'opmode',
  'telemetry',
  'device',
  'layout',
  'sim',
  'camera',
] satisfies Namespace[];

describe('the envelope', () => {
  const serverFrames = [
    opModeListFrame,
    opModeStatusFrame,
    opModeErrorFrame,
    telemetryFrame,
    deviceStateFrame,
    deviceErrorFrame,
    layoutListFrame,
    simPoseFrame,
    simConfigFrame,
    simSceneFrame,
    simStatusFrame,
    simCameraFrame,
    simCameraSavedFrame,
    cameraStreamFrame,
  ];

  it('carries only namespaces the browser declares', () => {
    for (const frame of serverFrames) {
      expect(SERVER_NAMESPACES).toContain(frame.namespace);
    }
  });

  it('names the camera namespace, which the stream frame arrives in', () => {
    // The drift this file was written for: the server has always sent camera/stream on connect and
    // useDashboard has always handled it, while the union said no such namespace existed. The
    // dispatch key is a template literal, so it widens to string and TypeScript never noticed.
    expect(cameraStreamFrame.namespace).toBe('camera');
    expect(SERVER_NAMESPACES).toContain('camera');
  });

  it('is three keys, whatever the payload is', () => {
    for (const frame of serverFrames) {
      expectFields(frame, ['namespace', 'type', 'payload']);
    }
  });

  it('survives text that is not a frame at all', () => {
    // An exception out of onmessage would take the page down; a dropped frame costs one tick.
    expect(parseEnvelope('{"namespace":')).toBeNull();
    expect(parseEnvelope('')).toBeNull();
    expect(parseEnvelope('null')).toBeNull();
    expect(parseEnvelope('[1,2,3]')).toBeNull();
    expect(parseEnvelope('{"type":"status","payload":{}}')).toBeNull();
    expect(parseEnvelope('{"namespace":"sim","payload":{}}')).toBeNull();
  });

  it('keeps a namespace this build has never heard of rather than dropping the frame', () => {
    // A server one version ahead names namespaces this browser does not know. Rejecting the
    // envelope would throw away its error message too, which is the one thing worth reading.
    const envelope = parseEnvelope(JSON.stringify(unknownErrorFrame));
    expect(envelope?.namespace).toBe('nope');
    expect(envelope?.type).toBe('error');
  });

  it('round-trips a captured frame through the parser unchanged', () => {
    expect(parseEnvelope(JSON.stringify(simPoseFrame))).toEqual(simPoseFrame);
  });
});

describe('error frames', () => {
  const errorFrames = [opModeErrorFrame, deviceErrorFrame, unknownErrorFrame];

  it('carry a message and nothing else, in every namespace that can fail', () => {
    for (const frame of errorFrames) {
      expect(frame.type).toBe('error');
      expectFields(frame.payload, ['message']);
      expect(frame.payload.message).toBeTypeOf('string');
    }
  });
});

describe('opmode frames', () => {
  it('list every field of an OpModeInfo the server sends', () => {
    const named = ['name', 'group', 'flavor', 'className'] satisfies (keyof OpModeInfo)[];
    expect(opModeListFrame.payload.opModes.length).toBeGreaterThan(0);
    for (const opMode of opModeListFrame.payload.opModes) {
      expectFields(opMode, named);
    }
  });

  it('wrap the list in an opModes key, which is what the parser looks for', () => {
    expectFields(opModeListFrame.payload, ['opModes']);
    const opModes: OpModeInfo[] | null = parseOpModeList(opModeListFrame.payload);
    expect(opModes).toHaveLength(opModeListFrame.payload.opModes.length);
  });

  it('use only the flavors and states the browser can render', () => {
    const flavors: OpModeInfo['flavor'][] = ['TeleOp', 'Autonomous'];
    for (const opMode of opModeListFrame.payload.opModes) {
      expect(flavors).toContain(opMode.flavor);
    }
    const states = ['STOPPED', 'INIT', 'RUNNING'] satisfies OpModeState[];
    expect(states).toContain(opModeStatusFrame.payload.state);
  });

  it('report a status of three fields, with no OpMode and no failure while stopped', () => {
    const named = ['opMode', 'state', 'failure'] satisfies (keyof OpModeStatus)[];
    expectFields(opModeStatusFrame.payload, named);
    // Gson serializes these nulls rather than omitting the keys, and the UI reads them as "no
    // OpMode selected" rather than "field missing" — so their presence is the contract.
    expect(opModeStatusFrame.payload.opMode).toBeNull();
    expect(opModeStatusFrame.payload.failure).toBeNull();
  });
});

describe('telemetry frames', () => {
  it('are a timestamp and the composed lines', () => {
    const named = ['timestamp', 'lines'] satisfies (keyof TelemetryFrame)[];
    expectFields(telemetryFrame.payload, named);
    expect(telemetryFrame.payload.timestamp).toBeTypeOf('number');
    for (const line of telemetryFrame.payload.lines) expect(line).toBeTypeOf('string');
  });
});

describe('device frames', () => {
  it('name every field of the flattened DeviceState', () => {
    const named = [
      'name',
      'kind',
      'behavior',
      'commandedPower',
      'physicalPower',
      'velocityTicksPerSecond',
      'position',
      'mode',
      'commandedPosition',
      'hornPosition',
      'yawDegrees',
      'yawRateDegreesPerSecond',
      'clippedCommandCount',
      'lastClippedCommand',
    ] satisfies (keyof DeviceState)[];
    expect(deviceStateFrame.payload.devices.length).toBeGreaterThan(0);
    for (const device of deviceStateFrame.payload.devices) expectFields(device, named);
  });

  it('use kinds the device rail knows how to draw, including the unknown one', () => {
    // A webcam is in the hardware map and has no simulated behavior of its own; the server still
    // reports it, as kind "unknown". A union without that member would make the rail render a
    // device it has no case for.
    const kinds: DeviceState['kind'][] = ['motor', 'servo', 'imu', 'unknown'];
    for (const device of deviceStateFrame.payload.devices) expect(kinds).toContain(device.kind);
    expect(deviceStateFrame.payload.devices.map((device) => device.kind)).toContain('unknown');
  });

  it('send a null mode for a device that has none, not an absent key', () => {
    const imu = deviceStateFrame.payload.devices.find((device) => device.kind === 'imu');
    expect(imu).toBeDefined();
    expect(imu?.mode).toBeNull();
  });

  it('wrap the list in a devices key, which is what the parser looks for', () => {
    expectFields(deviceStateFrame.payload, ['devices']);
    const devices: DeviceState[] | null = parseDeviceStates(deviceStateFrame.payload);
    expect(devices).toHaveLength(deviceStateFrame.payload.devices.length);
  });

  it('report commanded power diverging from physical power on a stalled motor', () => {
    // The capture ran with FL stalled. Both numbers crossing the wire is the whole point of the
    // frame: one of them is what the OpMode asked for and the other is what the robot did.
    const stalled = deviceStateFrame.payload.devices.find((device) => device.behavior === 'stalled');
    expect(stalled).toBeDefined();
    expect(stalled?.commandedPower).not.toBe(stalled?.physicalPower);
  });
});

describe('layout frames', () => {
  it('parse into the list and directory the rail shows', () => {
    expectFields(layoutListFrame.payload, ['layouts', 'directory']);
    const list = parseLayoutList(layoutListFrame.payload);
    // The directory is machine-dependent, so only its shape is the contract.
    expect(list?.directory).toBeTypeOf('string');
    expect(list?.layouts).toEqual(layoutListFrame.payload.layouts);
  });
});

describe('sim frames', () => {
  it('carry a pose of nine fields, in the FTC field frame', () => {
    const named = [
      'timestampMillis',
      'elapsedSeconds',
      'x',
      'y',
      'headingDegrees',
      'forwardVelocity',
      'lateralVelocity',
      'yawRateDegreesPerSecond',
      'wallContact',
    ] satisfies (keyof SimPose)[];
    expectFields(simPoseFrame.payload, named);
    // Captured while driving, so the values themselves are not the contract — their types are.
    expect(simPoseFrame.payload.timestampMillis).toBeTypeOf('number');
    expect(simPoseFrame.payload.headingDegrees).toBeTypeOf('number');
    expect(simPoseFrame.payload.wallContact).toBeTypeOf('boolean');
  });

  it('carry the robot and field geometry the scene is built from', () => {
    expectFields(simConfigFrame.payload, ['robot', 'field'] satisfies (keyof SimConfig)[]);
    expectFields(simConfigFrame.payload.robot, ['name', 'chassis', 'drivetrain']);
    expectFields(
      simConfigFrame.payload.robot.chassis,
      [
        'widthMetres',
        'lengthMetres',
        'heightMetres',
        'deckHeightMetres',
      ] satisfies (keyof SimChassis)[],
    );
    expectFields(
      simConfigFrame.payload.robot.drivetrain,
      [
        'type',
        'wheelRadiusMetres',
        'gearRatio',
        'trackWidthMetres',
        'wheelBaseMetres',
        'strafeEfficiency',
      ] satisfies (keyof SimDrivetrain)[],
    );
    expectFields(
      simConfigFrame.payload.field,
      ['sizeMetres', 'wallHeightMetres', 'tileMetres'] satisfies (keyof SimField)[],
    );
  });

  it('carry the scene as tags and elements, each tag a quad and a pattern', () => {
    expectFields(simSceneFrame.payload, ['tags', 'elements'] satisfies (keyof ScenePayload)[]);
    const tagFields = [
      'id',
      'cluster',
      'sizeMetres',
      'corners',
      'cells',
    ] satisfies (keyof SceneTag)[];
    const cornerFields = ['x', 'y', 'z'] satisfies (keyof SceneCorner)[];
    expect(simSceneFrame.payload.tags.length).toBeGreaterThan(0);
    for (const tag of simSceneFrame.payload.tags) {
      expectFields(tag, tagFields);
      // Four corners and a square pattern, or the renderer draws a tag the detector cannot read.
      expect(tag.corners).toHaveLength(4);
      for (const corner of tag.corners) expectFields(corner, cornerFields);
      for (const row of tag.cells) expect(row).toHaveLength(tag.cells.length);
    }
  });

  it('spell tag patterns in the two characters the texture builder understands', () => {
    for (const tag of simSceneFrame.payload.tags) {
      for (const row of tag.cells) expect(row).toMatch(/^[BW]+$/);
    }
  });

  it('name every field of a game element, ids included', () => {
    const named = [
      'id',
      'name',
      'x',
      'y',
      'z',
      'radiusMetres',
      'red',
      'green',
      'blue',
    ] satisfies (keyof SceneElement)[];
    // The capture is a `--scenario practice-balls` session, so there are six of them: three POLLEN
    // and three NECTAR. Before the scenario loader existed this array was empty and the fixture
    // pinned nothing about an element at all.
    expect(simSceneFrame.payload.elements.length).toBeGreaterThan(0);
    for (const element of simSceneFrame.payload.elements as SceneElement[]) {
      expectFields(element, named);
    }
  });

  it('carry each moving body as a position and an orientation, keyed by the scene element id', () => {
    const named = [
      'id',
      'x',
      'y',
      'z',
      'qx',
      'qy',
      'qz',
      'qw',
    ] satisfies (keyof SimBody)[];
    expectFields(simBodiesFrame.payload, [
      'timestampMillis',
      'elapsedSeconds',
      'bodies',
    ] satisfies (keyof SimBodies)[]);
    for (const body of simBodiesFrame.payload.bodies as SimBody[]) {
      expectFields(body, named);
    }

    // The join between the two payloads: a body whose id is in no scene element would be drawn
    // with no colour and no radius, which is to say not drawn at all.
    const ids = new Set(simSceneFrame.payload.elements.map((element) => element.id));
    for (const body of simBodiesFrame.payload.bodies) {
      expect(ids).toContain(body.id);
    }
  });

  it('capture bodies that are actually in motion, orientation and all', () => {
    // A fixture of resting balls would pin the field names and nothing about their meaning: every
    // quaternion would be the identity and every position would equal the scene's. This capture is
    // mid-shove, so a ball that stopped rolling in the simulation would change it.
    const atRest = simSceneFrame.payload.elements;
    const moved = simBodiesFrame.payload.bodies.filter((body) => {
      const start = atRest.find((element) => element.id === body.id);
      return start && Math.hypot(body.x - start.x, body.y - start.y) > 0.01;
    });
    expect(moved.length).toBe(simBodiesFrame.payload.bodies.length);
    const spinning = simBodiesFrame.payload.bodies.filter(
      (body) => Math.hypot(body.qx, body.qy, body.qz) > 0.01,
    );
    expect(spinning.length).toBe(simBodiesFrame.payload.bodies.length);
  });

  it('report the clock and the alliance as the browser spells them', () => {
    const named = ['multiplier', 'paused', 'alliance'] satisfies (keyof SimStatus)[];
    expectFields(simStatusFrame.payload, named);
    const alliances: SimStatus['alliance'][] = ['red', 'blue'];
    // Java's Alliance is an enum with @SerializedName; a browser reading RED instead of red would
    // silently take red's heading zero on a blue session.
    expect(alliances).toContain(simStatusFrame.payload.alliance);
  });
});

describe('camera frames', () => {
  it('advertise a stream URL, its size and its rate', () => {
    const named = ['url', 'width', 'height', 'framesPerSecond'] satisfies (keyof CameraStreamInfo)[];
    expectFields(cameraStreamFrame.payload, named);
    // The port is whatever the capture ran on, so the shape is the contract: an absolute HTTP URL
    // an <img> can be pointed at, because a page served from another machine cannot guess it.
    expect(cameraStreamFrame.payload.url).toMatch(/^http:\/\/[^/]+:\d+\/camera$/);
    expect(cameraStreamFrame.payload.width).toBeGreaterThan(0);
    expect(cameraStreamFrame.payload.height).toBeGreaterThan(0);
    expect(cameraStreamFrame.payload.framesPerSecond).toBeGreaterThan(0);
  });

  it('carry the mount in the units the robot configuration file is written in', () => {
    const named = [
      'name',
      'forwardMetres',
      'leftMetres',
      'heightMetres',
      'yawDegrees',
      'pitchDegrees',
      'rollDegrees',
      'horizontalFovDegrees',
      'verticalFovDegrees',
      'unsaved',
    ] satisfies (keyof CameraMountPayload)[];
    expectFields(simCameraFrame.payload, named);
    // Metres and degrees, unconverted: the editor shows these numbers as they are and a save has
    // to read back identically, so a millimetre or radian anywhere in this frame is a rewrite.
    expect(parseCameraMount(simCameraFrame.payload)).toEqual(simCameraFrame.payload);
  });

  it('identify the camera by the device name the rail already shows', () => {
    // The one place this frame and device/state have to agree. A second marker — a new
    // DeviceState.kind, a flag — is a second answer to "which device is the camera", and the two
    // can disagree; matching on the name cannot.
    const names = deviceStateFrame.payload.devices.map((candidate) => candidate.name);
    expect(names).toContain(simCameraFrame.payload.name);
  });

  it('refuse a mount with a number missing rather than aiming the camera at NaN', () => {
    // These numbers come off a hand-edited file and are multiplied into geometry every frame. One
    // NaN does not draw a wrong frustum, it drops the whole robot group out of the scene.
    const missing: Record<string, unknown> = { ...simCameraFrame.payload };
    delete missing.pitchDegrees;
    expect(parseCameraMount(missing)).toBeNull();
    expect(parseCameraMount({ ...simCameraFrame.payload, yawDegrees: '35' })).toBeNull();
    expect(parseCameraMount({ ...simCameraFrame.payload, unsaved: 'false' })).toBeNull();
    expect(parseCameraMount(null)).toBeNull();
  });

  it('answer a save with the absolute path it wrote, which is the file to commit', () => {
    expectFields(simCameraSavedFrame.payload, ['path']);
    // Absolute because the browser may be nowhere near the machine holding the repository: a
    // relative path is a path only the server can resolve, and it is shown to be typed into git.
    expect(simCameraSavedFrame.payload.path.startsWith('/')).toBe(true);
    expect(parseSavedCameraMount(simCameraSavedFrame.payload)).toEqual(simCameraSavedFrame.payload);
  });
});

describe('the connect burst', () => {
  it('is the frames a fresh browser needs before it can render, in order', () => {
    expect(handshake.observed).toEqual([...handshake.onOpen, ...handshake.thenFromTicker]);
  });

  it('opens with a camera frame, whose namespace this union nearly missed', () => {
    // camera/stream has no periodic broadcast: it is sent exactly once, on connect. A namespace
    // the browser does not declare therefore costs the camera panel the whole session, not a tick.
    // That the burst's frames all reach a sink is asserted against applyMessage, in messages.test.
    expect(handshake.onOpen).toContain('camera/stream');
    for (const frame of handshake.observed) {
      expect(SERVER_NAMESPACES).toContain(frame.split('/')[0]);
    }
  });
});
