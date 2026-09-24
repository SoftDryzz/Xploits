package com.xploits.shared.core.migration;

import java.io.IOException;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Moves the console's folder from {@code xploits/consola} to {@code xploits/console} and renames the files
 * inside (code-in-English design §6–§7). The live stream {@code vivo*.log} is deleted, not moved: a new
 * one starts with the next console.
 *
 * <p>When {@code console} does not exist yet the whole folder is moved in one go. When both exist — an old
 * 0.3.x console window held {@code consola} on the first 0.4.0 start, and the console module created
 * {@code console} in the meantime — the old folder is merged into the new one file by file: history logs
 * move into {@code console/history}, and a log whose name is already taken there keeps both, the old one
 * renamed with an {@code -old} suffix. Nothing is ever overwritten and no history is deleted; the old
 * window's {@code consola.lock}/{@code .pid}/{@code .salida} and {@code tamano.txt} are stale once their
 * new counterparts exist, so those are deleted instead of moved. {@code consola} is removed once empty.
 * The same merge runs inside {@code console} when both {@code historial} and {@code history} exist.
 *
 * <p>Anything still held open (an {@link IOException}) is left in place and retried on every later start,
 * until it succeeds; {@code consola} does not need to still exist for the inner renames to be retried.
 */
public final class ConsoleFolder {
    private ConsoleFolder() {
    }

    public enum Outcome {
        /** No old name anywhere: nothing to do. */
        NOTHING,
        /** Something was moved, merged or cleaned up this run, and nothing old is left. */
        MIGRATED,
        /** Something is still held and nothing could be done this run; retried next start. */
        BUSY,
        /** Some of it was migrated, some is still held; retried next start. */
        PARTIAL,
        /** {@code console} is a plain file, not a folder, next to {@code consola}: nothing touched. */
        BLOCKED
    }

    public interface Mover {
        void move(Path from, Path to) throws IOException;
    }

    private static final String OLD_HISTORY = "historial";
    private static final String NEW_HISTORY = "history";

    private static final Map<String, String> INSIDE = Map.of(
        "consola.lock", "console.lock",
        "consola.pid", "console.pid",
        "consola.salida", "console.exit",
        "consola-errores.log", "console-errors.log",
        "tamano.txt", "size.txt");

    /** Old files that only describe the old window; once the new file exists the old one is dropped. */
    private static final Set<String> STALE_WHEN_NEW_EXISTS = Set.of(
        "consola.lock", "consola.pid", "consola.salida", "tamano.txt");

    public static Outcome run(Path xploitsDir) {
        return run(xploitsDir, (from, to) -> Files.move(from, to));
    }

    public static Outcome run(Path xploitsDir, Mover mover) {
        Path old = xploitsDir.resolve("consola");
        Path newDir = xploitsDir.resolve("console");
        boolean oldExists = Files.isDirectory(old);
        if (!Files.isDirectory(newDir)) {
            // A plain file at the new path can be neither moved onto nor merged into.
            if (Files.exists(newDir)) return oldExists ? Outcome.BLOCKED : Outcome.NOTHING;
            if (!oldExists) return Outcome.NOTHING;
        }
        Tally t = new Tally();
        if (!Files.exists(newDir)) {
            try {
                mover.move(old, newDir);
                t.done = true;
            } catch (IOException e) {
                return Outcome.BUSY;
            }
        } else if (oldExists) {
            mergeOldFolder(old, newDir, mover, t);
        }
        renameInside(newDir, mover, t);
        deleteLiveLogs(newDir, t);
        if (t.held) return t.done ? Outcome.PARTIAL : Outcome.BUSY;
        return t.done ? Outcome.MIGRATED : Outcome.NOTHING;
    }

    /** What happened in one run: whether anything changed, and whether anything is still held. */
    private static final class Tally {
        boolean done;
        boolean held;
    }

    /** Merges everything in {@code consola} into an existing {@code console}, then removes {@code consola}. */
    private static void mergeOldFolder(Path old, Path newDir, Mover mover, Tally t) {
        List<Path> entries;
        try {
            entries = list(old);
        } catch (IOException e) {
            t.held = true;
            return;
        }
        for (Path from : entries) {
            String name = from.getFileName().toString();
            if (name.equals(OLD_HISTORY) && Files.isDirectory(from)) {
                mergeLogs(from, newDir.resolve(NEW_HISTORY), mover, t);
            } else if (isLiveLog(name)) {
                delete(from, t);
            } else if (STALE_WHEN_NEW_EXISTS.contains(name) && Files.exists(newDir.resolve(INSIDE.get(name)))) {
                delete(from, t);
            } else {
                moveKeepingBoth(from, newDir, INSIDE.getOrDefault(name, name), mover, t);
            }
        }
        removeIfEmpty(old, t);
    }

    /** Renames the old names left inside {@code console}, merging {@code historial} into {@code history}. */
    private static void renameInside(Path newDir, Mover mover, Tally t) {
        Path oldHistory = newDir.resolve(OLD_HISTORY);
        Path newHistory = newDir.resolve(NEW_HISTORY);
        if (Files.isDirectory(oldHistory)) {
            if (!Files.exists(newHistory)) {
                move(oldHistory, newHistory, mover, t);
            } else {
                mergeLogs(oldHistory, newHistory, mover, t);
            }
        }
        for (Map.Entry<String, String> e : INSIDE.entrySet()) {
            Path from = newDir.resolve(e.getKey());
            if (!Files.exists(from)) continue;
            if (STALE_WHEN_NEW_EXISTS.contains(e.getKey()) && Files.exists(newDir.resolve(e.getValue()))) {
                delete(from, t);
            } else {
                moveKeepingBoth(from, newDir, e.getValue(), mover, t);
            }
        }
    }

    /** Moves every file of {@code from} into {@code to} (created if missing), then removes {@code from}. */
    private static void mergeLogs(Path from, Path to, Mover mover, Tally t) {
        List<Path> logs;
        try {
            Files.createDirectories(to);
            logs = list(from);
        } catch (IOException e) {
            t.held = true;
            return;
        }
        for (Path log : logs) {
            moveKeepingBoth(log, to, log.getFileName().toString(), mover, t);
        }
        removeIfEmpty(from, t);
    }

    /** Moves {@code from} to {@code dir/name}, or to a suffixed free name when that one is taken. */
    private static void moveKeepingBoth(Path from, Path dir, String name, Mover mover, Tally t) {
        move(from, dir.resolve(freeName(dir, name)), mover, t);
    }

    /** {@code name} if free in {@code dir}; otherwise {@code <base>-old<ext>}, then {@code -old-2}, ... */
    private static String freeName(Path dir, String name) {
        if (!Files.exists(dir.resolve(name))) return name;
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        String candidate = base + "-old" + ext;
        for (int i = 2; Files.exists(dir.resolve(candidate)); i++) {
            candidate = base + "-old-" + i + ext;
        }
        return candidate;
    }

    private static void move(Path from, Path to, Mover mover, Tally t) {
        try {
            mover.move(from, to);
            t.done = true;
        } catch (IOException e) {
            // Still held by something; left in place for the next start to retry.
            t.held = true;
        }
    }

    private static void delete(Path file, Tally t) {
        try {
            if (Files.deleteIfExists(file)) t.done = true;
        } catch (IOException e) {
            t.held = true;
        }
    }

    /** Removes an emptied folder; one that still holds something (a file left held) stays for next start. */
    private static void removeIfEmpty(Path dir, Tally t) {
        try {
            if (Files.deleteIfExists(dir)) t.done = true;
        } catch (DirectoryNotEmptyException e) {
            // Whatever is still inside was already counted as held.
        } catch (IOException e) {
            t.held = true;
        }
    }

    private static void deleteLiveLogs(Path newDir, Tally t) {
        List<Path> entries;
        try {
            entries = list(newDir);
        } catch (IOException e) {
            t.held = true;
            return;
        }
        for (Path p : entries) {
            if (isLiveLog(p.getFileName().toString())) delete(p, t);
        }
    }

    /** The old live stream: {@code vivo.log}, {@code vivo.1.log}. */
    private static boolean isLiveLog(String name) {
        return name.startsWith("vivo") && name.endsWith(".log");
    }

    private static List<Path> list(Path dir) throws IOException {
        try (Stream<Path> s = Files.list(dir)) {
            return s.sorted().toList();
        }
    }
}
