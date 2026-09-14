#!/usr/bin/env python3
"""Regenerate TestFramework's committed tag36h11 bit patterns.

The simulated camera has to *draw* AprilTags, and the SDK's detector only accepts genuine
tag36h11 codewords. Deriving the family in Java would be a lot of code to get subtly wrong, and
calling OpenCV at render time would drag a native dependency into the one part of the simulator
that is deliberately pure Java and testable on a plain JVM. So the patterns are generated here,
once, and committed.

This script is expected to run approximately never: tag36h11 is a fixed family of 587 codes, and
all of them are committed, so a new season that picks different ids needs no regeneration. It
exists so the committed table has a stated provenance and can be reproduced rather than trusted.

Every entry is validated as it is generated: the marker is rendered and then detected with
apriltag3 itself, and must read back as its own id at a Hamming distance of zero. The script
refuses to write the table if any tag fails.

Usage:
    python3 -m venv /tmp/apriltag-venv
    /tmp/apriltag-venv/bin/pip install opencv-python-headless pupil-apriltags
    /tmp/apriltag-venv/bin/python tools/generate-tag36h11.py
"""

from pathlib import Path
import sys

import cv2
import numpy as np
from pupil_apriltags import Detector

DATA_CELLS = 6
CELLS_ACROSS = 8  # 6x6 of data inside a one-cell black border
OUTPUT = (Path(__file__).resolve().parent.parent / "TestFramework" / "src" / "main" / "java"
          / "org" / "ngicollective" / "testframework" / "camera" / "Tag36h11.java")


def codewords():
    """Every tag36h11 codeword, packed row-major MSB-first with a set bit meaning white."""
    dictionary = cv2.aruco.getPredefinedDictionary(cv2.aruco.DICT_APRILTAG_36h11)
    detector = Detector(families="tag36h11")
    words = []
    for tag_id in range(dictionary.bytesList.shape[0]):
        marker = dictionary.generateImageMarker(tag_id, CELLS_ACROSS, None, 1)
        cells = (cv2.resize(marker, (CELLS_ACROSS, CELLS_ACROSS),
                            interpolation=cv2.INTER_NEAREST) > 127).astype(int)
        if cells[0].any() or cells[-1].any() or cells[:, 0].any() or cells[:, -1].any():
            sys.exit("tag %d: expected a black border, got %s" % (tag_id, cells))

        word = 0
        for row in range(DATA_CELLS):
            for column in range(DATA_CELLS):
                if cells[row + 1][column + 1]:
                    word |= 1 << (DATA_CELLS * DATA_CELLS - 1 - (row * DATA_CELLS + column))
        words.append(word)

        # Provenance: apriltag3 must read this exact render back as this exact id. The white
        # margin is the quiet zone the detector needs to find the black square at all.
        scaled = cv2.resize(marker, (240, 240), interpolation=cv2.INTER_NEAREST)
        frame = np.full((320, 320), 255, np.uint8)
        frame[40:280, 40:280] = scaled
        hits = detector.detect(frame)
        if len(hits) != 1 or hits[0].tag_id != tag_id or hits[0].hamming != 0:
            sys.exit("tag %d did not survive a round trip through apriltag3: %s"
                     % (tag_id, [(h.tag_id, h.hamming) for h in hits]))
    return words


def rotations(word):
    grid = [[(word >> (DATA_CELLS * DATA_CELLS - 1 - (r * DATA_CELLS + c))) & 1
             for c in range(DATA_CELLS)] for r in range(DATA_CELLS)]
    for _ in range(4):
        value = 0
        for r in range(DATA_CELLS):
            for c in range(DATA_CELLS):
                if grid[r][c]:
                    value |= 1 << (DATA_CELLS * DATA_CELLS - 1 - (r * DATA_CELLS + c))
        yield value
        grid = [list(row) for row in zip(*grid[::-1])]


def minimum_hamming_distance(words):
    """The property the family is named for, and the table's own checksum."""
    smallest = DATA_CELLS * DATA_CELLS
    for i, left in enumerate(words):
        for right in words[i + 1:]:
            for rotated in rotations(right):
                smallest = min(smallest, bin(left ^ rotated).count("1"))
    return smallest


def main():
    words = codewords()
    if len(set(words)) != len(words):
        sys.exit("generated %d codewords but only %d are distinct"
                 % (len(words), len(set(words))))
    distance = minimum_hamming_distance(words)
    if distance != 11:
        sys.exit("minimum Hamming distance is %d, but tag36h11 guarantees 11" % distance)
    print("%d codewords, all distinct, minimum Hamming distance %d, all validated by apriltag3"
          % (len(words), distance))

    source = OUTPUT.read_text()
    start = source.index("    private static final long[] CODEWORDS = {")
    end = source.index("    };", start) + len("    };")
    rows = []
    for i in range(0, len(words), 4):
        chunk = words[i:i + 4]
        rows.append("            " + " ".join("0x%09XL," % w for w in chunk)
                    + "  // %d-%d" % (i, i + len(chunk) - 1))
    replacement = ("    private static final long[] CODEWORDS = {\n"
                   + "\n".join(rows) + "\n    };")
    OUTPUT.write_text(source[:start] + replacement + source[end:])
    print("rewrote %s" % OUTPUT)


if __name__ == "__main__":
    main()
