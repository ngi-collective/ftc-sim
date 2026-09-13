package org.ngicollective.testframework.dashboard;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Named robot layouts on disk, one pretty-printed JSON file each.
 *
 * <p>The dashboard writes these into the team's own source tree rather than into browser storage,
 * because a layout describes the robot &mdash; where each motor sits and what it drives &mdash; and
 * that belongs in version control next to the OpModes, not in one laptop's browser profile.</p>
 *
 * <p>The contents are whatever the browser sends. This class deliberately knows nothing about the
 * shape of a layout: the schema belongs to the UI that draws it, and pinning a copy of it here
 * would mean editing Java to add a field to a picture.</p>
 */
public final class LayoutStore {

    /** Names that are safe as a file name and readable as a git path. */
    private static final Pattern LEGAL_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9 _-]{0,39}");

    private static final String EXTENSION = ".json";

    private final Path directory;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().serializeNulls().create();

    public LayoutStore(Path directory) {
        this.directory = directory.toAbsolutePath().normalize();
    }

    /** Where the files live, for the UI to tell the user what to commit. */
    public Path directory() {
        return directory;
    }

    /** Saved layout names, alphabetically. */
    public List<String> list() {
        if (!Files.isDirectory(directory)) {
            return Collections.emptyList();
        }
        List<String> names = new ArrayList<>();
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(directory, "*" + EXTENSION)) {
            for (Path entry : entries) {
                String fileName = entry.getFileName().toString();
                names.add(fileName.substring(0, fileName.length() - EXTENSION.length()));
            }
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + directory, e);
        }
        Collections.sort(names);
        return names;
    }

    /** The layout saved under this name. */
    public JsonElement read(String name) {
        Path file = fileFor(name);
        if (!Files.isRegularFile(file)) {
            throw new IllegalArgumentException("no layout named \"" + name + "\"");
        }
        try {
            String contents = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
            // Gson here is whatever RobotCore drags in (2.8.0), which has no static parseString.
            return new JsonParser().parse(contents);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + file, e);
        }
    }

    /**
     * Writes a layout, replacing any file of the same name.
     *
     * @return the file written, so the UI can name the path a user has to commit
     */
    public Path save(String name, JsonElement layout) {
        if (layout == null || !layout.isJsonObject()) {
            throw new IllegalArgumentException("a layout must be a JSON object");
        }
        Path file = fileFor(name);
        try {
            Files.createDirectories(directory);
            // Trailing newline: these files are meant to be read and diffed in a pull request.
            Files.write(file, (gson.toJson(layout) + "\n").getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException("cannot write " + file, e);
        }
        return file;
    }

    public void delete(String name) {
        try {
            if (!Files.deleteIfExists(fileFor(name))) {
                throw new IllegalArgumentException("no layout named \"" + name + "\"");
            }
        } catch (IOException e) {
            throw new UncheckedIOException("cannot delete " + fileFor(name), e);
        }
    }

    /**
     * The file a name maps to.
     *
     * <p>Names are checked against a whitelist rather than escaped: this is a socket anyone on the
     * network can reach if the server is bound off loopback, and "../../build.gradle" must not be a
     * legal layout name.</p>
     */
    private Path fileFor(String name) {
        if (name == null || !LEGAL_NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("a layout name must be letters, digits, spaces, "
                    + "\"-\" or \"_\" (up to 40 characters); got \"" + name + "\"");
        }
        return directory.resolve(name + EXTENSION);
    }
}
