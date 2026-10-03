package org.ngicollective.ftcsim.gradle;

import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.provider.Property;

/**
 * {@code ftcSim { ... }} in a team's TeamCode build.
 *
 * <pre>
 * ftcSim {
 *     season = 'Season-BioBuzz'        // the game this robot practises; required
 *     robotConfigDir = file('robot-config')  // the default
 *     scenariosDir = file('scenarios')       // the default
 *     version = '0.1.0'                // only when the simulator comes from a Maven repository
 * }
 * </pre>
 */
public abstract class FtcSimExtension {

    /**
     * The season module, by its project name: {@code Season-BioBuzz}. Required, because a
     * default would quietly be last year's game every September.
     */
    public abstract Property<String> getSeason();

    /** The robot's physics files, staged into the simulated APK. */
    public abstract DirectoryProperty getRobotConfigDir();

    /** The scenario files, staged into the simulated APK beside the robot's. */
    public abstract DirectoryProperty getScenariosDir();

    /**
     * The simulator's version, for a build that resolves it from a Maven repository. Unused when
     * the simulator's modules are projects in the same build, as they are in the simulator's own
     * repository.
     */
    public abstract Property<String> getVersion();
}
