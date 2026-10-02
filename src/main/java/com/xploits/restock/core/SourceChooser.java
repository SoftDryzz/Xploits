package com.xploits.restock.core;

import com.xploits.printer.core.Point;
import com.xploits.printer.core.Pos;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The source of one material (restock spec §3 "Sources and choice"): same dimension, within {@code maxDistance} of
 * {@code from} (a straight line to the container's centre, the limit included), neither unusable this session nor
 * stale for this material. A source known to hold it loose comes first, then one known to hold it only inside its shulker boxes when {@code nested} (ruling R40: a loose stack is one click, a box is a carry and an unpack at the build), then a marked container whose contents nobody has seen; then the nearest; ties by x, then y, then z.
 */
public final class SourceChooser {
    private SourceChooser() {
    }

    public static Optional<Source> nearest(List<Source> sources, String material, String dimension, Point from,
                                           double maxDistance, Set<Pos> unusable, Set<Pos> stale, boolean nested) {
        double max = maxDistance * maxDistance;
        Source best = null;
        int bestRank = Integer.MAX_VALUE;
        double bestD = Double.POSITIVE_INFINITY;
        for (Source s : sources) {
            if (!s.dimension().equals(dimension)) continue;
            if (unusable.contains(s.container()) || stale.contains(s.container())) continue;
            int rank;
            if (!s.known()) {
                rank = 2;
            } else if (s.loose().getOrDefault(material, 0) > 0) {
                rank = 0;
            } else if (nested && s.nested().getOrDefault(material, 0) > 0) {
                rank = 1;
            } else {
                continue;
            }
            double d = s.container().distanceSq(from);
            if (d > max) continue;
            boolean better = rank < bestRank
                || (rank == bestRank && (d < bestD || (d == bestD && before(s.container(), best.container()))));
            if (better) {
                best = s;
                bestRank = rank;
                bestD = d;
            }
        }
        return Optional.ofNullable(best);
    }

    /**
     * Ruling R54 (and R60): a container restock just opened that held {@code material} only inside its shulker boxes
     * ({@code loose}, {@code nested}: what that visit saw) and was found stale while no box can be carried
     * ({@code carry} false: no free hotbar slot and one more) is passed over, not marked stale — {@link #nearest} with
     * {@code nested} false skips it, and chooses it again once a box can be carried. With carry room it is stale like
     * any other (a box restock may not carry, pre-flight 17-3: no loop either), and a source that failed for another
     * reason is noted as before.
     */
    public static boolean passedOver(RestockTrip.Failure failure, boolean carry, Map<String, Integer> loose,
                                     Map<String, Integer> nested, String material) {
        return failure == RestockTrip.Failure.STALE && !carry && loose.getOrDefault(material, 0) <= 0
            && nested.getOrDefault(material, 0) > 0;
    }

    private static boolean before(Pos a, Pos b) {
        if (a.x() != b.x()) return a.x() < b.x();
        if (a.y() != b.y()) return a.y() < b.y();
        return a.z() < b.z();
    }
}
