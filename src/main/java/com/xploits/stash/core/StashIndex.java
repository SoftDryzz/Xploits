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

    /** Total de shulkers vistos, sumando los de todos los contenedores indexados. */
    public int totalShulkers() {
        int total = 0;
        for (ContainerSnapshot snapshot : byKey.values()) total += snapshot.nested().size();
        return total;
    }

    /**
     * Dónde hay alguno de esos ítems, de más cantidad a menos. Los shulkers se miran por dentro.
     * Recorre los ítems de cada contenedor una vez (coste snapshots × ítems) en vez de recorrer
     * itemIds por cada snapshot (coste snapshots × ids × shulkers, mucho peor cuando la consulta
     * resuelve a muchos ids).
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
