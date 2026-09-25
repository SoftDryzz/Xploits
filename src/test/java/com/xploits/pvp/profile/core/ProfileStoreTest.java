package com.xploits.pvp.profile.core;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.xploits.pvp.core.ManagedModule;
import com.xploits.pvp.core.ManagedModules;
import com.xploits.testing.TempFolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** profiles.json round-trips a book and never lets a bad one through (design §2, ProfileStore). */
class ProfileStoreTest {
    private static final Pattern FORBIDDEN_KEY = Pattern.compile("(?i)^(x|y|z|pos|position|coord|coords|dimension)$");
    private static final Set<String> ALL = ManagedModules.ALL.stream().map(ManagedModule::name).collect(Collectors.toUnmodifiableSet());

    private Path dir;
    private Path file;

    @BeforeEach
    void createTempFolder() throws IOException {
        dir = TempFolder.create();
        file = dir.resolve("profiles.json");
    }

    @AfterEach
    void deleteTempFolder() {
        TempFolder.delete(dir);
    }

    @Test
    void roundTripKeepsOrderValuesAndActiveName() {
        ProfileStore store = new ProfileStore(file);
        ProfileBook book = applied(ProfileBook.defaults().save("my-style", 20, 3, 10.5, Set.of(ManagedModules.CRYSTAL_AURA.name())));
        book = applied(book.use("my-style"));

        assertInstanceOf(ProfileStore.Saved.class, store.save(book));

        ProfileStore.Result loaded = new ProfileStore(file).load();
        assertTrue(loaded.warning().isEmpty());
        assertEquals(book.activeName(), loaded.book().activeName());
        assertEquals(book.profiles(), loaded.book().profiles());
    }

    @Test
    void aMissingFileLoadsAsTheBuiltInsUnlocked() {
        ProfileStore store = new ProfileStore(file);
        ProfileStore.Result result = store.load();

        assertTrue(result.warning().isEmpty());
        assertEquals(ProfileBook.defaults().profiles(), result.book().profiles());
        assertFalse(store.locked());
    }

    @Test
    void aCorruptFileLoadsAsTheBuiltInsLockedAndIsLeftUntouched() throws IOException {
        Files.writeString(file, "{not json");

        ProfileStore store = new ProfileStore(file);
        ProfileStore.Result result = store.load();

        assertTrue(result.warning().isPresent());
        assertEquals(ProfileText.PROFILE_FILE_CORRUPT, result.warning().get().key());
        assertEquals(ProfileBook.defaults().profiles(), result.book().profiles());
        assertTrue(store.locked());
        assertEquals("{not json", Files.readString(file));
    }

    @Test
    void anEmptyFileIsTreatedAsCorrupt() throws IOException {
        Files.writeString(file, "");

        ProfileStore store = new ProfileStore(file);
        store.load();

        assertTrue(store.locked());
        assertEquals("", Files.readString(file));
    }

    @Test
    void anUnsupportedSchemaIsTreatedAsCorruptAndLeftUntouched() throws IOException {
        String json = "{\"schema\": 2, \"active\": \"balanced\", \"profiles\": []}";
        Files.writeString(file, json);

        ProfileStore store = new ProfileStore(file);
        store.load();

        assertTrue(store.locked());
        assertEquals(json, Files.readString(file));
    }

    @Test
    void aFileMissingABuiltInProfileIsTreatedAsCorrupt() throws IOException {
        // valid JSON, valid schema, but structurally invalid: ProfileBook.of refuses it (missing "defensive")
        String json = "{\"schema\": 1, \"active\": \"balanced\", \"profiles\": ["
            + "{\"name\": \"balanced\", \"targetRange\": 16, \"approachDistance\": 6, \"threatMargin\": 12.0, \"allowed\": []},"
            + "{\"name\": \"aggressive\", \"targetRange\": 24, \"approachDistance\": 4, \"threatMargin\": 8.0, \"allowed\": []}"
            + "]}";
        Files.writeString(file, json);

        ProfileStore store = new ProfileStore(file);
        store.load();

        assertTrue(store.locked());
    }

    @Test
    void savingWhileLockedIsRefusedAndTheCorruptFileStaysUntouched() throws IOException {
        Files.writeString(file, "{not json");
        ProfileStore store = new ProfileStore(file);
        store.load();
        assertTrue(store.locked());

        ProfileStore.SaveResult result = store.save(ProfileBook.defaults());

        ProfileStore.SaveRefused refused = assertInstanceOf(ProfileStore.SaveRefused.class, result);
        assertEquals(ProfileText.PROFILE_FILE_LOCKED, refused.reason().key());
        assertEquals("{not json", Files.readString(file));
    }

    @Test
    void resetFileMovesTheCorruptFileAsideAndUnlocksTheStore() throws IOException {
        Files.writeString(file, "{not json");
        ProfileStore store = new ProfileStore(file, ProfileStoreTest::moveIntoPlace, () -> 1_700_000_000_000L);
        store.load();
        assertTrue(store.locked());

        store.resetFile();

        assertFalse(store.locked());
        assertFalse(Files.exists(file));
        Path backup = dir.resolve("profiles.json.corrupt-1700000000000");
        assertEquals("{not json", Files.readString(backup));
    }

    @Test
    void savingWorksAgainAfterResetFile() throws IOException {
        Files.writeString(file, "{not json");
        ProfileStore store = new ProfileStore(file, ProfileStoreTest::moveIntoPlace, () -> 1_700_000_000_001L);
        store.load();

        store.resetFile();
        ProfileStore.SaveResult result = store.save(ProfileBook.defaults());

        assertInstanceOf(ProfileStore.Saved.class, result);
        assertEquals(ProfileBook.defaults().profiles(), new ProfileStore(file).load().book().profiles());
    }

    @Test
    void resetFileWithNothingToMoveIsAHarmlessNoOp() throws IOException {
        ProfileStore store = new ProfileStore(file);
        store.resetFile();
        assertFalse(store.locked());
        assertFalse(Files.exists(file));
    }

    @Test
    void aFailedMoveLeavesNoTemporaryFileBehindAndReportsSaveFailed() {
        IOException refused = new IOException("the move was refused");
        ProfileStore store = new ProfileStore(file, (from, to) -> {
            assertTrue(Files.exists(from), "the .tmp was written before the move");
            throw refused;
        }, System::currentTimeMillis);

        ProfileStore.SaveResult result = store.save(ProfileBook.defaults());

        ProfileStore.SaveRefused saveRefused = assertInstanceOf(ProfileStore.SaveRefused.class, result);
        assertEquals(ProfileText.PROFILE_SAVE_FAILED, saveRefused.reason().key());
        assertFalse(Files.exists(dir.resolve("profiles.json.tmp")));
    }

    @Test
    void noJsonKeyEverNamesAPosition() throws IOException {
        ProfileBook book = applied(ProfileBook.defaults().save("my-style", 20, 3, 10.5, ALL));
        ProfileStore store = new ProfileStore(file);
        assertInstanceOf(ProfileStore.Saved.class, store.save(book));

        JsonElement root = JsonParser.parseString(Files.readString(file));
        Set<String> keys = new HashSet<>();
        collectKeys(root, keys);

        for (String key : keys) {
            assertFalse(FORBIDDEN_KEY.matcher(key).matches(), "forbidden JSON key found: " + key);
        }
    }

    private static void collectKeys(JsonElement element, Set<String> keys) {
        if (element.isJsonObject()) {
            element.getAsJsonObject().entrySet().forEach(entry -> {
                keys.add(entry.getKey());
                collectKeys(entry.getValue(), keys);
            });
        } else if (element.isJsonArray()) {
            element.getAsJsonArray().forEach(child -> collectKeys(child, keys));
        }
    }

    private static void moveIntoPlace(Path from, Path to) throws IOException {
        Files.move(from, to, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
    }

    private static ProfileBook applied(ProfileBook.Outcome outcome) {
        return assertInstanceOf(ProfileBook.Applied.class, outcome).book();
    }
}
