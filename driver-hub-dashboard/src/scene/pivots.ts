import type { SceneStructure, SceneTag, ScenePivot } from '../protocol';
import { scenePoint, type ScenePoint } from './frame';

/**
 * A published hinge — a tipping HIVE — as the numbers a three.js {@code group} is driven with, and
 * the division of the scene into the parts that swing with it and the parts that do not.
 *
 * <p>The arithmetic is here rather than in the component for the reason {@code bodies.ts} gives:
 * this repository cannot assert what the WebGL canvas drew, so the sums behind the drawing live
 * somewhere a Node test can call them. What the component keeps is the three.js objects and the
 * render loop.</p>
 *
 * <p>Two facts shape the whole module. The first is that the wire sends the angle its
 * {@code solids} were <em>already posed at</em>, so a live angle is turned into a
 * <em>difference</em> — see {@link pivotTurnRadians}. The second is that one HIVE is one rigid
 * body: its solids, the two AprilTag clusters bolted to its CELLs and their labels all ride one
 * transform, so a tip costs one quaternion write per HIVE per frame rather than one per mesh.</p>
 */

/** A hinge, ready to hang a {@code group} on. */
export interface PivotPlacement {
  /** The pivot point, scene frame: where the group sits, and what its children are measured from. */
  position: ScenePoint;
  /** Unit axis in the scene frame, the rotation CCW-positive about it as the field frame has it. */
  axis: ScenePoint;
  /**
   * The angle the published solids already carry, radians. A live angle equal to this draws them
   * exactly as they arrived.
   */
  drawnRadians: number;
  /**
   * True when the wire gave no axis to turn about, so no live angle may move this group.
   *
   * <p>A zero-length axis is not merely useless: three.js' {@code setFromAxisAngle} trusts its
   * axis, and a non-unit one yields a non-unit quaternion, which is a <em>scale</em> on the group.
   * A HIVE that inflated or collapsed as it tipped would be a stranger failure than one that
   * refuses to tip.</p>
   */
  fixed: boolean;
}

/**
 * The hinge in the scene frame.
 *
 * <p>{@link scenePoint} is applied to the axis as well as to the point, which is the one thing here
 * worth stating: it maps {@code (x, y, z)} to {@code (x, z, -y)} with no translation term, so it
 * carries a direction exactly as it carries a position, and being a proper rotation it leaves the
 * sense of the turn alone — a CCW tip about field {@code +X} still lifts field {@code +Y} towards
 * field {@code +Z} once drawn. Writing a second conversion for directions is how the two would
 * drift apart, and a mirrored axis tips the HIVE the wrong way while looking entirely
 * reasonable.</p>
 *
 * <p>The axis is normalised rather than trusted. Java sends a unit vector, but a vector that came
 * out of a CAD measurement and a division is unit to within float rounding at best, and the error
 * lands as a scale on the group rather than as a wrong angle.</p>
 */
export function pivotPlacement(pivot: ScenePivot): PivotPlacement {
  const [ax, ay, az] = scenePoint({ x: pivot.axisX, y: pivot.axisY, z: pivot.axisZ });
  const length = Math.hypot(ax, ay, az);
  const usable = length > 0 && Number.isFinite(length);
  return {
    position: scenePoint(pivot),
    axis: usable ? [ax / length, ay / length, az / length] : [1, 0, 0],
    drawnRadians: pivot.angleRadians,
    fixed: !usable,
  };
}

/**
 * How far to turn the group, radians about {@link PivotPlacement#axis}.
 *
 * <p>The difference between where the structure is and where it was drawn — never the live angle
 * itself. Handing {@code setFromAxisAngle} the absolute angle draws the HIVE tipped twice over,
 * and that is the bug this function exists to be the only home of: at the few degrees a HIVE
 * spends most of a match at, double is still a plausible-looking HIVE.</p>
 *
 * <p>Zero when nothing has reported yet, which is the identity, which draws exactly the
 * {@code sim/scene} pose. Zero too for an angle that is not a number: a NaN quaternion removes the
 * group from the picture until the next frame overwrites it, and a structure that blinks out is
 * worse than one that lags.</p>
 */
export function pivotTurnRadians(
  placement: PivotPlacement,
  liveRadians: number | undefined,
): number {
  if (placement.fixed || liveRadians === undefined || !Number.isFinite(liveRadians)) return 0;
  return liveRadians - placement.drawnRadians;
}

/**
 * A scene-frame point as the offset from a pivot, which is what a child of the group is positioned
 * by.
 *
 * <p>Both conversions happen before the subtraction, and that is sound rather than lucky:
 * {@link scenePoint} is linear, so converting then subtracting and subtracting then converting are
 * the same three numbers. Doing it in the scene frame keeps {@code frame.ts} the only file that
 * knows which axis was negated.</p>
 *
 * <p>Every child of one group has to agree about this — the solids, the four corners of each tag
 * and the cluster label — or the parts of one rigid body are drawn swinging on hinges of their
 * own.</p>
 */
export function pivotLocal(point: ScenePoint, placement: PivotPlacement): ScenePoint {
  return [
    point[0] - placement.position[0],
    point[1] - placement.position[1],
    point[2] - placement.position[2],
  ];
}

/** One rigid body that swings: the hinge, the structure drawn on it, and the tags that ride it. */
export interface PivotGroup {
  /** The structure's name, which is also the key {@code sim/bodies} reports its angle under. */
  name: string;
  placement: PivotPlacement;
  structure: SceneStructure;
  /** The tags naming this structure in {@code attachedTo}, in the order the scene listed them. */
  tags: SceneTag[];
}

/** The scene divided into what can move and what cannot. */
export interface PivotSplit {
  groups: PivotGroup[];
  /** Structures with no hinge: the A-frame, the FLOWERs. Drawn exactly where the server put them. */
  fixed: SceneStructure[];
  /** Tags bolted to the field rather than to a hinge. */
  fixedTags: SceneTag[];
}

/**
 * Which tags swing with which structure.
 *
 * <p>Joined on the name the scene gave the structure, because the two tipping HIVEs are two
 * entries in a list whose order nothing promises; matched by position instead, the red HIVE's tags
 * would swing with the blue HIVE the first time Java reordered the list.</p>
 *
 * <p>A tag naming a structure that is not here, or one that has no hinge, stays with the field.
 * That is the safe direction: the tag is still drawn at the corners the server measured, which is
 * where it is. Guessing a hinge for it would move a tag the camera can see, and the two views
 * disagreeing about where a tag is is the one thing {@code sim/scene} exists to prevent.</p>
 */
export function splitByPivot(structures: SceneStructure[], tags: SceneTag[]): PivotSplit {
  const groups: PivotGroup[] = [];
  const fixed: SceneStructure[] = [];
  const byName = new Map<string, PivotGroup>();

  for (const structure of structures) {
    if (!structure.pivot) {
      fixed.push(structure);
      continue;
    }
    const group: PivotGroup = {
      name: structure.name,
      placement: pivotPlacement(structure.pivot),
      structure,
      tags: [],
    };
    groups.push(group);
    byName.set(structure.name, group);
  }

  const fixedTags: SceneTag[] = [];
  for (const tag of tags) {
    // Falsy rather than undefined: the wire sends an explicit null for a tag bolted to the
    // field, and an older server sends nothing at all.
    const group = tag.attachedTo ? byName.get(tag.attachedTo) : undefined;
    if (group) group.tags.push(tag);
    else fixedTags.push(tag);
  }

  return { groups, fixed, fixedTags };
}
