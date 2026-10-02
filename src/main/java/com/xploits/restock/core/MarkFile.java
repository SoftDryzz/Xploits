package com.xploits.restock.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Optional;

/**
 * One world's marks file (restock spec §4): the book it holds and the rules that keep the player's marks safe. No file
 * is an empty book; a file that cannot be told apart from "no file" or cannot be read is never overwritten
 * ({@link #book()} is null and every change is refused until {@link #reload()} reads it again); a change that cannot be
 * saved is undone exactly, the mark it replaced included; a file holding one container twice is not one this class wrote.
 * Not thread-safe: {@code MarkStore} owns it.
 */
public final class MarkFile {
    public enum Result { MARKED, UNMARKED, UNREADABLE, SAVE_FAILED }

    /** What {@link #clear} did: {@code UNMARKED} with how many marks it removed, or why it removed none. */
    public record Cleared(Result result, int count) {
    }

    private final Path file;
    private MarkBook book;
    private boolean unreadable;

    public MarkFile(Path file) {
        this.file = file;
        reload();
    }

    /** Reads the file again, forgetting whatever was loaded. */
    public void reload() {
        unreadable = false;
        book = read();
    }

    /** The marks; null when the file cannot be read. */
    public MarkBook book() {
        return unreadable ? null : book;
    }

    public boolean unreadable() {
        return unreadable;
    }

    public Result toggle(MarkBook.Mark mark) {
        if (unreadable) return Result.UNREADABLE;
        List<MarkBook.Mark> before = book.all();
        boolean marked = book.toggle(mark);
        try {
            save(book);
        } catch (IOException e) {
            book = new MarkBook(before);
            return Result.SAVE_FAILED;
        }
        return marked ? Result.MARKED : Result.UNMARKED;
    }

    public Cleared clear() {
        if (unreadable) return new Cleared(Result.UNREADABLE, 0);
        if (book.size() == 0) return new Cleared(Result.UNMARKED, 0);
        List<MarkBook.Mark> before = book.all();
        int n = book.clear();
        try {
            save(book);
        } catch (IOException e) {
            book = new MarkBook(before);
            return new Cleared(Result.SAVE_FAILED, 0);
        }
        return new Cleared(Result.UNMARKED, n);
    }

    private MarkBook read() {
        if (Files.notExists(file)) return new MarkBook(List.of());
        try {
            Optional<MarkBook> read = MarkBook.fromLines(Files.readAllLines(file, StandardCharsets.UTF_8));
            if (read.isPresent()) return read.get();
        } catch (IOException e) {
            // Unreadable: below. Never the exception's text: it could name a path.
        }
        unreadable = true;
        return null;
    }

    private void save(MarkBook b) throws IOException {
        Files.createDirectories(file.getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.write(tmp, b.toLines(), StandardCharsets.UTF_8);
        try {
            Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
