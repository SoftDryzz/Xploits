package com.xploits.restock.core;

import com.xploits.printer.core.BlockFacts;
import com.xploits.printer.core.BuildIndex;
import com.xploits.printer.core.MaterialNeeds;
import com.xploits.printer.core.Target;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * What the build still needs (restock spec §3 "Counting"): the selected placement indexed position by position with
 * {@link BuildIndex} (the same block id is placed, whatever its state; a wrong block or an unknown position is not), the
 * whole build's totals by item, and the need {@code total − placed − carried} through {@link MaterialNeeds#need} with no
 * placement of ours in flight (the printer places, not restock). Unknown parts are never placed, so they are fetched
 * for: more, never less.
 */
public final class RestockNeeds {
    /** One block state of the whole build: the item that places it, the items one block of it costs, how many blocks. */
    public record Counted(String item, int perBlock, long blocks) {
    }

    /**
     * A position as restock's index holds it; {@code material} only for {@code MATCHES} and {@code MISSING}.
     * {@code extra}: items beyond one that a {@code MATCHES} position already holds (a double slab, four candles), 0 for
     * every other status; {@link BuildIndex#placed()} counts positions, the totals count items. {@code partial}: a
     * {@code MATCHES} position that holds fewer items than the build wants there (one slab where a double goes), which
     * the index counts placed although part of its need is still missing ({@link PartlyPlaced}).
     */
    public record Classified(BuildIndex.Status status, String material, int extra, boolean partial) {
        /** A position that is not partly filled. */
        public Classified(BuildIndex.Status status, String material, int extra) {
            this(status, material, extra, false);
        }
    }

    private RestockNeeds() {
    }

    public static Classified classify(Target target, int perBlock, BlockFacts world, int worldPerBlock) {
        return switch (target.kind()) {
            case UNKNOWN -> new Classified(BuildIndex.Status.UNKNOWN, null, 0);
            case AIR -> new Classified(BuildIndex.Status.AIR_TARGET, null, 0);
            case BLOCK -> {
                if (perBlock <= 0) yield new Classified(BuildIndex.Status.AIR_TARGET, null, 0);
                String item = target.block().item();
                yield target.block().id().equals(world.id())
                    ? new Classified(BuildIndex.Status.MATCHES, item, Math.max(0, Math.min(perBlock, worldPerBlock) - 1),
                        worldPerBlock < perBlock)
                    : new Classified(BuildIndex.Status.MISSING, item, 0);
            }
        };
    }

    /**
     * The materials the index knows to be short somewhere, which alone may make a material due (ruling R31): those with
     * a missing position, and those whose need sits in partly filled positions only (deferred m1).
     */
    public static Set<String> knownMissing(BuildIndex index, PartlyPlaced partly) {
        Set<String> known = new TreeSet<>(index.remaining(false).keySet());
        known.addAll(partly.materials());
        return Set.copyOf(known);
    }

    public static Map<String, Long> totals(List<Counted> wholeBuild) {
        Map<String, Long> m = new TreeMap<>();
        for (Counted c : wholeBuild) {
            if (c.perBlock() <= 0 || c.blocks() <= 0 || c.item().equals(StateItems.NO_ITEM)) continue;
            m.merge(c.item(), c.perBlock() * c.blocks(), Long::sum);
        }
        return m;
    }

    /**
     * {@code total − placed − extra − carried}: {@code placed} counts one item per matching position, {@code extra} the
     * items beyond one those positions already hold ({@link PlacedExtra#byMaterial()}).
     */
    public static Map<String, Long> need(Map<String, Long> totals, Map<String, Integer> placed,
                                         Map<String, Integer> extra, Map<String, Integer> carried) {
        Map<String, Integer> built = new TreeMap<>(placed);
        extra.forEach((item, n) -> built.merge(item, n, Integer::sum));
        return MaterialNeeds.need(totals, built, carried, Map.of(), Map.of());
    }
}
