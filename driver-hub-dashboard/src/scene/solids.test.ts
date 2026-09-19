import * as THREE from 'three';
import { describe, expect, it } from 'vitest';
import scene from '../../../protocol-fixtures/sim-scene.json';
import type { SceneSolid, SceneStructure } from '../protocol';
import { fieldPoint, scenePoint } from './frame';
import { solidGeometry, solidGeometryKey, solidPose } from './solids';

/**
 * A structure that is drawn wrong is still drawn, and that is the whole risk. A FLOWER tube lying
 * on its side, a pitch that tips a solid into the floor instead of out of it, a box whose height
 * and depth were swapped on the way into the scene frame: each of those is a picture, and a
 * picture is what someone will trust when they ask "can the camera see into that tube from here?".
 * Nothing in this repository can assert what WebGL painted, so these are assertions about the
 * arithmetic that decided it.
 *
 * <p>Every expectation is stated in the <em>field</em> frame — the frame the server publishes in
 * and the frame an autonomous path is planned in — by taking the pose this module produced and
 * carrying it back with {@code frame.ts}'s own inverse. So a failure here says "this solid is
 * turned the wrong way on the field", which is a sentence about the robot, rather than "this
 * quaternion has the wrong sign in its third component".</p>
 */

/** All zeros: a box of no size at the field's centre, turned no way at all. */
const UNPLACED: SceneSolid = {
  shape: 'box',
  x: 0,
  y: 0,
  z: 0,
  yawDegrees: 0,
  pitchDegrees: 0,
  rollDegrees: 0,
  lengthX: 0,
  lengthY: 0,
  lengthZ: 0,
  radiusMetres: 0,
  lengthMetres: 0,
  red: 226,
  green: 178,
  blue: 40,
};

function solid(published: Partial<SceneSolid>): SceneSolid {
  return { ...UNPLACED, ...published };
}

/**
 * A direction in the solid's own frame, as a field-frame direction, through the pose alone.
 *
 * <p>The mesh's local frame is the solid's local frame, with one exception the module documents:
 * a cylinder is stood up so that three.js' own {@code Y} axis is the solid's {@code +Z}. So
 * {@code [0, 1, 0]} asked of a cylinder is the question "which way does the tube point", and
 * {@code [1, 0, 0]} asked of anything is "which way is this solid facing".</p>
 */
function fieldDirection(published: SceneSolid, local: [number, number, number]): THREE.Vector3 {
  const [x, y, z, w] = solidPose(published).quaternion;
  const turned = new THREE.Vector3(...local).applyQuaternion(new THREE.Quaternion(x, y, z, w));
  const field = fieldPoint([turned.x, turned.y, turned.z]);
  return new THREE.Vector3(field.x, field.y, field.z);
}

/**
 * How far a drawn box reaches along a field axis, from the size it was given and the way it was
 * turned: a box's support width is the sum of each of its own extents projected onto that axis.
 * This is what catches a dimension that went into the wrong slot — a swap is invisible until
 * something is turned, and then it is the wrong shape rather than a wrong angle.
 */
function boxExtentAlong(published: SceneSolid, axis: THREE.Vector3): number {
  const geometry = solidGeometry(published);
  if (geometry.shape !== 'box') throw new Error('asked a cylinder for its box extents');
  const [alongX, alongY, alongZ] = geometry.sizeMetres;
  return (
    Math.abs(fieldDirection(published, [1, 0, 0]).dot(axis)) * alongX +
    Math.abs(fieldDirection(published, [0, 1, 0]).dot(axis)) * alongY +
    Math.abs(fieldDirection(published, [0, 0, 1]).dot(axis)) * alongZ
  );
}

const FIELD_X = new THREE.Vector3(1, 0, 0);
const FIELD_Y = new THREE.Vector3(0, 1, 0);
const FIELD_Z = new THREE.Vector3(0, 0, 1);

/**
 * The example from the structures contract: one BioBuzz FLOWER's ring and one of the posts under
 * it, at the red audience-side corner.
 */
const RING = solid({
  shape: 'cylinder',
  x: 0.594,
  y: -1.728,
  z: 0.54,
  radiusMetres: 0.0508,
  lengthMetres: 0.0127,
});
const POST = solid({
  x: 0.594,
  y: -1.728,
  z: 0.27,
  yawDegrees: 45,
  lengthX: 0.012,
  lengthY: 0.012,
  lengthZ: 0.54,
});

describe('the axis a cylinder is drawn about', () => {
  it('points an unturned tube straight up the field Z axis, as the wire promises', () => {
    // three.js builds a cylinder about its own Y and the wire says the axis is the solid's +Z, so
    // this is the correction that cannot be seen: a tube that came out along the field's Y would
    // be a FLOWER lying on the tiles like a dropped pipe, and it would still look like a FLOWER
    // from directly above.
    const axis = fieldDirection(RING, [0, 1, 0]);
    expect(axis.x).toBeCloseTo(0, 10);
    expect(axis.y).toBeCloseTo(0, 10);
    expect(axis.z).toBeCloseTo(1, 10);
  });

  it('leaves the tube upright however far it is yawed, since yaw turns about that axis', () => {
    expect(fieldDirection(solid({ ...RING, yawDegrees: 37 }), [0, 1, 0]).z).toBeCloseTo(1, 10);
  });

  it('lays the tube over when it is pitched, in the direction the pitch aims it', () => {
    // Pitch is positive upward, so pitching a tube a quarter turn aims its mouth at the ceiling's
    // far side: the axis leaves +Z and arrives at -X, which is where a nose pitched up from +X
    // leaves from. A pitch applied the other way up would send it to +X and the whole structure
    // would tip towards the audience instead of away.
    const axis = fieldDirection(solid({ ...RING, pitchDegrees: 90 }), [0, 1, 0]);
    expect(axis.x).toBeCloseTo(-1, 10);
    expect(axis.z).toBeCloseTo(0, 10);
  });

  it('stands only the cylinders up, and leaves a box at the same pose alone', () => {
    // The Y-up correction belongs to the cylinder's geometry, not to the pose: a box's own +Z is
    // its height and three.js already agrees about that. Standing everything up would draw every
    // post in a structure rolled a quarter turn, and a square post looks identical rolled — which
    // is exactly why this is asserted rather than looked at.
    const posed = { pitchDegrees: 90 };
    // three.js' vertical, asked of a box pitched a quarter turn: still the field's +Y, because
    // pitch turns about that axis and a box is drawn in the solid's own frame.
    expect(fieldDirection(solid(posed), [0, 1, 0]).y).toBeCloseTo(1, 10);
    // The same direction asked of a cylinder is the tube's axis instead, now laid over onto -X.
    expect(fieldDirection(solid({ ...posed, shape: 'cylinder' }), [0, 1, 0]).x).toBeCloseTo(-1, 10);
  });
});

describe('the order the three angles compose in', () => {
  it('turns a solid counter-clockwise seen from above the field, as a heading does', () => {
    const facing = fieldDirection(solid({ yawDegrees: 90 }), [1, 0, 0]);
    expect(facing.x).toBeCloseTo(0, 10);
    expect(facing.y).toBeCloseTo(1, 10);
  });

  it('lifts a pitched solid out of the floor rather than into it', () => {
    const facing = fieldDirection(solid({ pitchDegrees: 30 }), [1, 0, 0]);
    expect(facing.z).toBeCloseTo(0.5, 10);
    expect(facing.x).toBeCloseTo(Math.cos(Math.PI / 6), 10);
  });

  it('pitches about the solid own right, not about the field own Y', () => {
    // Yaw first, then pitch in the frame the yaw left behind. A solid yawed to face field +Y and
    // then pitched forty-five degrees must be aimed up and along +Y. Compose them the other way
    // round and the pitch turns about an axis the nose is already lying on, which does nothing at
    // all: the nose stays flat on (0, 1, 0) and the error is only visible in the one case where
    // two angles are set at once.
    const facing = fieldDirection(solid({ yawDegrees: 90, pitchDegrees: 45 }), [1, 0, 0]);
    expect(facing.x).toBeCloseTo(0, 10);
    expect(facing.y).toBeCloseTo(Math.SQRT1_2, 10);
    expect(facing.z).toBeCloseTo(Math.SQRT1_2, 10);
  });

  it('rolls last, so a roll spins a solid in place instead of re-aiming it', () => {
    const aimed = solid({ yawDegrees: 40, pitchDegrees: 25 });
    const rolled = solid({ ...aimed, rollDegrees: 90 });

    // Roll turns about the solid's own +X, which is the axis it is already facing along, so where
    // it points cannot change. Roll applied about the field's X instead would swing the aim.
    const facing = fieldDirection(rolled, [1, 0, 0]);
    expect(facing.angleTo(fieldDirection(aimed, [1, 0, 0]))).toBeCloseTo(0, 10);

    // And it does turn the solid: a quarter roll carries the solid's own +Z onto where its -Y was.
    const up = fieldDirection(rolled, [0, 0, 1]);
    expect(up.angleTo(fieldDirection(aimed, [0, 1, 0]).negate())).toBeCloseTo(0, 10);
  });
});

describe('where a published solid lands on the field', () => {
  it('draws the centre the server sent, at the height it sent', () => {
    // Field (0.594, -1.728, 0.54) is the audience side of the red half, half a metre up. Scene Y
    // is the height and scene Z is the negated field Y, which is the whole of frame.ts's story.
    expect(solidPose(RING).position).toEqual([0.594, 0.54, 1.728]);
    expect(fieldPoint(solidPose(RING).position)).toEqual({ x: 0.594, y: -1.728, z: 0.54 });
    // Same point, same answer as everything else on the field goes through: a structure drawn from
    // its own conversion is how the two views come to disagree about where the field is.
    expect(solidPose(POST).position).toEqual(scenePoint(POST));
  });

  it('stands the FLOWER post on the tiles and puts the ring above it', () => {
    // The post is published by its centre, so what says it is standing on the floor rather than
    // buried to the waist is its centre height against half its own extent.
    const height = boxExtentAlong(POST, FIELD_Z);
    expect(height).toBeCloseTo(0.54, 10);
    expect(POST.z - height / 2).toBeCloseTo(0, 10);
    expect(RING.z).toBeGreaterThan(POST.z + height / 2 - 1e-9);
  });
});

describe('which published lengths a solid is sized by', () => {
  it('gives a box its three extents along its own axes', () => {
    const shape = solid({
      lengthX: 0.1,
      lengthY: 0.2,
      lengthZ: 0.3,
      radiusMetres: 9,
      lengthMetres: 9,
    });
    expect(solidGeometry(shape)).toEqual({ shape: 'box', sizeMetres: [0.1, 0.2, 0.3] });
    // Unturned, each extent lies along the field axis of the same name — including the height,
    // which is the one that has to survive the trip through a Y-up scene frame.
    expect(boxExtentAlong(shape, FIELD_X)).toBeCloseTo(0.1, 10);
    expect(boxExtentAlong(shape, FIELD_Y)).toBeCloseTo(0.2, 10);
    expect(boxExtentAlong(shape, FIELD_Z)).toBeCloseTo(0.3, 10);
  });

  it('turns a yawed post about its height rather than about one of its sides', () => {
    // The contract's post is a square pipe stood on end and yawed forty-five degrees. Its footprint
    // therefore grows by root two across both ground axes while its height is untouched. Swap the
    // published Y and Z on the way into the scene frame and this is the case that says so: the post
    // comes out 0.54 m wide and 12 mm tall, and every angle in the pose still looks right.
    expect(boxExtentAlong(POST, FIELD_Z)).toBeCloseTo(0.54, 10);
    expect(boxExtentAlong(POST, FIELD_X)).toBeCloseTo(0.012 * Math.SQRT2, 10);
    expect(boxExtentAlong(POST, FIELD_Y)).toBeCloseTo(0.012 * Math.SQRT2, 10);
  });

  it('gives a cylinder its radius and its axial length, never the box pair', () => {
    const shape = solid({
      shape: 'cylinder',
      radiusMetres: 0.0508,
      lengthMetres: 0.0127,
      lengthX: 9,
      lengthY: 9,
      lengthZ: 9,
    });
    expect(solidGeometry(shape)).toEqual({
      shape: 'cylinder',
      radiusMetres: 0.0508,
      lengthMetres: 0.0127,
    });
  });

  it('shares one geometry between solids of one size and refuses to share between two', () => {
    // The key is what lets four FLOWERs be a handful of buffers. Too coarse a key draws one post
    // at another post's size, which is a structure that is subtly the wrong shape and nothing
    // else: no error, no missing mesh.
    const other = solid({ ...POST, x: -0.594, yawDegrees: 135, red: 40, green: 80, blue: 226 });
    expect(solidGeometryKey(solidGeometry(other))).toBe(solidGeometryKey(solidGeometry(POST)));

    const taller = solid({ ...POST, lengthZ: 0.55 });
    expect(solidGeometryKey(solidGeometry(taller))).not.toBe(
      solidGeometryKey(solidGeometry(POST)),
    );
    expect(solidGeometryKey(solidGeometry(RING))).not.toBe(solidGeometryKey(solidGeometry(POST)));
  });
});

describe('the structures on a captured field', () => {
  // Cast because a JSON import types every {@code shape} as a plain string, which is the one field
  // of the capture the discriminator needs to be narrower than.
  const structures = scene.payload.structures as SceneStructure[];

  it('draws every solid the server published with a size and a place', () => {
    // This is the join between two files nobody can diff: Java writes the names, this module
    // reads them. A renamed or misspelled length is not an error here — it is `undefined` folded
    // into a geometry of size zero, and a structure that is simply absent from the field view
    // while the payload that describes it arrives intact fifty times a session.
    expect(structures.length).toBeGreaterThan(0);
    for (const structure of structures) {
      expect(structure.solids.length).toBeGreaterThan(0);
      for (const published of structure.solids) {
        const geometry = solidGeometry(published);
        const extents =
          geometry.shape === 'box'
            ? geometry.sizeMetres
            : [geometry.radiusMetres, geometry.lengthMetres];
        for (const extent of extents) expect(extent).toBeGreaterThan(0);

        // And a pose that is a rotation rather than a NaN: one bad number does not draw a wrong
        // solid, it drops the whole group out of the canvas.
        const [x, y, z, w] = solidPose(published).quaternion;
        expect(Math.hypot(x, y, z, w)).toBeCloseTo(1, 10);
        expect(fieldPoint(solidPose(published).position)).toEqual({
          x: published.x,
          y: published.y,
          z: published.z,
        });
      }
    }
  });
});
