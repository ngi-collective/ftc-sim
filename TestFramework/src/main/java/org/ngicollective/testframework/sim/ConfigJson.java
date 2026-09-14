package org.ngicollective.testframework.sim;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * A JSON object being read as configuration, with the document it came from &mdash; a file on disk
 * or a resource packaged into an APK &mdash; and the path that reached it, so every complaint can
 * name both.
 *
 * <p>Every accessor here is a {@code require}: a configuration file that is missing a number, or
 * has one written as a string, or has a wheel radius of zero, fails at load with a message naming
 * the file and the field. The alternative &mdash; defaulting a missing field to zero &mdash; turns
 * into a divide-by-zero in the drive model and a NaN pose several hundred ticks later, by which
 * point nothing points at the typo that caused it.</p>
 */
final class ConfigJson {

    // Gson rather than JsonParser: fromJson(String, Class) has been stable across every version the
    // SDK AARs have dragged in, while JsonParser's instance parse() is deprecated in the newer ones.
    private static final Gson GSON = new Gson();

    /** Where this came from, printable: an absolute file path, or a classpath resource URL. */
    private final String source;

    private final JsonObject object;
    private final String path;

    private ConfigJson(String source, JsonObject object, String path) {
        this.source = source;
        this.object = object;
        this.path = path;
    }

    /**
     * Reads {@code file} as a JSON object and checks its schema version.
     *
     * @param supportedVersion the only {@code version} this code knows how to read; a file from the
     *                         future is refused rather than half-understood
     */
    static ConfigJson read(Path file, int supportedVersion) {
        Path absolute = file.toAbsolutePath().normalize();
        if (!Files.isRegularFile(absolute)) {
            throw new IllegalArgumentException("no configuration file at " + absolute);
        }
        String text;
        try {
            text = new String(Files.readAllBytes(absolute), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + absolute, e);
        }
        return parse(absolute.toString(), text, supportedVersion);
    }

    /**
     * The same, from a classpath resource: inside an APK there is no file to read, and the config
     * travels as a packaged resource instead. Same bytes, same parser, same complaints.
     */
    static ConfigJson read(URL resource, int supportedVersion) {
        String text;
        try (InputStream stream = resource.openStream()) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            for (int read = stream.read(buffer); read >= 0; read = stream.read(buffer)) {
                bytes.write(buffer, 0, read);
            }
            text = new String(bytes.toByteArray(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + resource, e);
        }
        return parse(resource.toString(), text, supportedVersion);
    }

    private static ConfigJson parse(String source, String text, int supportedVersion) {
        JsonObject root;
        try {
            root = GSON.fromJson(text, JsonObject.class);
        } catch (JsonParseException e) {
            throw new IllegalArgumentException(source + " is not valid JSON: " + e.getMessage(), e);
        }
        if (root == null) {
            throw new IllegalArgumentException(source + " is empty; expected a JSON object");
        }
        ConfigJson config = new ConfigJson(source, root, "");
        double version = config.number("version");
        if (version != supportedVersion) {
            throw new IllegalArgumentException(source + ": \"version\" is " + version
                    + ", but this build only reads version " + supportedVersion);
        }
        return config;
    }

    /** What to name in a message about this document, whether it was a file or a resource. */
    String source() {
        return source;
    }

    /** The nested object under {@code name}. */
    ConfigJson child(String name) {
        JsonElement element = require(name);
        if (!element.isJsonObject()) {
            throw new IllegalArgumentException(
                    source + ": \"" + qualify(name) + "\" must be a JSON object, got " + element);
        }
        return new ConfigJson(source, element.getAsJsonObject(), qualify(name));
    }

    /**
     * The keys of this object, in file order &mdash; for maps keyed by a name we do not know.
     *
     * <p>Built from {@code entrySet()} rather than returned from {@code keySet()}: which Gson the
     * SDK AARs resolve to is not ours to choose, and {@code keySet()} only exists on the newer
     * ones.</p>
     */
    Set<String> names() {
        Set<String> names = new LinkedHashSet<>();
        for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
            names.add(entry.getKey());
        }
        return names;
    }

    String string(String name) {
        JsonElement element = require(name);
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException(
                    source + ": \"" + qualify(name) + "\" must be a string, got " + element);
        }
        String value = element.getAsString();
        if (value.trim().isEmpty()) {
            throw new IllegalArgumentException(source + ": \"" + qualify(name) + "\" is empty");
        }
        return value;
    }

    /** A true/false fact. Quoted "true" is rejected: it is a typo, not a value. */
    boolean bool(String name) {
        JsonElement element = require(name);
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isBoolean()) {
            throw new IllegalArgumentException(
                    source + ": \"" + qualify(name) + "\" must be true or false, got " + element);
        }
        return element.getAsBoolean();
    }

    double number(String name) {
        JsonElement element = require(name);
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException(
                    source + ": \"" + qualify(name) + "\" must be a number, got " + element);
        }
        double value = element.getAsDouble();
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            throw new IllegalArgumentException(
                    source + ": \"" + qualify(name) + "\" must be finite, got " + value);
        }
        return value;
    }

    /** A measurement that physics divides by or draws with, so zero is as wrong as absent. */
    double positive(String name) {
        double value = number(name);
        if (value <= 0.0) {
            throw new IllegalArgumentException(
                    source + ": \"" + qualify(name) + "\" must be greater than zero, got " + value);
        }
        return value;
    }

    /** A ratio in (0, 1]: an efficiency above 1 would make the model produce free energy. */
    double fraction(String name) {
        double value = number(name);
        if (value <= 0.0 || value > 1.0) {
            throw new IllegalArgumentException(source + ": \"" + qualify(name)
                    + "\" must be greater than 0 and at most 1, got " + value);
        }
        return value;
    }

    private JsonElement require(String name) {
        JsonElement element = object.get(name);
        if (element == null || element.isJsonNull()) {
            throw new IllegalArgumentException(
                    source + ": missing required field \"" + qualify(name) + "\"");
        }
        return element;
    }

    private String qualify(String name) {
        return path.isEmpty() ? name : path + "." + name;
    }
}
