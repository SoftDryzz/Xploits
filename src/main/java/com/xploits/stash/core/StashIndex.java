package com.xploits.stash.core;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Todo lo que el addon ha visto dentro de contenedores (spec §4). Nunca borra: una foto nueva
 * reemplaza a la anterior del mismo contenedor, y nada caduca (spec §4.4).
 */
public final class StashIndex {
    private final Map<ContainerKey, ContainerSnapshot> byKey = new LinkedHashMap<>();

    /**
     * Un ítem encontrado en un contenedor.
     *
     * @param insideShulker identidad del shulker que lo contenía, o null si estaba suelto en el contenedor
     */
    public record Hit(ContainerKey key, ContainerType type, String itemId, int count, long seenAt, String insideShulker) {}

    /** Da de alta la foto, o reemplaza la que hubiera de ese mismo contenedor. */
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

    /** Dónde hay alguno de esos ítems, de más cantidad a menos. Los shulkers se miran por dentro. */
    public List<Hit> find(Collection<String> itemIds) {
        List<Hit> hits = new ArrayList<>();
        for (ContainerSnapshot snapshot : byKey.values()) {
            for (String itemId : itemIds) {
                int loose = snapshot.items().getOrDefault(itemId, 0);
                if (loose > 0) {
                    hits.add(new Hit(snapshot.key(), snapshot.type(), itemId, loose, snapshot.seenAt(), null));
                }
                for (NestedShulker shulker : snapshot.nested()) {
                    int inside = shulker.totalOf(itemId);
                    if (inside > 0) {
                        hits.add(new Hit(snapshot.key(), snapshot.type(), itemId, inside, snapshot.seenAt(), shulker.identity()));
                    }
                }
            }
        }
        hits.sort(Comparator.comparingInt(Hit::count).reversed());
        return hits;
    }
}
