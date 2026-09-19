package com.xploits.stash.core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Carga y guarda index.json (spec §11). Escritura atómica y, igual que ProgressStore, un archivo
 * corrupto se denuncia y nunca se sobrescribe.
 */
public final class StashStore {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Path file;

    public StashStore(Path file) {
        this.file = file;
    }

    public Path file() {
        return file;
    }

    public StashIndex load() throws IOException {
        StashIndex index = new StashIndex();
        if (!Files.exists(file)) return index;

        FileDto dto;
        try {
            dto = GSON.fromJson(Files.readString(file), FileDto.class);
        } catch (JsonParseException e) {
            throw new IOException(file + " está corrupto; no se ha modificado: " + e.getMessage(), e);
        }
        if (dto == null || dto.containers == null) {
            throw new IOException(file + " está vacío; no se ha modificado.");
        }

        for (ContainerDto container : dto.containers) {
            index.put(container.toSnapshot());
        }
        return index;
    }

    public void save(StashIndex index) throws IOException {
        FileDto dto = new FileDto();
        for (ContainerSnapshot snapshot : index.all()) dto.containers.add(ContainerDto.of(snapshot));

        Files.createDirectories(file.getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(tmp, GSON.toJson(dto));
        try {
            Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static final class FileDto {
        List<ContainerDto> containers = new ArrayList<>();
    }

    private static final class ContainerDto {
        String dimension;
        int x, y, z;
        boolean ender;
        String type;
        long seenAt;
        Map<String, Integer> items = new LinkedHashMap<>();
        List<ShulkerDto> nested = new ArrayList<>();

        static ContainerDto of(ContainerSnapshot snapshot) {
            ContainerDto dto = new ContainerDto();
            ContainerKey key = snapshot.key();
            dto.ender = key.isEnder();
            dto.dimension = key.dimension();
            dto.x = key.x();
            dto.y = key.y();
            dto.z = key.z();
            dto.type = snapshot.type().name();
            dto.seenAt = snapshot.seenAt();
            dto.items = new LinkedHashMap<>(snapshot.items());
            for (NestedShulker shulker : snapshot.nested()) dto.nested.add(ShulkerDto.of(shulker));
            return dto;
        }

        ContainerSnapshot toSnapshot() {
            ContainerKey key = ender ? ContainerKey.ENDER : ContainerKey.block(dimension, x, y, z);
            List<NestedShulker> shulkers = new ArrayList<>();
            if (nested != null) for (ShulkerDto dto : nested) shulkers.add(dto.toShulker());
            return new ContainerSnapshot(key, ContainerType.valueOf(type), seenAt,
                items == null ? Map.of() : items, shulkers);
        }
    }

    private static final class ShulkerDto {
        int slot;
        String customName;
        String color;
        Map<String, Integer> items = new LinkedHashMap<>();

        static ShulkerDto of(NestedShulker shulker) {
            ShulkerDto dto = new ShulkerDto();
            dto.slot = shulker.slot();
            dto.customName = shulker.customName();
            dto.color = shulker.color();
            dto.items = new LinkedHashMap<>(shulker.items());
            return dto;
        }

        NestedShulker toShulker() {
            return new NestedShulker(slot, customName, color, items == null ? Map.of() : items);
        }
    }
}
