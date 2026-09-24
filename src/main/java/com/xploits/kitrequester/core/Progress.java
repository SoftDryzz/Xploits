package com.xploits.kitrequester.core;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Model of progress.json (spec §7). Public fields for Gson; the initial values cover missing fields. */
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

    /** IDs that no longer need to be requested. Failures and the active order do not count. */
    public Set<Integer> resolved() {
        Set<Integer> all = new HashSet<>(delivered);
        partial.forEach(all::addAll);
        all.addAll(notFound);
        unconfirmed.forEach(all::addAll);
        all.addAll(skipped);
        return all;
    }

    /**
     * Repairs what Gson lets through in lenient JSON (null collections, trailing commas that add
     * null elements): gaps in any collection are filled with empty ones, and stray nulls are removed.
     * An {@code activeOrder} without usable ids cannot be repaired silently: an IOException is thrown
     * so the file is left untouched (spec §7).
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
                throw new IOException("activeOrder without ids; it has not been modified.");
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
