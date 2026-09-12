package android.opengl;

/**
 * A working {@code android.opengl.Matrix} for plain-JVM unit tests.
 *
 * <p>The SDK's {@code OpenGLMatrix} — which every {@code Orientation} conversion and therefore every
 * IMU reading goes through — delegates its arithmetic here. In an Android unit test the only
 * {@code android.opengl.Matrix} on the classpath comes from the stub {@code android.jar}, whose
 * methods throw {@code "Method setIdentityM in android.opengl.Matrix not mocked"}. That makes the
 * standard {@code new RevHubOrientationOnRobot(...)} line in an OpMode's init fatal, which would
 * rule out headless testing of essentially every modern OpMode.</p>
 *
 * <p>This class shadows that stub with the real column-major 4x4 arithmetic, so the SDK's own
 * orientation code runs unchanged and produces the numbers it would on a robot. Turning on
 * {@code unitTests.returnDefaultValues} instead would silence the exception and hand the SDK
 * all-zero matrices — wrong answers rather than loud ones.</p>
 *
 * <p>Lives in {@code :AndroidShims}, which is a {@code testImplementation} dependency only, so
 * nothing here is packaged into an APK, where the real framework class is used. Only the
 * operations the SDK actually calls are implemented; anything else fails loudly with
 * {@code NoSuchMethodError} rather than quietly returning nonsense.</p>
 *
 * <p>Matrices are 16-element column-major arrays: element (row {@code r}, column {@code c}) lives at
 * {@code m[offset + 4 * c + r]}, matching OpenGL and the {@code android.opengl.Matrix} contract.</p>
 */
public class Matrix {

    private Matrix() {
    }

    /** Writes the identity matrix into {@code sm}. */
    public static void setIdentityM(float[] sm, int smOffset) {
        for (int i = 0; i < 16; i++) {
            sm[smOffset + i] = 0.0f;
        }
        for (int i = 0; i < 16; i += 5) {
            sm[smOffset + i] = 1.0f;
        }
    }

    /** {@code result = lhs x rhs}. The result must not overlap either operand. */
    public static void multiplyMM(float[] result, int resultOffset,
                                  float[] lhs, int lhsOffset,
                                  float[] rhs, int rhsOffset) {
        for (int column = 0; column < 4; column++) {
            for (int row = 0; row < 4; row++) {
                float sum = 0.0f;
                for (int k = 0; k < 4; k++) {
                    sum += lhs[lhsOffset + 4 * k + row] * rhs[rhsOffset + 4 * column + k];
                }
                result[resultOffset + 4 * column + row] = sum;
            }
        }
    }

    /** {@code resultVec = lhsMat x rhsVec}, for 4-element vectors. */
    public static void multiplyMV(float[] resultVec, int resultVecOffset,
                                  float[] lhsMat, int lhsMatOffset,
                                  float[] rhsVec, int rhsVecOffset) {
        for (int row = 0; row < 4; row++) {
            float sum = 0.0f;
            for (int k = 0; k < 4; k++) {
                sum += lhsMat[lhsMatOffset + 4 * k + row] * rhsVec[rhsVecOffset + k];
            }
            resultVec[resultVecOffset + row] = sum;
        }
    }

    /** Writes a rotation of {@code a} degrees about the axis {@code (x, y, z)} into {@code rm}. */
    public static void setRotateM(float[] rm, int rmOffset, float a, float x, float y, float z) {
        setIdentityM(rm, rmOffset);

        double radians = Math.toRadians(a);
        float s = (float) Math.sin(radians);
        float c = (float) Math.cos(radians);

        float length = (float) Math.sqrt(x * x + y * y + z * z);
        if (length == 0.0f) {
            return;
        }
        if (length != 1.0f) {
            float recipLength = 1.0f / length;
            x *= recipLength;
            y *= recipLength;
            z *= recipLength;
        }

        float nc = 1.0f - c;
        float xy = x * y;
        float yz = y * z;
        float zx = z * x;
        float xs = x * s;
        float ys = y * s;
        float zs = z * s;

        rm[rmOffset] = x * x * nc + c;
        rm[rmOffset + 1] = xy * nc + zs;
        rm[rmOffset + 2] = zx * nc - ys;

        rm[rmOffset + 4] = xy * nc - zs;
        rm[rmOffset + 5] = y * y * nc + c;
        rm[rmOffset + 6] = yz * nc + xs;

        rm[rmOffset + 8] = zx * nc + ys;
        rm[rmOffset + 9] = yz * nc - xs;
        rm[rmOffset + 10] = z * z * nc + c;
    }

    /** {@code m = m x rotate(a, x, y, z)}, in place. */
    public static void rotateM(float[] m, int mOffset, float a, float x, float y, float z) {
        float[] source = new float[16];
        System.arraycopy(m, mOffset, source, 0, 16);
        rotateM(m, mOffset, source, 0, a, x, y, z);
    }

    /** {@code rm = m x rotate(a, x, y, z)}. */
    public static void rotateM(float[] rm, int rmOffset, float[] m, int mOffset,
                               float a, float x, float y, float z) {
        float[] rotation = new float[16];
        setRotateM(rotation, 0, a, x, y, z);
        float[] product = new float[16];
        multiplyMM(product, 0, m, mOffset, rotation, 0);
        System.arraycopy(product, 0, rm, rmOffset, 16);
    }

    /** {@code m = m x translate(x, y, z)}, in place. */
    public static void translateM(float[] m, int mOffset, float x, float y, float z) {
        for (int i = 0; i < 4; i++) {
            int index = mOffset + i;
            m[12 + index] += m[index] * x + m[4 + index] * y + m[8 + index] * z;
        }
    }

    /** {@code tm = m x translate(x, y, z)}. */
    public static void translateM(float[] tm, int tmOffset, float[] m, int mOffset,
                                  float x, float y, float z) {
        for (int i = 0; i < 12; i++) {
            tm[tmOffset + i] = m[mOffset + i];
        }
        for (int i = 0; i < 4; i++) {
            int source = mOffset + i;
            tm[tmOffset + 12 + i] = m[source] * x
                    + m[4 + source] * y
                    + m[8 + source] * z
                    + m[12 + source];
        }
    }

    /** {@code m = m x scale(x, y, z)}, in place. */
    public static void scaleM(float[] m, int mOffset, float x, float y, float z) {
        for (int i = 0; i < 4; i++) {
            int index = mOffset + i;
            m[index] *= x;
            m[4 + index] *= y;
            m[8 + index] *= z;
        }
    }

    /** {@code sm = m x scale(x, y, z)}. */
    public static void scaleM(float[] sm, int smOffset, float[] m, int mOffset,
                              float x, float y, float z) {
        for (int i = 0; i < 4; i++) {
            int source = mOffset + i;
            int target = smOffset + i;
            sm[target] = m[source] * x;
            sm[4 + target] = m[4 + source] * y;
            sm[8 + target] = m[8 + source] * z;
            sm[12 + target] = m[12 + source];
        }
    }

    /**
     * Writes the inverse of {@code m} into {@code mInv}.
     *
     * @return false if the matrix is singular, leaving {@code mInv} untouched
     */
    public static boolean invertM(float[] mInv, int mInvOffset, float[] m, int mOffset) {
        double[] source = new double[16];
        for (int i = 0; i < 16; i++) {
            source[i] = m[mOffset + i];
        }
        double[] cofactors = new double[16];

        for (int column = 0; column < 4; column++) {
            for (int row = 0; row < 4; row++) {
                // Transposed on purpose: the adjugate is the transpose of the cofactor matrix.
                double cofactor = minorDeterminant(source, column, row);
                cofactors[4 * column + row] = ((row + column) % 2 == 0) ? cofactor : -cofactor;
            }
        }

        double determinant = 0.0;
        for (int row = 0; row < 4; row++) {
            determinant += source[row] * cofactors[4 * row];
        }
        if (determinant == 0.0) {
            return false;
        }

        double recipDeterminant = 1.0 / determinant;
        for (int i = 0; i < 16; i++) {
            mInv[mInvOffset + i] = (float) (cofactors[i] * recipDeterminant);
        }
        return true;
    }

    /** Determinant of the 3x3 left after striking out the given row and column. */
    private static double minorDeterminant(double[] m, int skipRow, int skipColumn) {
        double[] minor = new double[9];
        int index = 0;
        for (int column = 0; column < 4; column++) {
            if (column == skipColumn) {
                continue;
            }
            for (int row = 0; row < 4; row++) {
                if (row == skipRow) {
                    continue;
                }
                minor[index++] = m[4 * column + row];
            }
        }
        return minor[0] * (minor[4] * minor[8] - minor[5] * minor[7])
                - minor[3] * (minor[1] * minor[8] - minor[2] * minor[7])
                + minor[6] * (minor[1] * minor[5] - minor[2] * minor[4]);
    }
}
