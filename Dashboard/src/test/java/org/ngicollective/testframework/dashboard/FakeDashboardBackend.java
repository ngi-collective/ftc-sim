package org.ngicollective.testframework.dashboard;

import org.ngicollective.testframework.camera.FrameSource;
import org.ngicollective.testframework.dashboard.protocol.Alliance;
import org.ngicollective.testframework.dashboard.protocol.BehaviorSpec;
import org.ngicollective.testframework.dashboard.protocol.BodiesPayload;
import org.ngicollective.testframework.dashboard.protocol.CameraMountPayload;
import org.ngicollective.testframework.dashboard.protocol.DeviceState;
import org.ngicollective.testframework.dashboard.protocol.GamepadState;
import org.ngicollective.testframework.dashboard.protocol.OpModeInfo;
import org.ngicollective.testframework.dashboard.protocol.OpModeStatus;
import org.ngicollective.testframework.dashboard.protocol.ScenariosPayload;
import org.ngicollective.testframework.dashboard.protocol.ScenePayload;
import org.ngicollective.testframework.dashboard.protocol.ScorePayload;
import org.ngicollective.testframework.dashboard.protocol.SimConfigPayload;
import org.ngicollective.testframework.dashboard.protocol.SimPose;
import org.ngicollective.testframework.dashboard.protocol.SimStatus;
import org.ngicollective.testframework.dashboard.protocol.TelemetryFrame;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * A backend that answers from settable fields and records what it was asked to do.
 *
 * <p>Hand-written rather than mocked because this interface is twenty-four methods wide and a
 * protocol test's question is almost always "what did the request turn into", which reads better as
 * a field to assert than as an argument captor. The state the protocol reads back &mdash;
 * {@link #simStatus} above all, which {@code sim/time} uses to fill in whichever field the browser
 * omitted &mdash; is writable for the same reason.</p>
 */
final class FakeDashboardBackend implements DashboardBackend {

    /**
     * An {@link ArrayList}, never {@code Collections.emptyList()}, for the reason
     * {@link LayoutStore#list()} spells out: this list goes straight to Gson, which resolves an
     * adapter from the runtime class, and {@code Collections$EmptyList} lives in a {@code java.util}
     * that is not open to the unnamed module. A fake that returned the convenient empty list would
     * be a fake that no real implementation may imitate.
     */
    List<OpModeInfo> opModes = new ArrayList<>();
    OpModeStatus status = OpModeStatus.stopped();
    SimStatus simStatus = new SimStatus(1.0, false, Alliance.RED);

    /** Null the way a real session's is null: no drivetrain, no scene, no camera, no score. */
    SimConfigPayload simConfig;
    ScenePayload scene;
    ScorePayload score;
    ScenariosPayload scenarios;

    /** Every name loadScenario was asked for, nulls included, in order. */
    final List<String> loadedScenarios = new ArrayList<>();
    FrameSource cameraFrames;
    CameraMountPayload cameraMount;

    /** Where a save says it wrote, so a protocol test can check the path reaches the saver. */
    Path cameraMountFile = Paths.get("/tmp/robot-config/fixture.json");

    /** Null until asked; boxed so a test can tell "not called" from "called with the default". */
    String initializedOpMode;
    Double simTimeMultiplier;
    Boolean simTimePaused;
    Integer steppedTicks;
    Double placedX;
    Double placedY;
    Double placedHeadingDegrees;
    Alliance alliance;
    Integer gamepadSlot;
    GamepadState gamepadState;
    String overriddenDevice;
    BehaviorSpec overriddenBehavior;
    String resetDevice;
    boolean started;
    boolean stopped;
    boolean closed;
    double[] aimedCameraMount;
    boolean savedCameraMount;
    boolean revertedCameraMount;

    private final List<Consumer<OpModeStatus>> statusListeners = new ArrayList<>();
    private final List<Consumer<TelemetryFrame>> telemetryListeners = new ArrayList<>();
    private final List<Consumer<List<DeviceState>>> deviceStateListeners = new ArrayList<>();
    private final List<Consumer<SimPose>> poseListeners = new ArrayList<>();
    private final List<Consumer<SimConfigPayload>> simConfigListeners = new ArrayList<>();
    private final List<Consumer<ScenePayload>> sceneListeners = new ArrayList<>();
    private final List<Consumer<BodiesPayload>> bodyListeners = new ArrayList<>();
    private final List<Consumer<ScorePayload>> scoreListeners = new ArrayList<>();
    private final List<Consumer<SimStatus>> simStatusListeners = new ArrayList<>();
    private final List<Consumer<CameraMountPayload>> cameraMountListeners = new ArrayList<>();

    @Override
    public List<OpModeInfo> listOpModes() {
        return opModes;
    }

    @Override
    public void initOpMode(String className) {
        initializedOpMode = className;
    }

    @Override
    public void start() {
        started = true;
    }

    @Override
    public void stop() {
        stopped = true;
    }

    @Override
    public OpModeStatus status() {
        return status;
    }

    @Override
    public void subscribeStatus(Consumer<OpModeStatus> listener) {
        statusListeners.add(listener);
    }

    @Override
    public void subscribeTelemetry(Consumer<TelemetryFrame> listener) {
        telemetryListeners.add(listener);
    }

    @Override
    public void subscribeDeviceState(Consumer<List<DeviceState>> listener) {
        deviceStateListeners.add(listener);
    }

    @Override
    public void subscribeSimPose(Consumer<SimPose> listener) {
        poseListeners.add(listener);
    }

    @Override
    public SimConfigPayload simConfig() {
        return simConfig;
    }

    @Override
    public void subscribeSimConfig(Consumer<SimConfigPayload> listener) {
        simConfigListeners.add(listener);
    }

    @Override
    public FrameSource cameraFrames() {
        return cameraFrames;
    }

    @Override
    public ScenePayload scene() {
        return scene;
    }

    @Override
    public void subscribeScene(Consumer<ScenePayload> listener) {
        sceneListeners.add(listener);
    }

    @Override
    public void subscribeBodies(Consumer<BodiesPayload> listener) {
        bodyListeners.add(listener);
    }

    @Override
    public ScorePayload score() {
        return score;
    }

    @Override
    public void subscribeScore(Consumer<ScorePayload> listener) {
        scoreListeners.add(listener);
    }

    @Override
    public ScenariosPayload scenarios() {
        return scenarios;
    }

    @Override
    public void loadScenario(String name) {
        loadedScenarios.add(name);
        if (scenarios != null) {
            // What a real backend does: the list is unchanged and the selection moves, which is
            // what lets a protocol test see the broadcast carry the new one.
            scenarios = new ScenariosPayload(scenarios.scenarios, scenarios.directory, name);
        }
    }

    @Override
    public CameraMountPayload cameraMount() {
        return cameraMount;
    }

    @Override
    public void setCameraMount(double forwardMetres, double leftMetres, double heightMetres,
                               double yawDegrees, double pitchDegrees, double rollDegrees) {
        aimedCameraMount = new double[] {forwardMetres, leftMetres, heightMetres,
                yawDegrees, pitchDegrees, rollDegrees};
    }

    @Override
    public Path saveCameraMount() {
        savedCameraMount = true;
        return cameraMountFile;
    }

    @Override
    public void revertCameraMount() {
        revertedCameraMount = true;
    }

    @Override
    public void subscribeCameraMount(Consumer<CameraMountPayload> listener) {
        cameraMountListeners.add(listener);
    }

    @Override
    public SimStatus simStatus() {
        return simStatus;
    }

    @Override
    public void subscribeSimStatus(Consumer<SimStatus> listener) {
        simStatusListeners.add(listener);
    }

    @Override
    public void setSimTime(double multiplier, boolean paused) {
        simTimeMultiplier = multiplier;
        simTimePaused = paused;
    }

    @Override
    public void stepSim(int ticks) {
        steppedTicks = ticks;
    }

    @Override
    public void placeRobot(double xMetres, double yMetres, double headingDegrees) {
        placedX = xMetres;
        placedY = yMetres;
        placedHeadingDegrees = headingDegrees;
    }

    @Override
    public void setAlliance(Alliance alliance) {
        this.alliance = alliance;
    }

    @Override
    public void injectGamepadState(int gamepad, GamepadState state) {
        gamepadSlot = gamepad;
        gamepadState = state;
    }

    @Override
    public void overrideDeviceBehavior(String device, BehaviorSpec spec) {
        overriddenDevice = device;
        overriddenBehavior = spec;
    }

    @Override
    public void resetDeviceBehavior(String device) {
        resetDevice = device;
    }

    @Override
    public void close() {
        closed = true;
    }

    // Pushes, as the control loop would make them.

    void emitStatus(OpModeStatus pushed) {
        statusListeners.forEach(listener -> listener.accept(pushed));
    }

    void emitTelemetry(TelemetryFrame frame) {
        telemetryListeners.forEach(listener -> listener.accept(frame));
    }

    void emitDeviceState(List<DeviceState> devices) {
        deviceStateListeners.forEach(listener -> listener.accept(devices));
    }

    void emitSimPose(SimPose pose) {
        poseListeners.forEach(listener -> listener.accept(pose));
    }

    void emitSimConfig(SimConfigPayload config) {
        simConfigListeners.forEach(listener -> listener.accept(config));
    }

    void emitScene(ScenePayload pushed) {
        sceneListeners.forEach(listener -> listener.accept(pushed));
    }

    void emitBodies(BodiesPayload pushed) {
        bodyListeners.forEach(listener -> listener.accept(pushed));
    }

    void emitScore(ScorePayload pushed) {
        scoreListeners.forEach(listener -> listener.accept(pushed));
    }

    void emitSimStatus(SimStatus pushed) {
        simStatusListeners.forEach(listener -> listener.accept(pushed));
    }

    void emitCameraMount(CameraMountPayload pushed) {
        cameraMountListeners.forEach(listener -> listener.accept(pushed));
    }
}
