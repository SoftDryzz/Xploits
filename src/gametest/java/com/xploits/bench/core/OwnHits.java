package com.xploits.bench.core;

import com.xploits.pvp.recorder.core.CombatEvent.AttackerKind;
import com.xploits.pvp.recorder.core.DamageKind;
import com.xploits.pvp.recorder.core.FightRecord.DamageEvent;
import com.xploits.pvp.recorder.core.HitSource;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Task C2 (I2): which damage events are OURS for the reserve rules. The recorder blames the player whose
 * explosion it was, so a crystal of ours that the sparring's autobreak set off before our own hit landed is
 * given to the sparring and never reaches the rules. An event is ours when the recorder says so
 * ({@link AttackerKind#SELF}) or when the direct source of its packet was a crystal we placed.
 *
 * <p>The recorder logs one {@link HitSource} per damage packet about us, and the ledger makes one event per
 * packet that produced a drop (none for a packet that did not, and an {@link DamageKind#UNSEEN} event for a drop
 * with no packet), all in the same order, so on one tick the events (unseen ones aside) line up with the
 * packets. When they do not line up (a packet produced no drop) the events of that tick are ours if any packet
 * of the tick was from one of our crystals: a foreign hit may be over-counted, one of ours is never hidden.
 * Pure: no Minecraft types.
 */
public final class OwnHits {
    private OwnHits() {
    }

    /** The indexes into {@code damage} of the events that are ours: {@code ownIds} are the crystals we placed. */
    public static Set<Integer> indexes(List<DamageEvent> damage, List<HitSource> sources, Set<Integer> ownIds) {
        return indexes(damage, sources, ownIds, Set.of());
    }

    /**
     * The same, also recognising a crystal by the spot: a packet whose direct crystal stands on a cell where we
     * sent a placement ({@code placedCells}, packed block positions, in memory only) is ours even when its id was
     * never seen (the opponent broke it in the tick it appeared). A foreign crystal on one of our cells is
     * over-counted, the safe direction.
     */
    public static Set<Integer> indexes(List<DamageEvent> damage, List<HitSource> sources, Set<Integer> ownIds,
                                       Set<Long> placedCells) {
        Set<Integer> ours = selfIndexes(damage);
        Set<Long> ticks = new HashSet<>();
        for (DamageEvent e : damage) ticks.add(e.tick());
        for (long tick : ticks) {
            List<HitSource> packets = new ArrayList<>();
            for (HitSource s : sources) {
                if (s.tick() == tick) packets.add(s);
            }
            if (packets.isEmpty()) continue;
            List<Integer> events = new ArrayList<>();
            for (int i = 0; i < damage.size(); i++) {
                if (damage.get(i).tick() == tick && damage.get(i).kind() != DamageKind.UNSEEN) events.add(i);
            }
            if (events.size() == packets.size()) {
                for (int k = 0; k < events.size(); k++) {
                    if (isOurs(packets.get(k), ownIds, placedCells)) ours.add(events.get(k));
                }
            } else if (packets.stream().anyMatch(p -> isOurs(p, ownIds, placedCells))) {
                ours.addAll(events);
            }
        }
        return ours;
    }

    private static boolean isOurs(HitSource p, Set<Integer> ownIds, Set<Long> placedCells) {
        return ownIds.contains(p.directId()) || (p.cell() != HitSource.NO_CELL && placedCells.contains(p.cell()));
    }

    /** The events the recorder itself gave to us ({@link AttackerKind#SELF}). */
    public static Set<Integer> selfIndexes(List<DamageEvent> damage) {
        Set<Integer> self = new HashSet<>();
        for (int i = 0; i < damage.size(); i++) {
            if (damage.get(i).by() == AttackerKind.SELF) self.add(i);
        }
        return self;
    }
}
