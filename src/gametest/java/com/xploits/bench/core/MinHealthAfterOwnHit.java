package com.xploits.bench.core;

import com.xploits.pvp.recorder.core.CombatEvent.AttackerKind;
import com.xploits.pvp.recorder.core.FightRecord.DamageEvent;

import java.util.List;
import java.util.OptionalDouble;
import java.util.Set;

/**
 * Task A1 requirement 3: our health plus absorption right after each hit from one of OUR OWN crystals over
 * a fight-mode run — the damage events whose {@link DamageEvent#by()} is {@link AttackerKind#SELF} (the
 * same filter {@code MeasureRun.selfDamage} uses for self damage) — the lowest such value over the run.
 * That is what the self-budget's reserve promises even while the opponent is also hitting us. Empty when
 * the run had no hit from one of our own crystals. Pure: it only reads {@link DamageEvent#by()} and
 * {@link DamageEvent#after()}, in any order.
 *
 * <p><b>Note for whoever writes A3's reserve-acceptance rule (fix round 1):</b> {@code DamageLedger}'s
 * lethal-hit convention (pre-existing, unmodified by task A1) records {@code after = 0} for a SELF hit that
 * pops or kills us — not the real post-totem survived health (the totem's own ~1 HP plus its Absorption II).
 * A run where our own crystal triggered our own pop therefore reports {@code min_health_after_own_hit = 0},
 * which is that recording convention, not a sign the reserve failed to hold; do not read a 0 here the same
 * way as a low-but-nonzero value.
 */
public final class MinHealthAfterOwnHit {
    private MinHealthAfterOwnHit() {
    }

    public static OptionalDouble of(List<DamageEvent> damage) {
        return of(damage, Set.of());
    }

    /**
     * Task B0b: without the finishing hits, given as indexes into {@code damage} ({@link
     * FinishingTracker.Resolution#excluded}, one event per blow at most): the reserve rule applies to ordinary
     * own hits only, since a finishing blow may take us below it on purpose. Excluded by event, never by tick.
     */
    public static OptionalDouble of(List<DamageEvent> damage, Set<Integer> excludedIndexes) {
        return java.util.stream.IntStream.range(0, damage.size())
            .filter(i -> damage.get(i).by() == AttackerKind.SELF && !excludedIndexes.contains(i))
            .mapToDouble(i -> damage.get(i).after()).min();
    }
}
