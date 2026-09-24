package com.xploits.shared.core.migration;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** Backup-then-replace for one settings file, only when the migration changed something. */
public final class ModulesFile {
    private ModulesFile() {
    }

    public interface TreeWriter {
        void write(Object tree, Path target) throws IOException;
    }

    public static Path backupOf(Path file) {
        return file.resolveSibling(file.getFileName() + ".pre-0.4.0.backup.nbt");
    }

    /** @return whether the file was rewritten */
    public static boolean writeIfChanged(Path file, ModulesTree.Result result, TreeWriter writer) throws IOException {
        if (!result.changed()) return false;
        Path backup = backupOf(file);
        if (!Files.exists(backup)) {
            // Copied to a temp name first and moved into place, so a copy cut short by a crash or a full
            // disk never leaves a half-written file sitting at the real backup path, masking the original.
            Path backupTemp = backup.resolveSibling(backup.getFileName() + ".tmp");
            try {
                Files.copy(file, backupTemp, StandardCopyOption.REPLACE_EXISTING);
                move(backupTemp, backup);
            } catch (IOException | RuntimeException e) {
                Files.deleteIfExists(backupTemp);
                throw e;
            }
        }
        Path temp = file.resolveSibling(file.getFileName() + ".xploits-tmp");
        try {
            writer.write(result.tree(), temp);
        } catch (IOException | RuntimeException e) {
            Files.deleteIfExists(temp);
            throw e;
        }
        move(temp, file);
        return true;
    }

    private static void move(Path from, Path to) throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
