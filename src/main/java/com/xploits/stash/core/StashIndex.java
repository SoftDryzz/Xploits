package com.xploits.stash.core;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Everything the addon has seen inside containers (spec §4). It never deletes: a new snapshot
 * replaces the previous one of the same container, and nothing expires (spec §4.4).
 */
public final class StashIndex {
    private final Map<ContainerKey, ContainerSnapshot> byKey = new LinkedHashMap<>();

    /**
     * An item found in a container.
     *
     * @param insideShulker identity of the shulker that held it, or null if it was loose in the container
     */
    public record Hit(ContainerKey key, ContainerType type, String itemId, int count, long seenAt, String insideShulker) {}

    /** Adds the snapshot, or replaces the one already there for that same container. */
    public void put(ContainerSnapshot snapshot) {
        byKey.put(snapshot.key(), snapshot);
    }

    public Optional<ContainerSnapshot> get(ContainerKey key) {
        return Optional.ofNullable(byKey.get(key));
    }

    public Collection<ContainerSnapshot> all() {
        return Collections.unmodifiableCollection(byKey.values());
    }

    public int size() {
        return byKey.size();
    }

    /** Total shulkers seen, adding up those of every indexed container. */
    public int totalShulkers() {
        int total = 0;
        for (ContainerSnapshot snapshot : byKey.values()) total += snapshot.nested().size();
        return total;
    }

    /**
     * Where any of those items are, from the largest count to the smallest. Shulkers are looked
     * inside. Walks each container's items once (cost snapshots × items) instead of walking itemIds
     * for each snapshot (cost snapshots × ids × shulkers, much worse when the query resolves to
     * many ids).
     */
    public List<Hit> find(Collection<String> itemIds) {
        Set<String> wanted = itemIds instanceof Set<String> set ? set : new HashSet<>(itemIds);

        List<Hit> hits = new ArrayList<>();
        for (ContainerSnapshot snapshot : byKey.values()) {
            for (Map.Entry<String, Integer> entry : snapshot.items().entrySet()) {
                if (entry.getValue() > 0 && wanted.contains(entry.getKey())) {
                    hits.add(new Hit(snapshot.key(), snapshot.type(), entry.getKey(), entry.getValue(), snapshot.seenAt(), null));
                }
            }
            for (NestedShulker shulker : snapshot.nested()) {
                for (Map.Entry<String, Integer> entry : shulker.items().entrySet()) {
                    if (entry.getValue() > 0 && wanted.contains(entry.getKey())) {
                        hits.add(new Hit(snapshot.key(), snapshot.type(), entry.getKey(), entry.getValue(), snapshot.seenAt(), shulker.identity()));
                    }
                }
            }
        }
        hits.sort(Comparator.comparingInt(Hit::count).reversed());
        return hits;
    }
}
