package android.opengl;

import org.firstinspires.ftc.robotcore.external.matrices.OpenGLMatrix;
import org.firstinspires.ftc.robotcore.external.navigation.AngleUnit;
import org.firstinspires.ftc.robotcore.external.navigation.AxesOrder;
import org.firstinspires.ftc.robotcore.external.navigation.AxesReference;
import org.firstinspires.ftc.robotcore.external.navigation.Orientation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the {@code android.opengl.Matrix} shim, since a wrong answer here would quietly corrupt
 * every simulated IMU reading rather than throwing.
 */
class MatrixTest {

    private static final float[] IDENTITY = {
            1, 0, 0, 0,
            0, 1, 0, 0,
            0, 0, 1, 0,
            0, 0, 0, 1,
    };

    @Test
    void rotationAboutZTakesTheXAxisToTheYAxis() {
        float[] rotation = new float[16];
        Matrix.setRotateM(rotation, 0, 90.0f, 0.0f, 0.0f, 1.0f);

        float[] rotated = new float[4];
        Matrix.multiplyMV(rotated, 0, rotation, 0, new float[]{1, 0, 0, 1}, 0);

        assertArrayEquals(new float[]{0, 1, 0, 1}, rotated, 1e-6f);
    }

    @Test
    void multiplicationComposesInTheOrderOpenGlDocuments() {
        float[] translate = new float[16];
        Matrix.setIdentityM(translate, 0);
        Matrix.translateM(translate, 0, 5.0f, 0.0f, 0.0f);
        float[] rotate = new float[16];
        Matrix.setRotateM(rotate, 0, 90.0f, 0.0f, 0.0f, 1.0f);

        // translate x rotate: rotate the point first, then move it along x.
        float[] composed = new float[16];
        Matrix.multiplyMM(composed, 0, translate, 0, rotate, 0);
        float[] result = new float[4];
        Matrix.multiplyMV(result, 0, composed, 0, new float[]{1, 0, 0, 1}, 0);

        assertArrayEquals(new float[]{5, 1, 0, 1}, result, 1e-6f);
    }

    @Test
    void inverseUndoesARotationAndTranslation() {
        float[] transform = new float[16];
        Matrix.setRotateM(transform, 0, 37.0f, 0.3f, 0.6f, 0.9f);
        Matrix.translateM(transform, 0, 3.0f, -4.0f, 5.0f);

        float[] inverse = new float[16];
        assertTrue(Matrix.invertM(inverse, 0, transform, 0));

        float[] product = new float[16];
        Matrix.multiplyMM(product, 0, transform, 0, inverse, 0);
        assertArrayEquals(IDENTITY, product, 1e-5f);
    }

    @Test
    void singularMatricesAreReportedRatherThanReturningGarbage() {
        float[] singular = new float[16];
        float[] inverse = new float[16];

        assertFalse(Matrix.invertM(inverse, 0, singular, 0));
    }

    @Test
    void scaleAndTranslateMatchTheirInPlaceOverloads() {
        float[] inPlace = new float[16];
        Matrix.setRotateM(inPlace, 0, 20.0f, 0.0f, 1.0f, 0.0f);
        float[] source = inPlace.clone();

        Matrix.scaleM(inPlace, 0, 2.0f, 3.0f, 4.0f);
        Matrix.translateM(inPlace, 0, 1.0f, 2.0f, 3.0f);

        float[] outOfPlace = new float[16];
        Matrix.scaleM(outOfPlace, 0, source, 0, 2.0f, 3.0f, 4.0f);
        float[] translated = new float[16];
        Matrix.translateM(translated, 0, outOfPlace, 0, 1.0f, 2.0f, 3.0f);

        assertArrayEquals(inPlace, translated, 1e-6f);
    }

    @Test
    void theSdkOrientationRoundTripSurvivesThisArithmetic() {
        // The real reason this shim exists: OpenGLMatrix -> Orientation is the path every IMU
        // reading takes, so it has to come back with the angles it went in with.
        OpenGLMatrix rotation = OpenGLMatrix.rotation(
                AxesReference.INTRINSIC, AxesOrder.ZYX, AngleUnit.DEGREES, 90.0f, 10.0f, -20.0f);

        Orientation roundTripped = Orientation.getOrientation(
                rotation, AxesReference.INTRINSIC, AxesOrder.ZYX, AngleUnit.DEGREES);

        assertEquals(90.0f, roundTripped.firstAngle, 1e-3);
        assertEquals(10.0f, roundTripped.secondAngle, 1e-3);
        assertEquals(-20.0f, roundTripped.thirdAngle, 1e-3);
    }
}
