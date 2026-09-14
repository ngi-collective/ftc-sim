package org.firstinspires.ftc.teamcode.simulated;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.qualcomm.ftccommon.FtcEventLoop;
import com.qualcomm.ftccommon.FtcEventLoopIdle;
import com.qualcomm.ftccommon.FtcRobotControllerService;
import com.qualcomm.robotcore.eventloop.EventLoopManager;
import com.qualcomm.robotcore.eventloop.opmode.FtcRobotControllerServiceState;
import com.qualcomm.robotcore.eventloop.opmode.OpModeRegister;
import com.qualcomm.robotcore.util.RobotLog;
import com.qualcomm.robotcore.util.WebServer;

import org.firstinspires.ftc.ftccommon.internal.AnnotatedHooksClassFilter;
import org.firstinspires.ftc.robotcontroller.internal.FtcRobotControllerActivity;
import org.firstinspires.ftc.robotcore.internal.opmode.OnBotJavaHelper;
import org.ngicollective.testframework.hardware.SimulatedRobot;
import com.qualcomm.robotcore.exception.RobotCoreException;
import org.openftc.easyopencv.SyntheticCameras;

/**
 * The Robot Controller app, wired to a simulated robot.
 *
 * <p>Everything the app does is unchanged &mdash; OpMode discovery, the Driver Station protocol, the
 * web server, telemetry &mdash; except that the event loop is handed a
 * {@link SimulatedHardwareFactory}. That means an OpMode running here takes exactly the code path it
 * takes on a Control Hub, which is what the plain-JVM harness cannot offer.</p>
 *
 * <p>Why this reimplements the superclass's {@code onServiceBind} rather than calling it: the
 * factory is constructed inside the superclass's <em>private</em> {@code requestRobotSetup()}. Its
 * collaborators are all {@code protected}, so the honest option is to repeat those few lines here
 * with a different factory. If an SDK update changes {@code requestRobotSetup}, this method has to
 * be re-read against it &mdash; that is the maintenance cost of touching no vendor code.</p>
 */
public class SimulatedRobotControllerActivity extends FtcRobotControllerActivity {

    public static final String TAG = "SimulatedRC";

    /** The robot this activity started, and is responsible for shutting down. */
    private SimulatedRobotStart robotStart;

    /** The simulated robot every session in this build runs against. */
    protected SimulatedRobot simulatedRobot() {
        return new VerityRobot();
    }

    @Override
    public void onServiceBind(final FtcRobotControllerService service) {
        RobotLog.ii(TAG, "binding robot controller service with simulated hardware");

        // Before any OpMode can build a VisionPortal. From here on, every portal an unmodified
        // OpMode creates gets a camera that renders the simulated field instead of opening USB.
        SyntheticCameras.install();

        controllerService = service;
        updateUI.setControllerService(controllerService);

        updateUiAndRequestSimulatedRobotSetup();

        programmingModeManager.setState(new FtcRobotControllerServiceState() {
            @NonNull
            @Override
            public WebServer getWebServer() {
                return service.getWebServer();
            }

            @Nullable
            @Override
            public OnBotJavaHelper getOnBotJavaHelper() {
                return service.getOnBotJavaHelper();
            }

            @Override
            public EventLoopManager getEventLoopManager() {
                return service.getRobot().eventLoopManager;
            }
        });

        AnnotatedHooksClassFilter.getInstance().callWebHandlerRegistrarMethods(
                this, service.getWebServer().getWebHandlerManager());
    }

    private void updateUiAndRequestSimulatedRobotSetup() {
        if (controllerService == null) {
            return;
        }
        callback.networkConnectionUpdate(controllerService.getNetworkConnectionStatus());
        callback.updateRobotStatus(controllerService.getRobotStatus());

        // No configuration file is read: the simulated robot's device list is the configuration.
        SimulatedHardwareFactory hardwareFactory =
                new SimulatedHardwareFactory(context, simulatedRobot());

        OpModeRegister userOpModeRegister = createOpModeRegister();
        controllerService.setCallback(callback);

        // Not controllerService.setupRobot(): that waits for a Wi-Fi Direct network, which an
        // emulator does not have, so the event loop would never start. See SimulatedRobotStart.
        robotStart = new SimulatedRobotStart(this, controllerService);
        try {
            robotStart.start(hardwareFactory, userOpModeRegister, callback);
        } catch (RobotCoreException e) {
            RobotLog.ee(TAG, e, "could not start the simulated robot");
            return;
        }
        eventLoop = robotStart.eventLoop();

        AnnotatedHooksClassFilter.getInstance().callOnCreateEventLoopMethods(this, eventLoop);
    }

    @Override
    protected void onDestroy() {
        // This activity owns the robot now, so it has to put it down: a leaked event loop would
        // keep running OpModes against a hardware map nothing else can reach.
        if (robotStart != null) {
            robotStart.shutdown();
            robotStart = null;
        }
        super.onDestroy();
    }
}
