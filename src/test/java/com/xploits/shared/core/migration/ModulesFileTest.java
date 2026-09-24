package com.xploits.shared.core.migration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModulesFileTest {
    @TempDir
    Path dir;

    private final List<Object> written = new ArrayList<>();

    private boolean apply(Path file, ModulesTree.Result r) throws IOException {
        return ModulesFile.writeIfChanged(file, r, (tree, target) -> {
            written.add(tree);
            Files.writeString(target, "new");
        });
    }

    @Test
    void anUnchangedFileIsNotWrittenNorBackedUp() throws IOException {
        Path f = Files.writeString(dir.resolve("modules.nbt"), "old");
        assertFalse(apply(f, new ModulesTree.Result(Map.of(), false)));
        assertEquals(List.of(), written);
        assertFalse(Files.exists(dir.resolve("modules.nbt.pre-0.4.0.backup.nbt")));
    }

    @Test
    void aChangedFileIsBackedUpThenReplaced() throws IOException {
        Path f = Files.writeString(dir.resolve("modules.nbt"), "old");
        assertTrue(apply(f, new ModulesTree.Result(Map.of("x", "y"), true)));
        assertEquals("old", Files.readString(dir.resolve("modules.nbt.pre-0.4.0.backup.nbt")));
        assertEquals("new", Files.readString(f));
    }

    @Test
    void secondRunDoesNotWriteOrBackUpAgain() throws IOException {
        Path f = Files.writeString(dir.resolve("modules.nbt"), "old");
        apply(f, new ModulesTree.Result(Map.of("x", "y"), true));
        Files.writeString(dir.resolve("modules.nbt.pre-0.4.0.backup.nbt"), "first backup");
        apply(f, new ModulesTree.Result(Map.of("x", "y"), true));
        assertEquals("first backup", Files.readString(dir.resolve("modules.nbt.pre-0.4.0.backup.nbt")));
        assertFalse(apply(f, new ModulesTree.Result(Map.of(), false)));
    }
}
