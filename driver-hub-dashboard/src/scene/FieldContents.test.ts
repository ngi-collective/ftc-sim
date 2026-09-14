import { describe, expect, it } from 'vitest';
import scene from '../../../protocol-fixtures/sim-scene.json';
import type { SceneTag } from '../protocol';
import { clusterLabels } from './FieldContents';
import { scenePoint } from './frame';

const tags: SceneTag[] = scene.payload.tags;

/** Every corner of one cluster's tags, in the scene frame the label is placed in. */
function cornersOf(cluster: string): number[][] {
  return tags
    .filter((tag) => tag.cluster === cluster)
    .flatMap((tag) => tag.corners.map((corner) => scenePoint(corner)));
}

describe('naming the clusters on a captured field', () => {
  it('writes one label per cluster the server sent, and none for an empty field', () => {
    const labels = clusterLabels(tags);
    const clusters = [...new Set(tags.map((tag) => tag.cluster))];

    expect(labels.map((label) => label.cluster).sort()).toEqual([...clusters].sort());
    expect(clusterLabels([])).toEqual([]);
  });

  it('floats each label clear of the tags it is naming', () => {
    for (const label of clusterLabels(tags)) {
      const corners = cornersOf(label.cluster);
      const highest = Math.max(...corners.map((corner) => corner[1]));
      // Above the tags rather than printed across the middle of the pattern it names.
      expect(label.position[1]).toBeGreaterThan(highest);
    }
  });

  it('keeps each label over its own cluster rather than between two of them', () => {
    for (const label of clusterLabels(tags)) {
      const corners = cornersOf(label.cluster);
      const [x, , z] = label.position;
      expect(x).toBeGreaterThanOrEqual(Math.min(...corners.map((corner) => corner[0])));
      expect(x).toBeLessThanOrEqual(Math.max(...corners.map((corner) => corner[0])));
      expect(z).toBeGreaterThanOrEqual(Math.min(...corners.map((corner) => corner[2])));
      expect(z).toBeLessThanOrEqual(Math.max(...corners.map((corner) => corner[2])));
    }
  });

  it('puts the label on the same side of the field as its tags', () => {
    // The fixture's red clusters are at negative field X and its blue ones at positive; a label
    // that averaged the whole scene instead of one cluster would land near the centre line.
    const labels = clusterLabels(tags);
    for (const label of labels) {
      const corners = cornersOf(label.cluster);
      const meanX = corners.reduce((total, corner) => total + corner[0], 0) / corners.length;
      expect(Math.sign(label.position[0])).toBe(Math.sign(meanX));
    }
  });
});
