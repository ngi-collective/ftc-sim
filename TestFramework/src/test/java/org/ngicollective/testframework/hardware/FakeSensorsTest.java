package org.ngicollective.testframework.hardware;

import org.firstinspires.ftc.robotcore.external.navigation.DistanceUnit;
import org.junit.jupiter.api.Test;
import org.ngicollective.testframework.behavior.ColorBehaviors;
import org.ngicollective.testframework.behavior.DistanceBehaviors;
import org.ngicollective.testframework.behavior.TouchBehaviors;
import org.ngicollective.testframework.behavior.VoltageBehaviors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the simulated sensors as a group: a working one tracks the world, and every fault
 * behaviour reports something the world does not say.
 */
class FakeSensorsTest {

    @Test
    void aWorkingSwitchReportsPressedWhileSomethingIsInItsVolumeAndReleasesAfterwards() {
        FakeTouchSensor limit = new FakeTouchSensor("limit", TouchBehaviors.sensing());

        limit.state().setTouching(true);
        limit.advance(0.02);
        assertTrue(limit.isPressed());
        assertEquals(1.0, limit.getValue(), 1e-9);

        limit.state().setTouching(false);
        limit.advance(0.02);
        assertFalse(limit.isPressed(), "the arm left the switch, so it has to release");
        assertEquals(0.0, limit.getValue(), 1e-9);
    }

    @Test
    void aStuckSwitchKeepsReportingPressedWithNothingTouchingIt() {
        FakeTouchSensor limit = new FakeTouchSensor("limit", TouchBehaviors.stuckPressed());

        limit.state().setTouching(false);
        limit.advance(0.02);

        assertTrue(limit.isPressed());
    }

    @Test
    void aDisconnectedSwitchNeverReportsPressedNoMatterWhatTheWorldSays() {
        FakeTouchSensor limit = new FakeTouchSensor("limit", TouchBehaviors.disconnected());

        limit.state().setTouching(true);
        limit.advance(0.02);

        assertFalse(limit.isPressed());
    }

    @Test
    void aMeasuringDistanceSensorConvertsTheWorldRangeIntoTheRequestedUnit() {
        FakeDistanceSensor range = new FakeDistanceSensor("range", DistanceBehaviors.measuring());

        range.state().setWorldMetres(0.508);
        range.advance(0.02);

        assertEquals(0.508, range.getDistance(DistanceUnit.METER), 1e-9);
        assertEquals(508.0, range.getDistance(DistanceUnit.MM), 1e-6);
        assertEquals(20.0, range.getDistance(DistanceUnit.INCH), 1e-6);
    }

    @Test
    void anOutOfRangeReadingStaysNaNThroughAUnitConversion() {
        FakeDistanceSensor range = new FakeDistanceSensor("range", DistanceBehaviors.measuring());

        range.state().setWorldMetres(Double.NaN);
        range.advance(0.02);

        assertTrue(Double.isNaN(range.getDistance(DistanceUnit.METER)));
        assertTrue(Double.isNaN(range.getDistance(DistanceUnit.INCH)),
                "an OpMode guarding with isNaN would read a converted zero as a wall");
        assertTrue(Double.isNaN(range.getDistance(DistanceUnit.MM)));
    }

    @Test
    void aBlindDistanceSensorReportsNothingWithAWallInFrontOfIt() {
        FakeDistanceSensor range = new FakeDistanceSensor("range", DistanceBehaviors.blind());

        range.state().setWorldMetres(0.2);
        range.advance(0.02);

        assertTrue(Double.isNaN(range.getDistance(DistanceUnit.METER)));
    }

    @Test
    void aDistanceSensorStuckAtAReadingIgnoresTheWallMovingAway() {
        FakeDistanceSensor range = new FakeDistanceSensor("range", DistanceBehaviors.stuckAt(0.3));

        range.state().setWorldMetres(1.5);
        range.advance(0.02);

        assertEquals(300.0, range.getDistance(DistanceUnit.MM), 1e-6);
    }

    @Test
    void aWorkingColorSensorReportsTheChannelsAndTheLightItIsLookingAt() {
        FakeColorSensor color = new FakeColorSensor("color", ColorBehaviors.sensing());

        color.state().setWorldColor(200, 40, 10);
        color.advance(0.02);

        assertEquals(200, color.red());
        assertEquals(40, color.green());
        assertEquals(10, color.blue());
        assertEquals(83, color.alpha(), "total light is the mean of the three channels");
        assertEquals(0x53C8280A, color.argb());
    }

    @Test
    void aColorSensorSeesADifferentBallOnceTheWorldSwapsIt() {
        FakeColorSensor color = new FakeColorSensor("color", ColorBehaviors.sensing());
        color.state().setWorldColor(200, 40, 10);
        color.advance(0.02);

        color.state().setWorldColor(10, 40, 200);
        color.advance(0.02);

        assertEquals(10, color.red());
        assertEquals(200, color.blue());
    }

    @Test
    void aStuckColorSensorPutsEveryBallInTheSameBin() {
        FakeColorSensor color = new FakeColorSensor("color", ColorBehaviors.stuckAt(0, 0, 255));

        color.state().setWorldColor(200, 40, 10);
        color.advance(0.02);

        assertEquals(0, color.red());
        assertEquals(255, color.blue());
    }

    @Test
    void aBlindColorSensorReadsBlackWithAColoredBallInFrontOfIt() {
        FakeColorSensor color = new FakeColorSensor("color", ColorBehaviors.blind());

        color.state().setWorldColor(200, 40, 10);
        color.advance(0.02);

        assertEquals(0, color.red());
        assertEquals(0, color.alpha());
    }

    @Test
    void aWorkingVoltageSensorFollowsThePackAsItSags() {
        FakeVoltageSensor battery = new FakeVoltageSensor("battery", VoltageBehaviors.reporting());

        battery.state().setWorldVolts(12.4);
        battery.advance(0.02);
        assertEquals(12.4, battery.getVoltage(), 1e-9);

        battery.state().setWorldVolts(9.8);
        battery.advance(0.02);
        assertEquals(9.8, battery.getVoltage(), 1e-9);
    }

    @Test
    void aFlatVoltageSensorHidesASaggingPackFromTheOpMode() {
        FakeVoltageSensor battery = new FakeVoltageSensor("battery", VoltageBehaviors.flat(12.5));

        battery.state().setWorldVolts(9.0);
        battery.advance(0.02);

        assertEquals(12.5, battery.getVoltage(), 1e-9);
    }
}
