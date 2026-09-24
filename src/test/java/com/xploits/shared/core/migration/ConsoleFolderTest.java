package com.xploits.shared.core.migration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConsoleFolderTest {
    @TempDir
    Path dir;

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
        assertEquals(ConsoleFolder.Outcome.MOVED, ConsoleFolder.run(dir));
        Path neu = dir.resolve("console");
        assertFalse(Files.exists(dir.resolve("consola")));
        assertTrue(Files.exists(neu.resolve("history").resolve("2026-09-20.log")));
        assertTrue(Files.exists(neu.resolve("console.lock")));
        assertTrue(Files.exists(neu.resolve("console.pid")));
        assertTrue(Files.exists(neu.resolve("console.exit")));
        assertTrue(Files.exists(neu.resolve("console-errors.log")));
        assertTrue(Files.exists(neu.resolve("size.txt")));
        assertFalse(Files.exists(neu.resolve("vivo.log")));
        assertFalse(Files.exists(neu.resolve("vivo.1.log")));
    }

    @Test
    void nothingToDoWithoutTheOldFolder() {
        assertEquals(ConsoleFolder.Outcome.NOTHING, ConsoleFolder.run(dir));
    }

    @Test
    void anExistingNewFolderIsNeverOverwritten() throws IOException {
        oldFolder();
        Files.createDirectories(dir.resolve("console"));
        assertEquals(ConsoleFolder.Outcome.NEW_ALREADY_EXISTS, ConsoleFolder.run(dir));
        assertTrue(Files.exists(dir.resolve("consola").resolve("vivo.log")));
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
}
