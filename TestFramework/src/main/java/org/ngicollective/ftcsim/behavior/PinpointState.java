package org.ngicollective.ftcsim.behavior;

import org.ngicollective.ftcsim.sim.PinpointMount;
import org.ngicollective.ftcsim.sim.Pose2d;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

/**
 * A goBILDA Pinpoint as its I&sup2;C registers see it: what it was told, what it has counted, and
 * where it thinks the robot is.
 *
 * <p>Register numbers and layouts are the device's wire protocol, read off the SDK's
 * {@code GoBildaPinpointDriver} (firmware version 3, the one with a configurable bulk read and
 * CRC). The tests drive this through that driver unmodified, so a layout that drifts from it fails
 * there rather than in a match.</p>
 *
 * <p>Every method is synchronized: the OpMode thread reads and writes registers while the
 * simulation thread advances the device.</p>
 */
public class PinpointState {

    public static final int DEVICE_ID = 1;
    public static final int DEVICE_VERSION = 2;
    public static final int DEVICE_STATUS = 3;
    public static final int DEVICE_CONTROL = 4;
    public static final int LOOP_TIME = 5;
    public static final int X_ENCODER_VALUE = 6;
    public static final int Y_ENCODER_VALUE = 7;
    public static final int X_POSITION = 8;
    public static final int Y_POSITION = 9;
    public static final int H_ORIENTATION = 10;
    public static final int X_VELOCITY = 11;
    public static final int Y_VELOCITY = 12;
    public static final int H_VELOCITY = 13;
    public static final int MM_PER_TICK = 14;
    public static final int X_POD_OFFSET = 15;
    public static final int Y_POD_OFFSET = 16;
    public static final int YAW_SCALAR = 17;
    public static final int BULK_READ = 18;
    public static final int QUATERNION_W = 19;
    public static final int QUATERNION_X = 20;
    public static final int QUATERNION_Y = 21;
    public static final int QUATERNION_Z = 22;
    public static final int PITCH = 23;
    public static final int ROLL = 24;
    public static final int SET_BULK_READ = 25;

    /** The firmware this simulates: the first with a configurable bulk read and CRC. */
    public static final int FIRMWARE_VERSION = 3;

    /** Status bits, as {@code GoBildaPinpointDriver.DeviceStatus} decodes them. */
    public static final int STATUS_READY = 1;
    public static final int STATUS_CALIBRATING = 1 << 1;

    /** How long a gyro recalibration takes; the driver documents "approximately 0.25 seconds". */
    public static final double CALIBRATION_SECONDS = 0.25;

    /** A loop time inside the 500&ndash;1100 &micro;s band the driver calls healthy. */
    public static final int LOOP_TIME_MICROSECONDS = 1000;

    private static final int CONTROL_RECALIBRATE_IMU = 1;
    private static final int CONTROL_RESET_POS_AND_IMU = 1 << 1;
    private static final int CONTROL_Y_REVERSED = 1 << 2;
    private static final int CONTROL_Y_FORWARD = 1 << 3;
    private static final int CONTROL_X_REVERSED = 1 << 4;
    private static final int CONTROL_X_FORWARD = 1 << 5;

    /** The driver's own default scope, which a version 3 device must match until told otherwise. */
    private static final int[] DEFAULT_BULK_SCOPE = {
            DEVICE_STATUS, LOOP_TIME, X_ENCODER_VALUE, Y_ENCODER_VALUE, X_POSITION, Y_POSITION,
            H_ORIENTATION, X_VELOCITY, Y_VELOCITY, H_VELOCITY
    };

    private final PinpointMount mount;

    // What the OpMode told the device. The resolution starts at the 4-Bar pod's, an assumption:
    // the power-on default is not documented, and every driver path writes it before reading.
    private double ticksPerMm = PinpointMount.GOBILDA_4_BAR_TICKS_PER_MM;
    private double xPodOffsetMm;
    private double yPodOffsetMm;
    private boolean xReversed;
    private boolean yReversed;
    private double yawScalar = 1.0;
    private int[] bulkScope = DEFAULT_BULK_SCOPE.clone();

    // What it has measured.
    private PinpointOdometry.Estimate estimate = PinpointOdometry.Estimate.ORIGIN;
    private double xVelocityMmPerSecond;
    private double yVelocityMmPerSecond;
    private double headingVelocityRadiansPerSecond;
    private double xTicks;
    private double yTicks;
    private double calibratingSeconds;

    // The world's truth, published by the drive model each step.
    private Pose2d chassisPose;
    private Pose2d trackedPose;

    public PinpointState(PinpointMount mount) {
        this.mount = mount;
    }

    public PinpointMount mount() {
        return mount;
    }

    /** Where the chassis really is, after this step of the world. */
    public synchronized void observeChassis(Pose2d pose) {
        chassisPose = pose;
        if (trackedPose == null) {
            trackedPose = pose;
        }
    }

    /**
     * The chassis was moved without driving: placed on the field, not pushed. The pods' wheels did
     * not turn, so the device's estimate must not move either.
     */
    public synchronized void chassisPlaced(Pose2d pose) {
        chassisPose = pose;
        trackedPose = pose;
    }

    /** Counts the pods' motion since the last step and folds it into the estimate. */
    public synchronized void track(double elapsedSeconds) {
        calibratingSeconds = Math.max(0.0, calibratingSeconds - elapsedSeconds);
        if (chassisPose == null) {
            return;
        }
        PinpointOdometry.PodTicks raw =
                PinpointOdometry.podTicks(trackedPose, chassisPose, mount);
        double turned = PinpointOdometry.turned(trackedPose, chassisPose);
        trackedPose = chassisPose;

        PinpointOdometry.PodTicks counted = PinpointOdometry.directed(raw, settings());
        xTicks += counted.x;
        yTicks += counted.y;

        PinpointOdometry.Estimate next =
                PinpointOdometry.integrate(estimate, raw, turned, settings());
        if (elapsedSeconds > 0.0) {
            xVelocityMmPerSecond = (next.xMm - estimate.xMm) / elapsedSeconds;
            yVelocityMmPerSecond = (next.yMm - estimate.yMm) / elapsedSeconds;
            headingVelocityRadiansPerSecond =
                    (next.headingRadians - estimate.headingRadians) / elapsedSeconds;
        }
        estimate = next;
    }

    public synchronized PinpointOdometry.Estimate estimate() {
        return estimate;
    }

    public synchronized PinpointOdometry.Settings settings() {
        return new PinpointOdometry.Settings(
                ticksPerMm, xPodOffsetMm, yPodOffsetMm, xReversed, yReversed, yawScalar);
    }

    public synchronized boolean calibrating() {
        return calibratingSeconds > 0.0;
    }

    /**
     * The bytes a read of {@code length} from {@code register} returns: the value, little-endian,
     * then a CRC8 of it. A driver not checking CRC simply reads one byte fewer.
     */
    public synchronized byte[] read(int register, int length) {
        byte[] value = register == BULK_READ ? bulk() : word(register);
        byte[] framed = Arrays.copyOf(value, value.length + 1);
        framed[value.length] = crc8(value);
        return Arrays.copyOf(framed, length);
    }

    /** A write from the driver. Registers the device does not accept are ignored, as on I&sup2;C. */
    public synchronized void write(int register, byte[] data) {
        switch (register) {
            case DEVICE_CONTROL:
                control(intOf(data));
                break;
            case X_POSITION:
                estimate = new PinpointOdometry.Estimate(
                        floatOf(data), estimate.yMm, estimate.headingRadians);
                break;
            case Y_POSITION:
                estimate = new PinpointOdometry.Estimate(
                        estimate.xMm, floatOf(data), estimate.headingRadians);
                break;
            case H_ORIENTATION:
                estimate = new PinpointOdometry.Estimate(
                        estimate.xMm, estimate.yMm, floatOf(data));
                break;
            case MM_PER_TICK:
                // Named per tick, holding ticks per millimetre: the driver writes
                // 1 / toMm(1 / ticksPerUnit) here.
                ticksPerMm = floatOf(data);
                break;
            case X_POD_OFFSET:
                xPodOffsetMm = floatOf(data);
                break;
            case Y_POD_OFFSET:
                yPodOffsetMm = floatOf(data);
                break;
            case YAW_SCALAR:
                yawScalar = floatOf(data);
                break;
            case SET_BULK_READ:
                bulkScope = new int[data.length];
                for (int i = 0; i < data.length; i++) {
                    bulkScope[i] = data[i];
                }
                break;
            default:
                break;
        }
    }

    private void control(int bits) {
        if ((bits & CONTROL_RESET_POS_AND_IMU) != 0) {
            estimate = PinpointOdometry.Estimate.ORIGIN;
            calibratingSeconds = CALIBRATION_SECONDS;
        }
        if ((bits & CONTROL_RECALIBRATE_IMU) != 0) {
            calibratingSeconds = CALIBRATION_SECONDS;
        }
        if ((bits & CONTROL_X_REVERSED) != 0) {
            xReversed = true;
        }
        if ((bits & CONTROL_X_FORWARD) != 0) {
            xReversed = false;
        }
        if ((bits & CONTROL_Y_REVERSED) != 0) {
            yReversed = true;
        }
        if ((bits & CONTROL_Y_FORWARD) != 0) {
            yReversed = false;
        }
    }

    private byte[] bulk() {
        ByteBuffer buffer = ByteBuffer.allocate(bulkScope.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (int register : bulkScope) {
            buffer.put(word(register));
        }
        return buffer.array();
    }

    private byte[] word(int register) {
        switch (register) {
            case DEVICE_ID:
                return intBytes(1);
            case DEVICE_VERSION:
                return intBytes(FIRMWARE_VERSION);
            case DEVICE_STATUS:
                return intBytes(calibrating() ? STATUS_CALIBRATING : STATUS_READY);
            case LOOP_TIME:
                return intBytes(LOOP_TIME_MICROSECONDS);
            case X_ENCODER_VALUE:
                return intBytes((int) Math.round(xTicks));
            case Y_ENCODER_VALUE:
                return intBytes((int) Math.round(yTicks));
            case X_POSITION:
                return floatBytes(estimate.xMm);
            case Y_POSITION:
                return floatBytes(estimate.yMm);
            case H_ORIENTATION:
                return floatBytes(estimate.headingRadians);
            case X_VELOCITY:
                return floatBytes(xVelocityMmPerSecond);
            case Y_VELOCITY:
                return floatBytes(yVelocityMmPerSecond);
            case H_VELOCITY:
                return floatBytes(headingVelocityRadiansPerSecond);
            case MM_PER_TICK:
                return floatBytes(ticksPerMm);
            case X_POD_OFFSET:
                return floatBytes(xPodOffsetMm);
            case Y_POD_OFFSET:
                return floatBytes(yPodOffsetMm);
            case YAW_SCALAR:
                return floatBytes(yawScalar);
            case QUATERNION_W:
                return floatBytes(Math.cos(estimate.headingRadians / 2.0));
            case QUATERNION_Z:
                return floatBytes(Math.sin(estimate.headingRadians / 2.0));
            default:
                // QUATERNION_X/Y, PITCH, ROLL: a robot on a flat field.
                return floatBytes(0.0);
        }
    }

    /** CRC8 with the driver's parameters: initial 0x90, polynomial 0x31, no final XOR. */
    static byte crc8(byte[] data) {
        byte crc = (byte) 0x90;
        for (byte b : data) {
            crc ^= b;
            for (int i = 0; i < 8; i++) {
                crc = (crc & 0x80) != 0 ? (byte) ((crc << 1) ^ 0x31) : (byte) (crc << 1);
            }
        }
        return crc;
    }

    private static byte[] intBytes(int value) {
        return ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array();
    }

    private static byte[] floatBytes(double value) {
        return ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putFloat((float) value).array();
    }

    private static int intOf(byte[] data) {
        return ByteBuffer.wrap(Arrays.copyOf(data, 4)).order(ByteOrder.LITTLE_ENDIAN).getInt();
    }

    private static float floatOf(byte[] data) {
        return ByteBuffer.wrap(Arrays.copyOf(data, 4)).order(ByteOrder.LITTLE_ENDIAN).getFloat();
    }
}
