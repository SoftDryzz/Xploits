package com.xploits.shared.core.migration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Moves the console's folder from {@code xploits/consola} to {@code xploits/console} and renames the files
 * inside (code-in-English design §6–§7). The live stream {@code vivo*.log} is deleted, not moved: a new
 * one starts with the next console. If the top-level move fails — typically an old console window still
 * holds a file — nothing is touched and the next start tries again.
 *
 * <p>The inner renames run every time {@code console} exists, whether or not this call is also the one
 * that moved it there: a rename that failed once (its file was held open) is retried on every later start,
 * until it succeeds — {@code consola} does not need to still exist for that retry to happen.
 */
public final class ConsoleFolder {
    private ConsoleFolder() {
    }

    public enum Outcome { NOTHING, MOVED, NEW_ALREADY_EXISTS, BUSY, PARTIAL }

    public interface Mover {
        void move(Path from, Path to) throws IOException;
    }

    private static final Map<String, String> INSIDE = Map.of(
        "historial", "history",
        "consola.lock", "console.lock",
        "consola.pid", "console.pid",
        "consola.salida", "console.exit",
        "consola-errores.log", "console-errors.log",
        "tamano.txt", "size.txt");

    public static Outcome run(Path xploitsDir) {
        return run(xploitsDir, (from, to) -> Files.move(from, to));
    }

    public static Outcome run(Path xploitsDir, Mover mover) {
        Path old = xploitsDir.resolve("consola");
        Path neu = xploitsDir.resolve("console");
        boolean oldExists = Files.isDirectory(old);
        // Anything at the new path blocks the move, a plain file as much as a folder: moving onto it
        // would fail every start and read as BUSY forever.
        boolean newExists = Files.exists(neu);
        if (oldExists && newExists) return Outcome.NEW_ALREADY_EXISTS;
        if (!oldExists && !Files.isDirectory(neu)) return Outcome.NOTHING;
        if (oldExists) {
            try {
                mover.move(old, neu);
            } catch (IOException e) {
                return Outcome.BUSY;
            }
        }
        boolean allRenamed = renameInside(neu, mover);
        deleteLiveLogs(neu);
        return allRenamed ? Outcome.MOVED : Outcome.PARTIAL;
    }

    /** @return whether every leftover old name inside {@code neu} was renamed */
    private static boolean renameInside(Path neu, Mover mover) {
        boolean allOk = true;
        for (Map.Entry<String, String> e : INSIDE.entrySet()) {
            Path from = neu.resolve(e.getKey());
            Path to = neu.resolve(e.getValue());
            if (Files.exists(from) && !Files.exists(to)) {
                try {
                    mover.move(from, to);
                } catch (IOException ignored) {
                    // Still held by something; left in place for the next start to retry.
                    allOk = false;
                }
            }
        }
        return allOk;
    }

    private static void deleteLiveLogs(Path neu) {
        for (String live : new String[] {"vivo.log", "vivo.1.log"}) {
            try {
                Files.deleteIfExists(neu.resolve(live));
            } catch (IOException ignored) {
                // A live stream nobody reads any more; harmless if it stays.
            }
        }
    }
}
