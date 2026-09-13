package org.ngicollective.testframework.dashboard;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Layouts on disk: what the browser saves has to come back, and has to stay inside its directory. */
class LayoutStoreTest {

    @TempDir
    Path directory;

    private static JsonObject layout(String motor, double x) {
        JsonObject placement = new JsonObject();
        placement.add("x", new JsonPrimitive(x));
        JsonObject devices = new JsonObject();
        devices.add(motor, placement);
        JsonObject file = new JsonObject();
        file.addProperty("version", 1);
        file.add("devices", devices);
        return file;
    }

    @Test
    void savesAndReadsBackTheLayoutItWasGiven() {
        LayoutStore store = new LayoutStore(directory);

        store.save("Verity comp", layout("FL", -0.22));

        assertEquals(Arrays.asList("Verity comp"), store.list());
        assertEquals(layout("FL", -0.22), store.read("Verity comp"));
    }

    @Test
    void savingTheSameNameReplacesTheFileRatherThanAccumulating() {
        LayoutStore store = new LayoutStore(directory);
        store.save("Verity", layout("FL", -0.22));

        store.save("Verity", layout("FL", -0.30));

        assertEquals(1, store.list().size());
        assertEquals(layout("FL", -0.30), store.read("Verity"));
    }

    /** These files are read and reviewed in pull requests, not just parsed. */
    @Test
    void writesReadableJsonWithATrailingNewline() throws Exception {
        LayoutStore store = new LayoutStore(directory);

        Path file = store.save("Verity", layout("FL", -0.22));

        String contents = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        assertTrue(contents.endsWith("}\n"), "no trailing newline: " + contents);
        assertTrue(contents.contains("\n  \"devices\""), "not pretty-printed: " + contents);
        assertEquals(layout("FL", -0.22), new JsonParser().parse(contents));
    }

    @Test
    void listsNothingBeforeAnythingHasBeenSaved() {
        assertTrue(new LayoutStore(directory.resolve("not-created-yet")).list().isEmpty());
    }

    @Test
    void deletingRemovesTheFileAndReportsAnUnknownName() {
        LayoutStore store = new LayoutStore(directory);
        store.save("Verity", layout("FL", -0.22));

        store.delete("Verity");

        assertTrue(store.list().isEmpty());
        assertFalse(Files.exists(directory.resolve("Verity.json")));
        assertThrows(IllegalArgumentException.class, () -> store.delete("Verity"));
    }

    @Test
    void loadingAnUnknownLayoutIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new LayoutStore(directory).read("nope"));
    }

    /**
     * The socket can be bound off loopback so a second laptop can drive the robot, which makes a
     * layout name attacker-controlled input. It must never address a file outside its directory.
     */
    @Test
    void refusesNamesThatWouldEscapeTheLayoutDirectory() {
        LayoutStore store = new LayoutStore(directory);

        for (String name : Arrays.asList("../escape", "sub/dir", "..", "", "a\u0000b", null)) {
            assertThrows(IllegalArgumentException.class, () -> store.save(name, layout("FL", 0)),
                    "accepted \"" + name + "\"");
        }
        assertTrue(store.list().isEmpty());
    }

    @Test
    void refusesToSaveSomethingThatIsNotALayoutObject() {
        LayoutStore store = new LayoutStore(directory);

        assertThrows(IllegalArgumentException.class, () -> store.save("Verity", null));
        assertThrows(IllegalArgumentException.class,
                () -> store.save("Verity", new JsonPrimitive("not an object")));
    }
}
