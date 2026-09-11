package com.kitbot.core;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
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
}
