package com.xploits.shared.core.migration;

import com.xploits.testing.TempFolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConsoleFolderTest {
    Path dir;

    @BeforeEach
    void createTempFolder() throws IOException {
        dir = TempFolder.create();
    }

    @AfterEach
    void deleteTempFolder() {
        TempFolder.delete(dir);
    }

    private Path oldFolder() throws IOException {
        Path old = Files.createDirectories(dir.resolve("consola"));
        Files.createDirectories(old.resolve("historial"));
        Files.writeString(old.resolve("historial").resolve("2026-09-20.log"), "x");
        Files.writeString(old.resolve("vivo.log"), "stream");
        Files.writeString(old.resolve("vivo.1.log"), "stream");
        Files.writeString(old.resolve("consola.lock"), "");
        Files.writeString(old.resolve("consola.pid"), "1");
        Files.writeString(old.resolve("consola.salida"), "usuario");
        Files.writeString(old.resolve("consola-errores.log"), "e");
        Files.writeString(old.resolve("tamano.txt"), "110x46");
        return old;
    }

    @Test
    void movesTheFolderAndRenamesWhatIsInside() throws IOException {
        oldFolder();
        assertEquals(ConsoleFolder.Outcome.MIGRATED, ConsoleFolder.run(dir));
        Path newDir = dir.resolve("console");
        assertFalse(Files.exists(dir.resolve("consola")));
        assertTrue(Files.exists(newDir.resolve("history").resolve("2026-09-20.log")));
        assertTrue(Files.exists(newDir.resolve("console.lock")));
        assertTrue(Files.exists(newDir.resolve("console.pid")));
        assertTrue(Files.exists(newDir.resolve("console.exit")));
        assertTrue(Files.exists(newDir.resolve("console-errors.log")));
        assertTrue(Files.exists(newDir.resolve("size.txt")));
        assertFalse(Files.exists(newDir.resolve("vivo.log")));
        assertFalse(Files.exists(newDir.resolve("vivo.1.log")));
    }

    @Test
    void nothingToDoWithoutTheOldFolder() {
        assertEquals(ConsoleFolder.Outcome.NOTHING, ConsoleFolder.run(dir));
    }

    @Test
    void aPlainFileAtTheNewPathBlocksTheMoveLikeAFolder() throws IOException {
        oldFolder();
        Files.writeString(dir.resolve("console"), "not a folder");
        ConsoleFolder.Outcome o = ConsoleFolder.run(dir, (from, to) -> {
            throw new AssertionError("nothing may be moved: " + from + " -> " + to);
        });
        assertEquals(ConsoleFolder.Outcome.BLOCKED, o);
        assertTrue(Files.exists(dir.resolve("consola").resolve("vivo.log")));
        assertEquals("not a folder", Files.readString(dir.resolve("console")));
    }

    @Test
    void aLonePlainFileAtTheNewPathIsNothingToDo() throws IOException {
        Files.writeString(dir.resolve("console"), "not a folder");
        assertEquals(ConsoleFolder.Outcome.NOTHING, ConsoleFolder.run(dir));
        assertEquals("not a folder", Files.readString(dir.resolve("console")));
    }

    @Test
    void aFailedMoveLeavesEverythingInPlace() throws IOException {
        oldFolder();
        ConsoleFolder.Outcome o = ConsoleFolder.run(dir, (from, to) -> {
            throw new IOException("in use");
        });
        assertEquals(ConsoleFolder.Outcome.BUSY, o);
        assertTrue(Files.exists(dir.resolve("consola").resolve("vivo.log")));
        assertFalse(Files.exists(dir.resolve("console")));
    }

    @Test
    void aFailingInnerRenameYieldsPartialAndLeavesTheFileInPlace() throws IOException {
        oldFolder();
        ConsoleFolder.Outcome o = ConsoleFolder.run(dir, (from, to) -> {
            if (from.getFileName().toString().equals("historial")) throw new IOException("locked");
            Files.move(from, to);
        });
        assertEquals(ConsoleFolder.Outcome.PARTIAL, o);
        Path newDir = dir.resolve("console");
        assertTrue(Files.exists(newDir.resolve("historial").resolve("2026-09-20.log")));
        assertFalse(Files.exists(newDir.resolve("history")));
        assertTrue(Files.exists(newDir.resolve("console.lock")));
    }

    @Test
    void aLeftoverOldNameInsideConsoleIsRenamedOnTheNextRun() throws IOException {
        Path newDir = Files.createDirectories(dir.resolve("console"));
        Files.createDirectories(newDir.resolve("historial"));
        Files.writeString(newDir.resolve("historial").resolve("2026-09-20.log"), "x");
        ConsoleFolder.Outcome o = ConsoleFolder.run(dir);
        assertEquals(ConsoleFolder.Outcome.MIGRATED, o);
        assertTrue(Files.exists(newDir.resolve("history").resolve("2026-09-20.log")));
        assertFalse(Files.exists(newDir.resolve("historial")));
    }

    /** What the console module leaves behind when it starts while {@code consola} is still held. */
    private Path newFolderFromTheModule() throws IOException {
        Path newDir = Files.createDirectories(dir.resolve("console"));
        Files.createDirectories(newDir.resolve("history"));
        Files.writeString(newDir.resolve("history").resolve("2026-09-24.log"), "today");
        Files.writeString(newDir.resolve("console.lock"), "new");
        Files.writeString(newDir.resolve("live.log"), "stream");
        return newDir;
    }

    @Test
    void aBusyFirstStartIsMergedOnTheNextOne() throws IOException {
        oldFolder();
        ConsoleFolder.Outcome first = ConsoleFolder.run(dir, (from, to) -> {
            throw new IOException("in use");
        });
        assertEquals(ConsoleFolder.Outcome.BUSY, first);
        Path newDir = newFolderFromTheModule();

        assertEquals(ConsoleFolder.Outcome.MIGRATED, ConsoleFolder.run(dir));
        assertFalse(Files.exists(dir.resolve("consola")));
        assertEquals("x", Files.readString(newDir.resolve("history").resolve("2026-09-20.log")));
        assertEquals("today", Files.readString(newDir.resolve("history").resolve("2026-09-24.log")));
        assertEquals("new", Files.readString(newDir.resolve("console.lock")));
        // The old window's lock is stale next to the new one: dropped, not kept twice.
        assertFalse(Files.exists(newDir.resolve("console-old.lock")));
        assertEquals("1", Files.readString(newDir.resolve("console.pid")));
        assertEquals("usuario", Files.readString(newDir.resolve("console.exit")));
        assertEquals("e", Files.readString(newDir.resolve("console-errors.log")));
        assertEquals("110x46", Files.readString(newDir.resolve("size.txt")));
        assertEquals("stream", Files.readString(newDir.resolve("live.log")));
        assertFalse(Files.exists(newDir.resolve("vivo.log")));
        assertFalse(Files.exists(newDir.resolve("vivo.1.log")));

        assertEquals(ConsoleFolder.Outcome.NOTHING, ConsoleFolder.run(dir));
    }

    @Test
    void aNameInBothFoldersKeepsBothFiles() throws IOException {
        oldFolder();
        Path newDir = newFolderFromTheModule();
        Files.writeString(newDir.resolve("history").resolve("2026-09-20.log"), "new day");
        Files.writeString(newDir.resolve("console-errors.log"), "new errors");
        Files.writeString(newDir.resolve("size.txt"), "120x40");

        assertEquals(ConsoleFolder.Outcome.MIGRATED, ConsoleFolder.run(dir));
        Path history = newDir.resolve("history");
        assertEquals("new day", Files.readString(history.resolve("2026-09-20.log")));
        assertEquals("x", Files.readString(history.resolve("2026-09-20-old.log")));
        assertEquals("new errors", Files.readString(newDir.resolve("console-errors.log")));
        assertEquals("e", Files.readString(newDir.resolve("console-errors-old.log")));
        // The old window's size is stale next to the new one: dropped, not kept twice.
        assertEquals("120x40", Files.readString(newDir.resolve("size.txt")));
        assertFalse(Files.exists(newDir.resolve("size-old.txt")));
        assertFalse(Files.exists(dir.resolve("consola")));
    }

    @Test
    void oldAndNewHistoryInsideConsoleAreMerged() throws IOException {
        Path newDir = newFolderFromTheModule();
        Path oldHistory = Files.createDirectories(newDir.resolve("historial"));
        Files.writeString(oldHistory.resolve("2026-09-20.log"), "old only");
        Files.writeString(oldHistory.resolve("2026-09-24.log"), "old today");

        assertEquals(ConsoleFolder.Outcome.MIGRATED, ConsoleFolder.run(dir));
        Path history = newDir.resolve("history");
        assertFalse(Files.exists(oldHistory));
        assertEquals("old only", Files.readString(history.resolve("2026-09-20.log")));
        assertEquals("today", Files.readString(history.resolve("2026-09-24.log")));
        assertEquals("old today", Files.readString(history.resolve("2026-09-24-old.log")));

        assertEquals(ConsoleFolder.Outcome.NOTHING, ConsoleFolder.run(dir));
    }

    @Test
    void aHeldHistoryFileStaysAndIsMergedOnTheNextStart() throws IOException {
        oldFolder();
        Path newDir = newFolderFromTheModule();
        ConsoleFolder.Outcome o = ConsoleFolder.run(dir, (from, to) -> {
            if (from.getFileName().toString().equals("2026-09-20.log")) throw new IOException("locked");
            Files.move(from, to);
        });
        assertEquals(ConsoleFolder.Outcome.PARTIAL, o);
        assertEquals("x", Files.readString(dir.resolve("consola").resolve("historial").resolve("2026-09-20.log")));
        assertFalse(Files.exists(newDir.resolve("history").resolve("2026-09-20.log")));
        assertTrue(Files.exists(newDir.resolve("size.txt")));

        assertEquals(ConsoleFolder.Outcome.MIGRATED, ConsoleFolder.run(dir));
        assertFalse(Files.exists(dir.resolve("consola")));
        assertEquals("x", Files.readString(newDir.resolve("history").resolve("2026-09-20.log")));
    }

    @Test
    void aMergeWhereEverythingIsHeldIsBusy() throws IOException {
        Path old = Files.createDirectories(dir.resolve("consola").resolve("historial"));
        Files.writeString(old.resolve("2026-09-20.log"), "x");
        newFolderFromTheModule();
        ConsoleFolder.Outcome o = ConsoleFolder.run(dir, (from, to) -> {
            throw new IOException("in use");
        });
        assertEquals(ConsoleFolder.Outcome.BUSY, o);
        assertTrue(Files.exists(old.resolve("2026-09-20.log")));
    }

    @Test
    void aConsoleFolderWithOnlyNewNamesIsNothingToDo() throws IOException {
        newFolderFromTheModule();
        assertEquals(ConsoleFolder.Outcome.NOTHING, ConsoleFolder.run(dir, (from, to) -> {
            throw new AssertionError("nothing may be moved: " + from + " -> " + to);
        }));
    }
}
