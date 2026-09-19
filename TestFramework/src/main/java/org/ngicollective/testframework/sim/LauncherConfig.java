package org.ngicollective.testframework.sim;

/**
 * One flywheel launcher as the configuration file describes it: what spins it, how fast a ball
 * leaves it, and where it is aimed.
 *
 * <p>Keyed in the file by the motor that drives it, because that is the one name both halves of
 * this need: the hardware map looks the motor up under it, and the world is told that launcher's
 * surface speed under it. A launcher naming a motor the robot does not declare is refused at load
 * &mdash; see {@link RobotConfig} &mdash; since a launcher with nothing turning it would read as a
 * physics bug rather than as the typo it is.</p>
 *
 * <h2>Why a launcher is driven by a motor and an intake by a servo</h2>
 *
 * <p>Not a style choice about which device is nicer. A shot's range comes from how fast the wheel
 * is <em>actually</em> turning, so the simulated shaft has to be something with inertia that an
 * OpMode can read back and wait for, which is a {@code DcMotorEx} with an encoder. An intake only
 * ever has to be running or not, which is all a {@link ServoConfig} offers.</p>
 *
 * <h2>Deliberately not here: a gear ratio</h2>
 *
 * <p>{@link MotorConfig#rpm()} is the speed of the output shaft at the gearbox the motor actually
 * has, and {@link #wheelRadiusMetres()} times {@link #transferEfficiency()} already spans every
 * linear scaling between that shaft and the ball. A third multiplicative constant doing the same
 * job would only ever give two numbers a chance to disagree about one belt.</p>
 */
public final class LauncherConfig {

    private final String motorName;
    private final double wheelRadius;
    private final double transferEfficiency;
    private final double spinUpSeconds;
    private final double exitYawDegrees;
    private final double exitPitchDegrees;
    private final VolumeConfig mouth;

    LauncherConfig(String motorName, double wheelRadiusMetres, double transferEfficiency,
                   double spinUpSeconds, double exitYawDegrees, double exitPitchDegrees,
                   VolumeConfig mouth) {
        this.motorName = motorName;
        this.wheelRadius = wheelRadiusMetres;
        this.transferEfficiency = transferEfficiency;
        this.spinUpSeconds = spinUpSeconds;
        this.exitYawDegrees = exitYawDegrees;
        this.exitPitchDegrees = exitPitchDegrees;
        this.mouth = mouth;
    }

    static LauncherConfig from(String motorName, ConfigJson json) {
        return new LauncherConfig(
                motorName,
                json.positive("wheelRadiusMetres"),
                json.fraction("transferEfficiency"),
                json.positive("spinUpSeconds"),
                json.number("exitYawDegrees"),
                json.number("exitPitchDegrees"),
                VolumeConfig.from(json.child("mouth")));
    }

    /** The motor whose shaft spins this flywheel, and the name this launcher is keyed under. */
    public String motorName() {
        return motorName;
    }

    /**
     * Radius of the wheel a ball touches, in metres: what turns shaft speed into surface speed.
     *
     * <p>The radius of the wheel and not of anything else in the mechanism. A 4&nbsp;in compliant
     * wheel is 0.0508&nbsp;m, and at a bare 5203's 6000&nbsp;rpm its surface runs at nearly
     * 32&nbsp;m/s &mdash; which is why the interesting part of a shooter's range is down at a third
     * of full power rather than at the top of the stick.</p>
     */
    public double wheelRadiusMetres() {
        return wheelRadius;
    }

    /**
     * What fraction of the wheel's surface speed a ball actually leaves with.
     *
     * <p>A ball squeezed between one flywheel and a fixed hood is dragged by the wheel and rubbed
     * by the hood, and leaves at roughly half the surface speed; two counter-rotating wheels grip
     * both sides and give up much less. So this is about 0.5 for the common single-wheel shooter
     * and approaches 1 for a two-wheel one, and which of those a robot has is a fact about the
     * robot rather than a constant of this simulator.</p>
     *
     * <p>Above 1 is refused by the loader, because a ball leaving faster than the surface throwing
     * it is free energy.</p>
     */
    public double transferEfficiency() {
        return transferEfficiency;
    }

    /**
     * Simulated seconds for the flywheel to reach free speed from a standstill.
     *
     * <p>The reason a launcher is worth simulating at all. A flywheel heavy enough to keep its
     * speed through a shot takes a second or two to get there, so an OpMode that fires the instant
     * it commands power throws short &mdash; and with an inertia-free motor, which is what every
     * other motor on this robot is, that mistake cannot be made. This number is what installs
     * {@code MotorBehaviors.ramping} on the launcher's motor instead.</p>
     */
    public double spinUpSeconds() {
        return spinUpSeconds;
    }

    /**
     * Where the ball is thrown, in the robot's own frame: yaw counter-clockwise from the nose.
     *
     * <p>The same convention as a camera mount and a distance sensor, so there is one aiming
     * vocabulary on this robot. A plain number rather than a positive one: a launcher canted to the
     * right is an ordinary thing to bolt on.</p>
     */
    public double exitYawDegrees() {
        return exitYawDegrees;
    }

    /** The same, up from the horizon. Positive is upward, which is where a target three feet up is. */
    public double exitPitchDegrees() {
        return exitPitchDegrees;
    }

    /**
     * The space a ball has to be in to be thrown.
     *
     * <p>Not a trigger and not a magazine: it is where the wheel is, and anything in it while the
     * wheel is spinning goes. That is what a real shoot-through robot does and it is why a driver
     * spins up <em>before</em> collecting &mdash; a ball cannot be held against a running flywheel
     * waiting for it to come up to speed. One ball leaves at a time because a ball takes time to
     * arrive, rather than because anything counts them.</p>
     *
     * <p>Necessarily clear of the chassis footprint. A ball authored inside a robot is evicted from
     * under it, so a mouth tucked behind the bumper would be a mouth nothing could ever be in.</p>
     */
    public VolumeConfig mouth() {
        return mouth;
    }
}
