package com.xploits.shared.core.migration;

import java.io.IOException;
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
        if (!Files.exists(backup)) Files.copy(file, backup);
        Path temp = file.resolveSibling(file.getFileName() + ".xploits-tmp");
        writer.write(result.tree(), temp);
        try {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
        }
        return true;
    }
}
