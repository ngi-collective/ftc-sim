package org.ngicollective.testframework.camera;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards on the committed tag36h11 table.
 *
 * <p>The table is generated data, and generated data that nobody checks is generated data that
 * quietly rots. A single flipped bit would not throw: it would render a tag the detector
 * silently refuses, and the failure would surface as "vision doesn't work" days later. These
 * tests are cheap and they make that impossible.</p>
 */
class Tag36h11Test {

    /**
     * Tag 30 as apriltag3 itself rendered it, read off the detected image during the table's
     * generation and transcribed here by hand.
     *
     * <p>An independent fixture on purpose: it came from a rendering that the real detector
     * confirmed reads back as id 30, not from the same code path being tested. {@code #} is a
     * white cell, {@code .} is black, and the outer ring is the tag's black border.</p>
     */
    private static final String[] TAG_30 = {
            "........",
            "..##.##.",
            ".#......",
            ".##...#.",
            "..###.#.",
            "..#.#...",
            ".#..###.",
            "........",
    };

    @Test
    void tagThirtyMatchesWhatApriltagItselfDrew() {
        for (int row = 0; row < Tag36h11.CELLS_ACROSS; row++) {
            for (int column = 0; column < Tag36h11.CELLS_ACROSS; column++) {
                boolean expected = TAG_30[row].charAt(column) == '#';
                assertEquals(expected, Tag36h11.isWhite(30, row, column),
                        "cell (" + row + ", " + column + ") of tag 30");
            }
        }
    }

    @Test
    void everyIdInTheFamilyHasItsOwnCodeword() {
        assertEquals(587, Tag36h11.FAMILY_SIZE, "tag36h11 has 587 codes");

        Set<Long> seen = new HashSet<>();
        for (int id = 0; id < Tag36h11.FAMILY_SIZE; id++) {
            assertTrue(seen.add(Tag36h11.codeword(id)),
                    "tag " + id + " repeats a codeword already used by another id");
        }
    }

    @Test
    void theFamilyKeepsTheMinimumHammingDistanceItIsNamedFor() {
        // "36h11": 36 data bits, minimum Hamming distance 11 between any two codes under any
        // rotation. That guarantee is what lets the detector correct read errors, and it is also
        // the table's own checksum: corrupt any entry and the minimum drops.
        long[] words = new long[Tag36h11.FAMILY_SIZE];
        for (int id = 0; id < words.length; id++) {
            words[id] = Tag36h11.codeword(id);
        }

        int smallest = Tag36h11.DATA_CELLS * Tag36h11.DATA_CELLS;
        int closestLeft = -1;
        int closestRight = -1;
        for (int left = 0; left < words.length; left++) {
            for (int right = left + 1; right < words.length; right++) {
                long rotated = words[right];
                for (int quarter = 0; quarter < 4; quarter++) {
                    int distance = Long.bitCount(words[left] ^ rotated);
                    if (distance < smallest) {
                        smallest = distance;
                        closestLeft = left;
                        closestRight = right;
                    }
                    rotated = rotateQuarterTurn(rotated);
                }
            }
        }

        assertEquals(11, smallest,
                "closest pair was tags " + closestLeft + " and " + closestRight);
    }

    /** One clockwise quarter turn of a packed 6x6 codeword. */
    private static long rotateQuarterTurn(long word) {
        int size = Tag36h11.DATA_CELLS;
        long turned = 0L;
        for (int row = 0; row < size; row++) {
            for (int column = 0; column < size; column++) {
                int from = row * size + column;
                if (((word >>> (size * size - 1 - from)) & 1L) == 0L) {
                    continue;
                }
                // Clockwise: the cell at (row, column) lands at (column, size - 1 - row).
                int to = column * size + (size - 1 - row);
                turned |= 1L << (size * size - 1 - to);
            }
        }
        return turned;
    }
}
