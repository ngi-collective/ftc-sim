package org.ngicollective.testframework.dashboard;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * The captured frames in {@code protocol-fixtures/}, as JSON trees.
 *
 * <p>The directory is found by walking up from the working directory rather than by a relative
 * path, because Gradle does not promise what a test's working directory is &mdash; it is the
 * module for an Android unit test task and the root for some invocations, and a hard-coded
 * {@code ../protocol-fixtures} silently becomes "fixture not found" in whichever of those two the
 * author did not try.</p>
 */
final class ProtocolFixtures {

    private static final String DIRECTORY_NAME = "protocol-fixtures";

    private ProtocolFixtures() {
    }

    /** The captured frame in {@code <name>.json}, whole envelope included. */
    static JsonObject frame(String name) {
        Path file = directory().resolve(name + ".json");
        String contents;
        try {
            contents = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read fixture " + file, e);
        }
        return new JsonParser().parse(contents).getAsJsonObject();
    }

    /** Just the {@code payload} of a captured frame. */
    static JsonObject payload(String name) {
        return frame(name).getAsJsonObject("payload");
    }

    static Path directory() {
        File from = new File(".").getAbsoluteFile();
        for (File candidate = from; candidate != null; candidate = candidate.getParentFile()) {
            File fixtures = new File(candidate, DIRECTORY_NAME);
            if (fixtures.isDirectory()) {
                return fixtures.toPath();
            }
        }
        fail("no " + DIRECTORY_NAME + " directory in " + from + " or any directory above it");
        throw new AssertionError("unreachable");
    }
}
