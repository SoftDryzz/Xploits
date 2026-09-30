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
                    if (ownIds.contains(packets.get(k).directId())) ours.add(events.get(k));
                }
            } else if (packets.stream().anyMatch(p -> ownIds.contains(p.directId()))) {
                ours.addAll(events);
            }
        }
        return ours;
    }

    /**
     * 0.8.0: the crystals that are ours for {@link #indexes}: those crystal-aura++ remembers as its own ({@code
     * brainOwn}) and those the recorder claimed from our placements ({@code claimed}), less every crystal a script of
     * the bench spawned ({@code spawnedByScripts}: the opponent's, never ours). The recorder claims a crystal that lands
     * on a cell we just placed on, and our aura sends its placement several times until a crystal appears, so a claim
     * left over from that burst could take the opponent's next crystal on the same cell, and its hit on us counted as
     * ours (the release bench's one reserve failure, 2026-09-30). A crystal of ours set off by the opponent's autobreak
     * is still ours: no script spawned it. The recorder's own attribution ({@link AttackerKind#SELF}) is not touched.
     */
    public static Set<Integer> ownIds(Set<Integer> brainOwn, Set<Integer> claimed, Set<Integer> spawnedByScripts) {
        Set<Integer> ids = new HashSet<>(brainOwn);
        ids.addAll(claimed);
        ids.removeAll(spawnedByScripts);
        return Set.copyOf(ids);
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
