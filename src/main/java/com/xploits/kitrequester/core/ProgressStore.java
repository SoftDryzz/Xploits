package com.xploits.kitrequester.core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** Loads and saves progress.json. Atomic write; a corrupt file is never overwritten (spec §7). */
public final class ProgressStore {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Path file;

    public ProgressStore(Path file) {
        this.file = file;
    }

    public Path file() {
        return file;
    }

    /** Without a file it returns an empty progress. If it exists but cannot be understood, throws IOException without touching it. */
    public Progress load() throws IOException {
        if (!Files.exists(file)) return new Progress();
        String json = Files.readString(file);
        Progress progress;
        try {
            progress = GSON.fromJson(json, Progress.class);
            if (progress == null) throw new IOException(file + " is empty; it has not been modified.");
        } catch (JsonParseException e) {
            throw new IOException(file + " is corrupt; it has not been modified: " + e.getMessage(), e);
        }
        try {
            progress.sanitize();
        } catch (IOException e) {
            throw new IOException(file + ": " + e.getMessage(), e);
        }
        return progress;
    }

    public void save(Progress progress) throws IOException {
        Files.createDirectories(file.getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(tmp, GSON.toJson(progress));
        try {
            Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
