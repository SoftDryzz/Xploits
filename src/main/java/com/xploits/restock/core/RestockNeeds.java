package com.xploits.restock.core;

import com.xploits.printer.core.BlockFacts;
import com.xploits.printer.core.BuildIndex;
import com.xploits.printer.core.MaterialNeeds;
import com.xploits.printer.core.Target;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

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

    /** A position as restock's index holds it; {@code material} only for {@code MATCHES} and {@code MISSING}. */
    public record Classified(BuildIndex.Status status, String material) {
    }

    private RestockNeeds() {
    }

    public static Classified classify(Target target, int perBlock, BlockFacts world) {
        return switch (target.kind()) {
            case UNKNOWN -> new Classified(BuildIndex.Status.UNKNOWN, null);
            case AIR -> new Classified(BuildIndex.Status.AIR_TARGET, null);
            case BLOCK -> {
                if (perBlock <= 0) yield new Classified(BuildIndex.Status.AIR_TARGET, null);
                String item = target.block().item();
                yield target.block().id().equals(world.id()) ? new Classified(BuildIndex.Status.MATCHES, item)
                    : new Classified(BuildIndex.Status.MISSING, item);
            }
        };
    }

    public static Map<String, Long> totals(List<Counted> wholeBuild) {
        Map<String, Long> m = new TreeMap<>();
        for (Counted c : wholeBuild) {
            if (c.perBlock() <= 0 || c.blocks() <= 0 || c.item().equals(StateItems.NO_ITEM)) continue;
            m.merge(c.item(), c.perBlock() * c.blocks(), Long::sum);
        }
        return m;
    }

    public static Map<String, Long> need(Map<String, Long> totals, Map<String, Integer> placed,
                                         Map<String, Integer> carried) {
        return MaterialNeeds.need(totals, placed, carried, Map.of(), Map.of());
    }
}
