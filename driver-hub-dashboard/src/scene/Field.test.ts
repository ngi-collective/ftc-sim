import { describe, expect, it } from 'vitest';
import { STANDARD_FIELD, tiles } from './Field';

/**
 * The floor a driver paces an autonomous against. Tiles are 24" and the perimeter is 141" inside a
 * 6x6 tile floor, so the edge row is 1.5" short on each side; a tile grid that scaled to fit
 * instead of clipping would read as a 23.5" pitch and mismeasure every count off it.
 */
const PITCH = STANDARD_FIELD.tileMetres;
const SIZE = STANDARD_FIELD.sizeMetres;

/** Every tile edge along one axis, deduplicated, in order. */
function seams(axis: 0 | 2): number[] {
  const edges = new Set<number>();
  for (const tile of tiles(SIZE, PITCH)) {
    const centre = tile.position[axis];
    const span = tile.scale[axis === 0 ? 0 : 1];
    edges.add(Number((centre - span / 2).toFixed(6)));
    edges.add(Number((centre + span / 2).toFixed(6)));
  }
  return [...edges].sort((a, b) => a - b);
}

describe('the standard field floor', () => {
  it('is six tiles by six, the last of them clipped rather than dropped', () => {
    expect(tiles(SIZE, PITCH)).toHaveLength(36);
  });

  it('puts its seams on the real 24 inch pitch, with a seam through the field centre', () => {
    const expected = [-SIZE / 2, -2 * PITCH, -PITCH, 0, PITCH, 2 * PITCH, SIZE / 2].map((value) =>
      Number(value.toFixed(6)),
    );
    expect(seams(0)).toEqual(expected);
    expect(seams(2)).toEqual(expected);
  });

  it('trims the edge row to the wall instead of letting it overhang', () => {
    const half = SIZE / 2;
    for (const tile of tiles(SIZE, PITCH)) {
      expect(tile.position[0] - tile.scale[0] / 2).toBeGreaterThanOrEqual(-half - 1e-9);
      expect(tile.position[0] + tile.scale[0] / 2).toBeLessThanOrEqual(half + 1e-9);
      expect(tile.position[2] - tile.scale[1] / 2).toBeGreaterThanOrEqual(-half - 1e-9);
      expect(tile.position[2] + tile.scale[1] / 2).toBeLessThanOrEqual(half + 1e-9);
    }
  });

  it('covers the whole floor exactly once: no gap at the wall, no tile over another', () => {
    const covered = tiles(SIZE, PITCH).reduce(
      (total, tile) => total + tile.scale[0] * tile.scale[1],
      0,
    );
    expect(covered).toBeCloseTo(SIZE * SIZE, 9);
  });

  it('alternates light and dark, so a counted tile is distinguishable from its neighbour', () => {
    const grid = new Map(tiles(SIZE, PITCH).map((tile) => [tile.key, tile.light]));
    expect(grid.get('0:0')).toBe(true);
    expect(grid.get('0:1')).toBe(false);
    expect(grid.get('1:0')).toBe(false);
    expect(grid.get('1:1')).toBe(true);
  });
});

describe('a field that happens to be a whole number of tiles', () => {
  it('lays it out with no sliver of a tile at the wall', () => {
    const whole = tiles(PITCH * 2, PITCH);
    expect(whole).toHaveLength(4);
    for (const tile of whole) {
      expect(tile.scale[0]).toBeCloseTo(PITCH, 9);
      expect(tile.scale[1]).toBeCloseTo(PITCH, 9);
    }
  });
});
