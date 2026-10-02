package com.xploits.restock;

import com.xploits.restock.core.MarkBook;
import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.utils.Utils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Optional;

/**
 * The marks of the world the player is in (restock spec §4), loaded on first use per world from
 * {@code meteor-client/xploits/restock/<world>/marks.txt} (the world name sanitized by Meteor, as stash-keeper's folder)
 * and written after every change, atomically. A file that cannot be read is never overwritten: marking refuses until the
 * player moves it away, as the message says; each marking or clearing attempt reads it again, and so does every join.
 * Client thread.
 */
public final class MarkStore {
    public enum Result { MARKED, UNMARKED, UNREADABLE, SAVE_FAILED }

    /** What {@link #clear} did: {@code UNMARKED} with how many marks it removed, or why it removed none. */
    public record Cleared(Result result, int count) {
    }

    private static String world;
    private static MarkBook book;
    private static boolean unreadable;
    private static int version;

    private MarkStore() {
    }

    private static Path file() {
        return MeteorClient.FOLDER.toPath().resolve("xploits").resolve("restock").resolve(Utils.getFileWorldName())
            .resolve("marks.txt");
    }

    /** This world's marks; an empty book without a world; null when its file cannot be read. */
    public static synchronized MarkBook book() {
        if (MeteorClient.mc.world == null) return new MarkBook(List.of());
        String now = Utils.getFileWorldName();
        if (!now.equals(world)) {
            world = now;
            unreadable = false;
            book = load();
        }
        return unreadable ? null : book;
    }

    public static synchronized Result toggle(MarkBook.Mark mark) {
        if (unreadable) world = null; // a write attempt reads the file again: the player may have moved it away
        MarkBook b = book();
        if (b == null) return Result.UNREADABLE;
        boolean marked = b.toggle(mark);
        try {
            save(b);
        } catch (IOException e) {
            b.toggle(mark);
            return Result.SAVE_FAILED;
        }
        version++;
        return marked ? Result.MARKED : Result.UNMARKED;
    }

    /**
     * Removes every mark of this world: {@code UNMARKED} with how many there were; {@code UNREADABLE} when the file cannot
     * be read; {@code SAVE_FAILED} when the emptied file could not be written, every mark kept (deferred L49).
     */
    public static synchronized Cleared clear() {
        if (unreadable) world = null;
        MarkBook b = book();
        if (b == null) return new Cleared(Result.UNREADABLE, 0);
        if (b.size() == 0) return new Cleared(Result.UNMARKED, 0);
        List<MarkBook.Mark> before = b.all();
        int n = b.clear();
        try {
            save(b);
        } catch (IOException e) {
            for (MarkBook.Mark m : before) b.toggle(m);
            return new Cleared(Result.SAVE_FAILED, 0);
        }
        version++;
        return new Cleared(Result.UNMARKED, n);
    }

    /** Forgets the loaded world, so the next use reads its file again (every join does). */
    static synchronized void forget() {
        world = null;
    }

    /** Grows on every change, so a session can tell its sources changed. */
    public static synchronized int version() {
        return version;
    }

    private static MarkBook load() {
        Path f = file();
        if (!Files.isRegularFile(f)) return new MarkBook(List.of());
        try {
            Optional<MarkBook> read = MarkBook.fromLines(Files.readAllLines(f, StandardCharsets.UTF_8));
            if (read.isPresent()) return read.get();
        } catch (IOException e) {
            // Unreadable: below.
        }
        unreadable = true;
        return null;
    }

    private static void save(MarkBook b) throws IOException {
        Path f = file();
        Files.createDirectories(f.getParent());
        Path tmp = f.resolveSibling(f.getFileName() + ".tmp");
        Files.write(tmp, b.toLines(), StandardCharsets.UTF_8);
        try {
            Files.move(tmp, f, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
