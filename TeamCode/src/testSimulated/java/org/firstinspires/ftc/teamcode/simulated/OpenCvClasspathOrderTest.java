package org.firstinspires.ftc.teamcode.simulated;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.opencv.core.Core;

/**
 * Guards the one ordering the ftc-sim plugin depends on.
 *
 * <p>Two copies of {@code org.opencv} reach this classpath: the SDK's Android build and openpnp's
 * desktop build. Off a device the natives that load are openpnp's, so its Java classes have to be
 * the ones found first. If a dependency declared ahead of the plugin's ever puts the SDK's copy
 * first, every vision test fails at its first {@code Mat}, far from the cause; this fails here
 * instead, naming it.</p>
 */
class OpenCvClasspathOrderTest {

    @Test
    void openCvClassesComeFromOpenpnp() {
        String source = Core.class.getProtectionDomain().getCodeSource().getLocation().toString();

        assertTrue(source.contains("openpnp"),
                "org.opencv.core.Core was loaded from " + source + ", not openpnp's desktop build;"
                        + " the ftc-sim plugin must declare org.openpnp:opencv before the SDK");
    }
}
