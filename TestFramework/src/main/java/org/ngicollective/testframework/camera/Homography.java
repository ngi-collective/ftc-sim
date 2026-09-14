package org.ngicollective.testframework.camera;

/**
 * A projective map between two planes, solved from four point correspondences.
 *
 * <p>This is what puts a flat tag image onto the flat quadrilateral a camera sees it as. A tag is
 * a plane and the image is a plane, so the exact relationship between them is a homography &mdash;
 * not an approximation, and not something a simpler affine map can express, because an affine map
 * cannot make the far edge of a tilted tag shorter than the near edge, which is precisely the
 * effect the detector's pose solver reads a tag's angle out of.</p>
 *
 * <p>Eight unknowns from four correspondences, solved by Gaussian elimination with partial
 * pivoting. Deliberately no dependency: this package stays runnable on a plain JVM, and a
 * hand-rolled 8x8 solve is a smaller liability than a matrix library that only exists on
 * Android.</p>
 */
final class Homography {

    private final double[] forward;
    private final double[] inverse;

    private Homography(double[] forward) {
        this.forward = forward;
        this.inverse = invert(forward);
    }

    /**
     * The map taking each {@code from[i]} to the corresponding {@code to[i]}.
     *
     * @param from four source points, as {@code {x, y}} pairs
     * @param to   four destination points, in the same order
     * @throws IllegalArgumentException if the correspondence is degenerate &mdash; three
     *     collinear source points, or a quad collapsed to a line &mdash; which has no unique
     *     solution and would otherwise silently produce infinities
     */
    static Homography mapping(double[][] from, double[][] to) {
        if (from.length != 4 || to.length != 4) {
            throw new IllegalArgumentException("a homography needs exactly four correspondences");
        }

        // Each correspondence contributes two rows of the 8x8 system; h33 is fixed at 1, which
        // costs nothing here because a degenerate h33 means a quad through the camera centre and
        // that is already rejected before rasterising.
        double[][] system = new double[8][9];
        for (int i = 0; i < 4; i++) {
            double x = from[i][0];
            double y = from[i][1];
            double u = to[i][0];
            double v = to[i][1];

            system[i * 2] = new double[] {x, y, 1.0, 0.0, 0.0, 0.0, -x * u, -y * u, u};
            system[i * 2 + 1] = new double[] {0.0, 0.0, 0.0, x, y, 1.0, -x * v, -y * v, v};
        }

        double[] solution = solve(system);
        return new Homography(new double[] {
                solution[0], solution[1], solution[2],
                solution[3], solution[4], solution[5],
                solution[6], solution[7], 1.0,
        });
    }

    /** Maps a point through the homography, writing {@code {x, y}} into {@code out}. */
    void apply(double x, double y, double[] out) {
        applyWith(forward, x, y, out);
    }

    /**
     * Maps a point back through the homography.
     *
     * <p>The direction a rasteriser actually uses: walk the pixels of the destination quad and ask
     * which point of the source plane each one came from. Walking the source instead would leave
     * gaps wherever the map stretches.</p>
     */
    void applyInverse(double x, double y, double[] out) {
        applyWith(inverse, x, y, out);
    }

    private static void applyWith(double[] m, double x, double y, double[] out) {
        double w = m[6] * x + m[7] * y + m[8];
        out[0] = (m[0] * x + m[1] * y + m[2]) / w;
        out[1] = (m[3] * x + m[4] * y + m[5]) / w;
    }

    /** Gaussian elimination with partial pivoting on an augmented matrix. */
    private static double[] solve(double[][] augmented) {
        int n = augmented.length;
        for (int column = 0; column < n; column++) {
            int pivot = column;
            for (int row = column + 1; row < n; row++) {
                if (Math.abs(augmented[row][column]) > Math.abs(augmented[pivot][column])) {
                    pivot = row;
                }
            }
            double[] swap = augmented[column];
            augmented[column] = augmented[pivot];
            augmented[pivot] = swap;

            if (Math.abs(augmented[column][column]) < 1e-12) {
                throw new IllegalArgumentException(
                        "degenerate correspondence: the four points do not define a quadrilateral");
            }

            for (int row = 0; row < n; row++) {
                if (row == column) {
                    continue;
                }
                double factor = augmented[row][column] / augmented[column][column];
                for (int k = column; k <= n; k++) {
                    augmented[row][k] -= factor * augmented[column][k];
                }
            }
        }

        double[] solution = new double[n];
        for (int i = 0; i < n; i++) {
            solution[i] = augmented[i][n] / augmented[i][i];
        }
        return solution;
    }

    /** Adjugate over determinant, for a 3x3 held row-major. */
    private static double[] invert(double[] m) {
        double a = m[4] * m[8] - m[5] * m[7];
        double b = m[5] * m[6] - m[3] * m[8];
        double c = m[3] * m[7] - m[4] * m[6];
        double determinant = m[0] * a + m[1] * b + m[2] * c;
        if (Math.abs(determinant) < 1e-12) {
            throw new IllegalArgumentException("this homography cannot be inverted");
        }
        return new double[] {
                a / determinant,
                (m[2] * m[7] - m[1] * m[8]) / determinant,
                (m[1] * m[5] - m[2] * m[4]) / determinant,
                b / determinant,
                (m[0] * m[8] - m[2] * m[6]) / determinant,
                (m[2] * m[3] - m[0] * m[5]) / determinant,
                c / determinant,
                (m[1] * m[6] - m[0] * m[7]) / determinant,
                (m[0] * m[4] - m[1] * m[3]) / determinant,
        };
    }
}
