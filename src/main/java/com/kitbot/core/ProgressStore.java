package com.kitbot.core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** Carga y guarda progress.json. Escritura atómica; un archivo corrupto nunca se sobrescribe (spec §7). */
public final class ProgressStore {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Path file;

    public ProgressStore(Path file) {
        this.file = file;
    }

    public Path file() {
        return file;
    }

    /** Sin archivo devuelve un progreso vacío. Si existe pero no se entiende, lanza IOException sin tocarlo. */
    public Progress load() throws IOException {
        if (!Files.exists(file)) return new Progress();
        String json = Files.readString(file);
        Progress progress;
        try {
            progress = GSON.fromJson(json, Progress.class);
            if (progress == null) throw new IOException(file + " está vacío; no se ha modificado.");
        } catch (JsonParseException e) {
            throw new IOException(file + " está corrupto; no se ha modificado: " + e.getMessage(), e);
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
