import * as THREE from 'three';
import { describe, expect, it } from 'vitest';
import scene from '../../../protocol-fixtures/sim-scene.json';
import pose from '../../../protocol-fixtures/sim-pose.json';
import type { SceneTag } from '../protocol';
import { fieldGround, fieldPoint, sceneGround, scenePoint, sceneYaw } from './frame';

/**
 * Where the chassis model's nose ends up once its group has been yawed for a heading. The model is
 * built with its nose along local {@code -Z}, so this is the only question the yaw has to answer.
 */
function nose(headingDegrees: number): THREE.Vector3 {
  return new THREE.Vector3(0, 0, -1).applyAxisAngle(
    new THREE.Vector3(0, 1, 0),
    sceneYaw(headingDegrees),
  );
}

/**
 * The front-face normal three.js derives for a triangle {@code (a, b, c)}: {@code (c - b) x
 * (a - b)}, which points at a viewer the triangle is wound counter-clockwise for.
 */
function frontNormal(a: THREE.Vector3, b: THREE.Vector3, c: THREE.Vector3): THREE.Vector3 {
  return new THREE.Vector3().subVectors(c, b).cross(new THREE.Vector3().subVectors(a, b)).normalize();
}

const YAW = new THREE.Vector3(0, 1, 0);

describe('the field-to-scene heading', () => {
  it('aims the nose along the scene X axis the field shares, at heading zero', () => {
    const forward = nose(0);
    expect(forward.x).toBeCloseTo(1, 10);
    expect(forward.y).toBeCloseTo(0, 10);
    expect(forward.z).toBeCloseTo(0, 10);
  });

  it('aims the nose at scene -Z at heading ninety, because that is where field +Y went', () => {
    const forward = nose(90);
    expect(forward.x).toBeCloseTo(0, 10);
    expect(forward.z).toBeCloseTo(-1, 10);
  });

  it('turns the nose the way a rising heading turns the robot, not the mirror of it', () => {
    // A rising field heading is counter-clockwise seen from above the field. Seen from above the
    // scene that is +X towards -Z: the sequence below is the only one a driver's turn can read as.
    expect(nose(180).x).toBeCloseTo(-1, 10);
    expect(nose(270).z).toBeCloseTo(1, 10);
    expect(nose(-90).z).toBeCloseTo(1, 10);
  });

  it('keeps a captured heading a quarter turn away from the same heading plus ninety', () => {
    const turned = nose(pose.payload.headingDegrees + 90);
    const straight = nose(pose.payload.headingDegrees);
    expect(turned.angleTo(straight)).toBeCloseTo(Math.PI / 2, 10);
    // And it turned the one way it can: an increasing heading is a positive rotation about scene
    // +Y, which is what carries the nose from +X towards -Z — the axis the field's own +Y went to.
    // A conversion that mirrored an axis would put this cross product on the other end of the
    // yaw axis, and the robot would turn away from the direction the driver is turning it.
    expect(new THREE.Vector3().crossVectors(straight, turned).dot(YAW)).toBeGreaterThan(0);
  });
});

describe('the field-to-scene position', () => {
  it('negates the field Y axis into scene Z and leaves the floor at scene Y zero', () => {
    expect(sceneGround(1, 2)).toEqual([1, 0, -2]);
  });

  it('reads a floor hit back into the frame the robot is placed in', () => {
    const [sceneX, , sceneZ] = sceneGround(pose.payload.x, pose.payload.y);
    const back = fieldGround(sceneX, sceneZ);
    expect(back.x).toBe(pose.payload.x);
    expect(back.y).toBe(pose.payload.y);
  });

  it('lifts the field height axis into scene Y', () => {
    expect(scenePoint({ x: 1, y: 2, z: 3 })).toEqual([1, 3, -2]);
  });

  it('round-trips a field point through the scene and back', () => {
    const corner = scene.payload.tags[0].corners[0];
    expect(fieldPoint(scenePoint(corner))).toEqual(corner);
  });
});

describe('a captured tag through the conversion', () => {
  const tags: SceneTag[] = scene.payload.tags;
  const cluster = tags.filter((tag) => tag.cluster === tags[0].cluster);

  it('has a cluster of square quads to work with, or this fixture is not the one described', () => {
    expect(cluster.length).toBeGreaterThan(1);
    for (const tag of cluster) expect(tag.corners).toHaveLength(4);
  });

  it('keeps each tag a square of its published size, with its corner order intact', () => {
    for (const tag of cluster) {
      const [topLeft, topRight, bottomRight, bottomLeft] = tag.corners.map(
        (corner) => new THREE.Vector3(...scenePoint(corner)),
      );

      // The two top-to-bottom edges and the two left-to-right edges: equal, parallel and the
      // tag's own size. A rotated or mirrored corner order turns this square into a bow tie whose
      // "edges" are the diagonals, which is longer by a factor of root two and no longer parallel.
      const acrossTop = new THREE.Vector3().subVectors(topRight, topLeft);
      const acrossBottom = new THREE.Vector3().subVectors(bottomRight, bottomLeft);
      const downLeft = new THREE.Vector3().subVectors(bottomLeft, topLeft);
      const downRight = new THREE.Vector3().subVectors(bottomRight, topRight);

      expect(acrossTop.length()).toBeCloseTo(tag.sizeMetres, 4);
      expect(acrossBottom.length()).toBeCloseTo(tag.sizeMetres, 4);
      expect(downLeft.length()).toBeCloseTo(tag.sizeMetres, 4);
      expect(downRight.length()).toBeCloseTo(tag.sizeMetres, 4);
      expect(acrossTop.clone().normalize().dot(acrossBottom.clone().normalize())).toBeCloseTo(1, 6);
      expect(downLeft.clone().normalize().dot(downRight.clone().normalize())).toBeCloseTo(1, 6);
      // A square, not a parallelogram leaning over: adjacent edges stay at right angles.
      expect(acrossTop.clone().normalize().dot(downLeft.clone().normalize())).toBeCloseTo(0, 6);
    }
  });

  it('leaves the visible face of the quad facing the way the field frame says it faces', () => {
    for (const tag of cluster) {
      const [topLeft, , bottomRight, bottomLeft] = tag.corners.map(
        (corner) => new THREE.Vector3(...scenePoint(corner)),
      );

      // The first triangle of QUAD_INDICES, [0, 3, 2] = top-left, bottom-left, bottom-right.
      const drawn = frontNormal(topLeft, bottomLeft, bottomRight);

      // The same normal taken in the field frame and then converted. The conversion is linear, so
      // a direction converts exactly as a point does; that the two agree is the whole claim. A
      // conversion that mirrored an axis would reverse this normal, and with the quad's material
      // culling its back face the tag would render from angles no camera could detect it from
      // while hiding from the one that can.
      const field = frontNormal(
        new THREE.Vector3(tag.corners[0].x, tag.corners[0].y, tag.corners[0].z),
        new THREE.Vector3(tag.corners[3].x, tag.corners[3].y, tag.corners[3].z),
        new THREE.Vector3(tag.corners[2].x, tag.corners[2].y, tag.corners[2].z),
      );
      const converted = new THREE.Vector3(...scenePoint({ x: field.x, y: field.y, z: field.z }));

      expect(drawn.dot(converted)).toBeCloseTo(1, 6);
    }
  });
});
