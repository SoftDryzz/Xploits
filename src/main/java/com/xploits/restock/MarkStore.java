package com.xploits.restock;

import com.xploits.restock.core.MarkBook;
import com.xploits.restock.core.MarkFile;
import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.utils.Utils;

import java.nio.file.Path;
import java.util.List;

/**
 * The marks of the world the player is in (restock spec §4), loaded on first use per world from
 * {@code meteor-client/xploits/restock/<world>/marks.txt} (the world name sanitized by Meteor, as stash-keeper's folder)
 * and written after every change, atomically; the rules of the file are {@link MarkFile}'s. A file that cannot be read is
 * never overwritten: marking refuses until the player moves it away, as the message says; each marking or clearing
 * attempt reads it again, and so does every join. Client thread.
 */
public final class MarkStore {
    public enum Result { MARKED, UNMARKED, UNREADABLE, SAVE_FAILED }

    /** What {@link #clear} did: {@code UNMARKED} with how many marks it removed, or why it removed none. */
    public record Cleared(Result result, int count) {
    }

    private static String world;
    private static MarkFile marks;
    private static int version;

    private MarkStore() {
    }

    private static Path file(String name) {
        return MeteorClient.FOLDER.toPath().resolve("xploits").resolve("restock").resolve(name).resolve("marks.txt");
    }

    /** The loaded file of this world, read on the first use and again after {@link #forget}; null without a world. */
    private static MarkFile current() {
        if (MeteorClient.mc.world == null) return null;
        String now = Utils.getFileWorldName();
        if (!now.equals(world) || marks == null) {
            world = now;
            marks = new MarkFile(file(now));
        }
        return marks;
    }

    /** This world's marks; an empty book without a world; null when its file cannot be read. */
    public static synchronized MarkBook book() {
        MarkFile f = current();
        return f == null ? new MarkBook(List.of()) : f.book();
    }

    public static synchronized Result toggle(MarkBook.Mark mark) {
        MarkFile f = current();
        if (f == null) return Result.UNREADABLE;
        if (f.unreadable()) f.reload(); // a write attempt reads the file again: the player may have moved it away
        Result r = Result.valueOf(f.toggle(mark).name());
        if (r == Result.MARKED || r == Result.UNMARKED) version++;
        return r;
    }

    /**
     * Removes every mark of this world: {@code UNMARKED} with how many there were; {@code UNREADABLE} when the file cannot
     * be read; {@code SAVE_FAILED} when the emptied file could not be written, every mark kept (deferred L49).
     */
    public static synchronized Cleared clear() {
        MarkFile f = current();
        if (f == null) return new Cleared(Result.UNREADABLE, 0);
        if (f.unreadable()) f.reload();
        MarkFile.Cleared c = f.clear();
        if (c.result() == MarkFile.Result.UNMARKED && c.count() > 0) version++;
        return new Cleared(Result.valueOf(c.result().name()), c.count());
    }

    /** Forgets the loaded world, so the next use reads its file again (every join does). */
    static synchronized void forget() {
        world = null;
        marks = null;
    }

    /** Grows on every change, so a session can tell its sources changed. */
    public static synchronized int version() {
        return version;
    }
}
