package com.kitbot.kitrequester.core;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Modelo de progress.json (spec §7). Campos públicos para Gson; los valores iniciales cubren campos ausentes. */
public final class Progress {
    public Set<Integer> delivered = new LinkedHashSet<>();
    public List<List<Integer>> partial = new ArrayList<>();
    public Set<Integer> notFound = new LinkedHashSet<>();
    public List<List<Integer>> unconfirmed = new ArrayList<>();
    public Set<Integer> skipped = new LinkedHashSet<>();
    public Map<Integer, Integer> failures = new LinkedHashMap<>();
    public ActiveOrder activeOrder;
    public long nextOrderAt;

    public record ActiveOrder(List<Integer> ids, long placedAt, String courier) {
        public ActiveOrder withCourier(String courier) {
            return new ActiveOrder(ids, placedAt, courier);
        }
    }

    /** IDs que ya no hay que pedir. Los fallos y el pedido activo no cuentan. */
    public Set<Integer> resolved() {
        Set<Integer> all = new HashSet<>(delivered);
        partial.forEach(all::addAll);
        all.addAll(notFound);
        unconfirmed.forEach(all::addAll);
        all.addAll(skipped);
        return all;
    }

    /**
     * Repara lo que Gson deja pasar en JSON tolerante (colecciones nulas, comas colgantes que añaden
     * elementos null): huecos en cualquier colección se rellenan vacíos, y los nulls sueltos se quitan.
     * {@code activeOrder} sin ids utilizables no se puede reparar en silencio: se lanza IOException para
     * que el archivo no se toque (spec §7).
     */
    void sanitize() throws IOException {
        if (delivered == null) delivered = new LinkedHashSet<>();
        else delivered.removeIf(Objects::isNull);

        if (partial == null) partial = new ArrayList<>();
        else sanitizeIdLists(partial);

        if (notFound == null) notFound = new LinkedHashSet<>();
        else notFound.removeIf(Objects::isNull);

        if (unconfirmed == null) unconfirmed = new ArrayList<>();
        else sanitizeIdLists(unconfirmed);

        if (skipped == null) skipped = new LinkedHashSet<>();
        else skipped.removeIf(Objects::isNull);

        if (failures == null) failures = new LinkedHashMap<>();
        else failures.entrySet().removeIf(e -> e.getKey() == null || e.getValue() == null);

        if (activeOrder != null) {
            List<Integer> ids = activeOrder.ids();
            if (ids == null || ids.contains(null)) {
                throw new IOException("activeOrder sin ids; no se ha modificado.");
            }
        }
    }

    private static void sanitizeIdLists(List<List<Integer>> lists) {
        lists.removeIf(Objects::isNull);
        for (List<Integer> inner : lists) {
            inner.removeIf(Objects::isNull);
        }
    }
}
