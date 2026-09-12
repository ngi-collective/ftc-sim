package org.firstinspires.ftc.teamcode.simulated;

import org.firstinspires.ftc.robotcontroller.internal.FtcRobotControllerActivity;
import org.firstinspires.ftc.robotcontroller.internal.PermissionValidatorWrapper;

/**
 * The simulated build's launcher: the SDK's own permission-request screen, sending the user to
 * {@link SimulatedRobotControllerActivity} instead of the stock one.
 */
public class SimulatedPermissionValidatorWrapper extends PermissionValidatorWrapper {

    @Override
    protected Class onStartApplication() {
        FtcRobotControllerActivity.setPermissionsValidated();
        return SimulatedRobotControllerActivity.class;
    }
}
