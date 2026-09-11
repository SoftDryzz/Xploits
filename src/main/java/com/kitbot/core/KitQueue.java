package com.kitbot.core;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Cola de kits leída de kits-queue.txt (spec §7). Es inmutable: los pendientes se calculan contra el progreso,
 * así que "devolver un lote al frente" es simplemente no marcarlo como resuelto.
 */
public final class KitQueue {
    public static final int MAX_BATCH = 5;

    private static final Pattern ID_LINE = Pattern.compile("^#?(\\d{1,6})(?:\\s.*)?$");

    private final List<Integer> ids;

    private KitQueue(List<Integer> ids) {
        this.ids = List.copyOf(ids);
    }

    /** Acepta la salida de "Copiar pendientes" ("#285 Stash Kit"); cabeceras, líneas vacías y ruido se ignoran. */
    public static KitQueue parse(String text) {
        Set<Integer> ids = new LinkedHashSet<>();
        for (String line : text.split("\\R")) {
            Matcher m = ID_LINE.matcher(line.strip());
            if (m.matches()) ids.add(Integer.parseInt(m.group(1)));
        }
        return new KitQueue(List.copyOf(ids));
    }

    public List<Integer> ids() {
        return ids;
    }

    public List<Integer> pending(Set<Integer> resolved) {
        return ids.stream().filter(id -> !resolved.contains(id)).toList();
    }

    public List<Integer> nextBatch(Set<Integer> resolved) {
        List<Integer> pending = pending(resolved);
        return List.copyOf(pending.subList(0, Math.min(MAX_BATCH, pending.size())));
    }
}
