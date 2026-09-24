package com.xploits.shared.core.migration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Moves the console's folder from {@code xploits/consola} to {@code xploits/console} and renames the files
 * inside (code-in-English design §6–§7). The live stream {@code vivo*.log} is deleted, not moved: a new
 * one starts with the next console. If the move fails — typically an old console window still holds a
 * file — nothing is touched and the next start tries again.
 */
public final class ConsoleFolder {
    private ConsoleFolder() {
    }

    public enum Outcome { NOTHING, MOVED, NEW_ALREADY_EXISTS, BUSY }

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
        if (!Files.isDirectory(old)) return Outcome.NOTHING;
        if (Files.exists(neu)) return Outcome.NEW_ALREADY_EXISTS;
        try {
            mover.move(old, neu);
        } catch (IOException e) {
            return Outcome.BUSY;
        }
        for (Map.Entry<String, String> e : INSIDE.entrySet()) {
            Path from = neu.resolve(e.getKey());
            Path to = neu.resolve(e.getValue());
            if (Files.exists(from) && !Files.exists(to)) {
                try {
                    mover.move(from, to);
                } catch (IOException ignored) {
                    // The folder is already moved; a leftover old name only loses that one file's history.
                }
            }
        }
        for (String live : new String[] {"vivo.log", "vivo.1.log"}) {
            try {
                Files.deleteIfExists(neu.resolve(live));
            } catch (IOException ignored) {
                // A live stream nobody reads any more; harmless if it stays.
            }
        }
        return Outcome.MOVED;
    }
}
