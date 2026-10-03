package org.firstinspires.ftc.teamcode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

import org.firstinspires.ftc.teamcode.simulated.ExampleRobot;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.ngicollective.ftcsim.dashboard.LocalDashboardBackend;
import org.ngicollective.ftcsim.dashboard.protocol.Alliance;
import org.ngicollective.ftcsim.dashboard.protocol.DeviceState;
import org.ngicollective.ftcsim.season.BioBuzzField;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;

/**
 * Which way heading zero points, per alliance.
 *
 * <p>The alliance stations are on the <b>X</b> axis, red at &minus;X, from the field CAD that also
 * places the season's AprilTags &mdash; see {@link BioBuzzField}. An alliance model on any other
 * axis would put every field-centric OpMode a quarter turn out from the tags it navigates by,
 * while still looking entirely plausible on screen, so the axis is worth a test that fails loudly
 * rather than a constant nobody checks.</p>
 *
 * <p>Asserted through the device snapshot the browser actually receives, because that is what a
 * driver reads and what an OpMode's {@code getRobotYawPitchRollAngles()} reports.</p>
 */
class AllianceHeadingTest {

    private final List<List<DeviceState>> snapshots = new CopyOnWriteArrayList<>();

    private LocalDashboardBackend backend;

    @BeforeEach
    void setUp() {
        backend = new LocalDashboardBackend(new ExampleRobot(), Collections.emptyList());
        backend.subscribeDeviceState(snapshots::add);
    }

    @AfterEach
    void tearDown() {
        backend.close();
    }

    /** Facing away from the red wall is facing +X, which red's driver calls straight ahead. */
    @Test
    void redsHeadingZeroFacesAwayFromTheRedWall() {
        backend.setAlliance(Alliance.RED);
        backend.placeRobot(0.0, 0.0, 0.0);

        assertEquals(0.0, reportedYaw(), 0.5);
    }

    /**
     * Blue's wall is at +X, so a robot facing +X is driving straight at its own drivers and must
     * report a reversed heading. The offset is the whole point of the alliance switch: a
     * field-centric OpMode that steers by IMU heading has to steer the same way for both drivers.
     */
    @Test
    void bluesHeadingZeroFacesAwayFromTheBlueWall() {
        backend.setAlliance(Alliance.BLUE);
        backend.placeRobot(0.0, 0.0, 0.0);

        assertEquals(180.0, Math.abs(reportedYaw()), 0.5);

        // And facing -X, away from blue's own wall, is blue's straight ahead.
        backend.placeRobot(0.0, 0.0, 180.0);
        assertEquals(0.0, reportedYaw(), 0.5);
    }

    /** The IMU yaw from the newest snapshot that has one. */
    private double reportedYaw() {
        snapshots.clear();
        await(() -> !snapshots.isEmpty(), "no device snapshot arrived");
        List<DeviceState> latest = snapshots.get(snapshots.size() - 1);
        for (DeviceState device : latest) {
            if ("imu".equals(device.kind)) {
                return device.yawDegrees;
            }
        }
        throw new AssertionError("the snapshot carried no IMU");
    }

    private static void await(BooleanSupplier condition, String message) {
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.yield();
        }
        fail(message);
    }
}
