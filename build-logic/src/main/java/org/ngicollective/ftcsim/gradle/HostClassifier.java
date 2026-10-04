package org.ngicollective.ftcsim.gradle;

import java.util.Locale;

/**
 * Names this machine the way the vision-natives artifact's classifiers do: {@code osx-aarch64},
 * {@code linux-x86_64}, {@code windows-x86_64} and so on.
 *
 * <p>Its own class, with nothing from AGP in it, so a test can ask without loading the plugin.</p>
 */
final class HostClassifier {

    private HostClassifier() {
    }

    static String of(String osName, String osArch) {
        String os = osName.toLowerCase(Locale.ROOT);
        String arch = osArch.toLowerCase(Locale.ROOT);
        String osPart = os.contains("mac") ? "osx" : os.contains("win") ? "windows" : "linux";
        String archPart = (arch.equals("aarch64") || arch.equals("arm64")) ? "aarch64" : "x86_64";
        return osPart + "-" + archPart;
    }

    static String current() {
        return of(System.getProperty("os.name"), System.getProperty("os.arch"));
    }
}
