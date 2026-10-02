package com.xploits.restock;

import com.xploits.printer.core.Pos;
import com.xploits.restock.core.MarkBook;
import com.xploits.restock.core.Source;
import com.xploits.stash.core.ContainerKey;
import com.xploits.stash.core.ContainerSnapshot;
import com.xploits.stash.core.ContainerType;
import com.xploits.stash.core.NestedShulker;
import com.xploits.stash.core.StashIndex;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * The containers restock may fetch from in one dimension (restock spec §3 "Sources and choice"): the marks (chests,
 * trapped chests, copper chests, barrels and placed shulker boxes), with the contents restock saw when it opened them
 * this session, else stash-keeper's snapshot of them, else unknown; then every container stash-keeper remembers that is
 * not marked — chests, trapped chests, barrels and placed shulker boxes (it does not index copper chests), never the
 * ender chest, which has no position. Client thread.
 */
final class Sources {
    private record Contents(Map<String, Integer> loose, Map<String, Integer> nested) {
    }

    private final Map<Pos, Contents> seen = new HashMap<>();

    List<Source> list(String dimension, List<MarkBook.Mark> marks, StashIndex stash) {
        List<Source> out = new ArrayList<>();
        Set<Pos> marked = new HashSet<>();
        for (MarkBook.Mark m : marks) {
            if (!m.dimension().equals(dimension)) continue;
            marked.add(m.container());
            Contents c = seen.get(m.container());
            if (c == null && stash != null) {
                c = stash.get(ContainerKey.block(dimension, m.container().x(), m.container().y(), m.container().z()))
                    .map(Sources::of).orElse(null);
            }
            out.add(c == null ? Source.unknownMark(dimension, m.container(), m.stand())
                : Source.mark(dimension, m.container(), m.stand(), c.loose(), c.nested()));
        }
        if (stash == null) return out;
        for (ContainerSnapshot s : stash.all()) {
            ContainerKey k = s.key();
            if (k.isEnder() || !k.dimension().equals(dimension) || !placed(s.type())) continue;
            Pos p = new Pos(k.x(), k.y(), k.z());
            if (marked.contains(p)) continue;
            Contents c = seen.getOrDefault(p, of(s));
            out.add(Source.stash(dimension, p, c.loose(), c.nested()));
        }
        return out;
    }

    /** What restock saw in a container it opened, closing it: the truth until stash-keeper or restock sees it again. */
    void saw(Pos container, Map<String, Integer> loose, Map<String, Integer> nested) {
        seen.put(container, new Contents(Map.copyOf(loose), Map.copyOf(nested)));
    }

    private static Contents of(ContainerSnapshot s) {
        Map<String, Integer> nested = new TreeMap<>();
        for (NestedShulker n : s.nested()) n.items().forEach((item, count) -> nested.merge(item, count, Integer::sum));
        return new Contents(s.items(), nested);
    }

    private static boolean placed(ContainerType type) {
        return type == ContainerType.CHEST || type == ContainerType.TRAPPED_CHEST || type == ContainerType.BARREL
            || type == ContainerType.SHULKER_BLOCK;
    }
}
