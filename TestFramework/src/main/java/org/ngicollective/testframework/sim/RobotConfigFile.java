package org.ngicollective.testframework.sim;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Writes a number back into a robot description without disturbing the file around it.
 *
 * <p>Surgical on purpose. {@code TeamCode/robot-config/verity.json} is hand-formatted &mdash;
 * one-line blocks for the small things, aligned columns in the motor table &mdash; and the obvious
 * implementation, parse with Gson and re-serialise, throws all of that away: the whole document
 * comes back pretty-printed, and a driver who nudged one slider gets a two-hundred-line diff to
 * review. That already happened once to a robot layout file and is a standing annoyance. So this
 * locates the {@code "camera"} block, replaces the six values inside it, and leaves every other
 * byte of the file exactly as it found it.</p>
 *
 * <p>Every write is verified by reading the file back through {@link RobotConfig#load}. A text
 * edit that produced something the loader cannot read, or that reads back as a different mount,
 * restores the original bytes and throws: the team's configuration file is what the robot drives
 * from, and half-writing it is worse than refusing.</p>
 */
public final class RobotConfigFile {

    /**
     * The mount's keys, in the order {@link CameraMount} takes them, which is also the order the
     * configuration file and the wire protocol write them in.
     */
    private static final String[] MOUNT_KEYS = {
            "forwardMetres", "leftMetres", "heightMetres",
            "yawDegrees", "pitchDegrees", "rollDegrees",
    };

    private RobotConfigFile() {
    }

    /**
     * Puts {@code mount} into the {@code "camera"} block of {@code file}.
     *
     * <p>A key the block does not yet have is added before its closing brace, indented like its
     * siblings: {@code heightMetres} is optional in the schema, so a file that inherited the
     * chassis deck height has to gain the key rather than silently keep inheriting.</p>
     *
     * @throws IllegalArgumentException if the file has no {@code "camera"} block, or has one whose
     *     mount values are not numbers, since there is then nothing this can safely rewrite
     * @throws IllegalStateException if the rewritten file does not read back as {@code mount}
     */
    public static void writeCameraMount(Path file, CameraMount mount) {
        String original = read(file);
        write(file, withCameraMount(original, mount, file));

        CameraMount reread;
        try {
            reread = RobotConfig.load(file).camera().mount();
        } catch (RuntimeException e) {
            write(file, original);
            throw new IllegalStateException("writing the camera mount into " + file + " produced a"
                    + " file that will not load, so the file has been put back as it was", e);
        }
        if (!reread.equals(mount)) {
            write(file, original);
            throw new IllegalStateException("writing the camera mount into " + file + " produced "
                    + reread + " instead of " + mount
                    + ", so the file has been put back as it was");
        }
    }

    private static String withCameraMount(String text, CameraMount mount, Path file) {
        int blockStart = cameraBlockStart(text, file);
        int blockEnd = matchingBrace(text, blockStart, file);
        double[] values = {
                mount.forwardMetres(), mount.leftMetres(), mount.heightMetres(),
                mount.yawDegrees(), mount.pitchDegrees(), mount.rollDegrees(),
        };

        List<Edit> edits = new ArrayList<>();
        List<String> added = new ArrayList<>();
        for (int i = 0; i < MOUNT_KEYS.length; i++) {
            String written = Double.toString(values[i]);
            int[] span = valueSpan(text, blockStart, blockEnd, MOUNT_KEYS[i], file);
            if (span == null) {
                added.add("\"" + MOUNT_KEYS[i] + "\": " + written);
            } else {
                edits.add(new Edit(span[0], span[1], written));
            }
        }
        if (!added.isEmpty()) {
            edits.add(insertion(text, blockStart, blockEnd, added));
        }
        return applied(text, edits);
    }

    /**
     * The new entries, as one edit at the end of the block.
     *
     * <p>At the end rather than in schema order, so that the keys the block already had keep the
     * order and the formatting the person who wrote them chose.</p>
     */
    private static Edit insertion(String text, int blockStart, int blockEnd, List<String> added) {
        int at = lastEntryEnd(text, blockStart, blockEnd);
        boolean hasSiblings = at > blockStart + 1;
        // Whether the block is spread over lines or written compactly on one, which decides
        // whether a new entry gets its own line or a space.
        int newline = text.indexOf('\n', at);
        String separator = newline >= 0 && newline < blockEnd
                ? "\n" + entryIndent(text, blockStart, blockEnd)
                : " ";

        StringBuilder insert = new StringBuilder();
        for (String entry : added) {
            if (hasSiblings || insert.length() > 0) {
                insert.append(',');
            }
            insert.append(separator).append(entry);
        }
        return new Edit(at, at, insert.toString());
    }

    private static String applied(String text, List<Edit> edits) {
        Collections.sort(edits, (left, right) -> Integer.compare(left.start, right.start));
        StringBuilder out = new StringBuilder(text.length() + 64);
        int at = 0;
        for (Edit edit : edits) {
            out.append(text, at, edit.start).append(edit.replacement);
            at = edit.end;
        }
        return out.append(text, at, text.length()).toString();
    }

    /** The index of the brace that opens the document's top-level {@code "camera"} block. */
    private static int cameraBlockStart(String text, Path file) {
        int depth = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '"') {
                int close = endOfString(text, i, file);
                if (depth == 1 && "camera".equals(text.substring(i + 1, close))) {
                    int colon = skipWhitespace(text, close + 1);
                    if (colon < text.length() && text.charAt(colon) == ':') {
                        int brace = skipWhitespace(text, colon + 1);
                        if (brace < text.length() && text.charAt(brace) == '{') {
                            return brace;
                        }
                    }
                }
                i = close;
            } else if (c == '{' || c == '[') {
                depth++;
            } else if (c == '}' || c == ']') {
                depth--;
            }
        }
        throw new IllegalArgumentException("there is no \"camera\" block in " + file
                + ", so there is nowhere to write a camera mount");
    }

    /**
     * Where {@code key}'s number sits inside the block, as {@code {start, end}}, or null when the
     * block does not declare it.
     */
    private static int[] valueSpan(String text, int blockStart, int blockEnd, String key,
                                   Path file) {
        int depth = 0;
        for (int i = blockStart; i < blockEnd; i++) {
            char c = text.charAt(i);
            if (c == '"') {
                int close = endOfString(text, i, file);
                if (depth == 1 && key.equals(text.substring(i + 1, close))) {
                    int colon = skipWhitespace(text, close + 1);
                    if (colon < blockEnd && text.charAt(colon) == ':') {
                        return numberSpan(text, skipWhitespace(text, colon + 1), blockEnd, key,
                                file);
                    }
                }
                i = close;
            } else if (c == '{' || c == '[') {
                depth++;
            } else if (c == '}' || c == ']') {
                depth--;
            }
        }
        return null;
    }

    private static int[] numberSpan(String text, int start, int blockEnd, String key, Path file) {
        int end = start;
        while (end < blockEnd && isNumberCharacter(text.charAt(end))) {
            end++;
        }
        if (end == start) {
            throw new IllegalArgumentException("\"" + key + "\" in the \"camera\" block of " + file
                    + " is not a number, so this cannot rewrite it");
        }
        return new int[] {start, end};
    }

    /** One past the last non-whitespace character inside the block. */
    private static int lastEntryEnd(String text, int blockStart, int blockEnd) {
        int at = blockEnd;
        while (at > blockStart + 1 && Character.isWhitespace(text.charAt(at - 1))) {
            at--;
        }
        return at;
    }

    /** The leading whitespace of the first entry that starts a line of its own. */
    private static String entryIndent(String text, int blockStart, int blockEnd) {
        for (int line = nextLine(text, blockStart); line > 0 && line < blockEnd;
                line = nextLine(text, line)) {
            int at = skipIndent(text, line, blockEnd);
            if (at < blockEnd && text.charAt(at) == '"') {
                return text.substring(line, at);
            }
        }
        // Nothing to copy: match the closing brace's own indentation, two deeper, which is what
        // every nested block in these files does.
        int braceLine = text.lastIndexOf('\n', blockEnd) + 1;
        return text.substring(braceLine, skipIndent(text, braceLine, blockEnd)) + "  ";
    }

    private static int nextLine(String text, int from) {
        int newline = text.indexOf('\n', from);
        return newline < 0 ? -1 : newline + 1;
    }

    private static int skipIndent(String text, int from, int limit) {
        int at = from;
        while (at < limit && (text.charAt(at) == ' ' || text.charAt(at) == '\t')) {
            at++;
        }
        return at;
    }

    private static int matchingBrace(String text, int openBrace, Path file) {
        int depth = 0;
        for (int i = openBrace; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '"') {
                i = endOfString(text, i, file);
            } else if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        throw new IllegalArgumentException(
                "the \"camera\" block in " + file + " is never closed, so the file is not JSON");
    }

    /** The index of the quote that closes the string opened at {@code openQuote}. */
    private static int endOfString(String text, int openQuote, Path file) {
        for (int i = openQuote + 1; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\\') {
                i++;
            } else if (c == '"') {
                return i;
            }
        }
        throw new IllegalArgumentException(
                file + " ends in the middle of a string, so the file is not JSON");
    }

    private static int skipWhitespace(String text, int from) {
        int at = from;
        while (at < text.length() && Character.isWhitespace(text.charAt(at))) {
            at++;
        }
        return at;
    }

    private static boolean isNumberCharacter(char c) {
        return c == '-' || c == '+' || c == '.' || c == 'e' || c == 'E'
                || (c >= '0' && c <= '9');
    }

    private static String read(Path file) {
        try {
            return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + file, e);
        }
    }

    private static void write(Path file, String text) {
        try {
            Files.write(file, text.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException("cannot write " + file, e);
        }
    }

    /** One replacement in the file's text; {@code start == end} inserts. */
    private static final class Edit {

        final int start;
        final int end;
        final String replacement;

        Edit(int start, int end, String replacement) {
            this.start = start;
            this.end = end;
            this.replacement = replacement;
        }
    }
}
