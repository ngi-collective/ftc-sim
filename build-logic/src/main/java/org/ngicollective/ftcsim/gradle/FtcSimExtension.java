package org.ngicollective.ftcsim.gradle;

import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.provider.Property;

/**
 * {@code ftcSim { ... }} in a team's TeamCode build.
 *
 * <pre>
 * ftcSim {
 *     version = '12.0.0'        // the simulator release; its major version is the FTC SDK's
 *     season = 'biobuzz'        // the game this robot practises
 *     seasonVersion = '1.0.0'   // that season's own release (ADR 0009)
 *     robotConfigDir = file('robot-config')  // the default
 *     scenariosDir = file('scenarios')       // the default
 * }
 * </pre>
 *
 * <p>In the simulator's own repository its modules are projects of the build, so only
 * {@code season} is read there.</p>
 */
public abstract class FtcSimExtension {

    /** The simulator release, for a build that resolves it from Maven Central. */
    public abstract Property<String> getVersion();

    /**
     * The game, by name: {@code biobuzz}. Required, because a default would quietly be last
     * year's game every September.
     */
    public abstract Property<String> getSeason();

    /** The season's own release, for a build that resolves it from Maven Central. */
    public abstract Property<String> getSeasonVersion();

    /** The robot's physics files, staged into the simulated APK. */
    public abstract DirectoryProperty getRobotConfigDir();

    /** The scenario files, staged into the simulated APK beside the robot's. */
    public abstract DirectoryProperty getScenariosDir();
}
