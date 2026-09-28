package com.xploits.bench.core;

import com.xploits.pvp.recorder.core.CombatEvent.AttackerKind;
import com.xploits.pvp.recorder.core.FightRecord.DamageEvent;

import java.util.List;
import java.util.OptionalDouble;

/**
 * Task A1 requirement 3: our health plus absorption right after each hit from one of OUR OWN crystals over
 * a fight-mode run — the damage events whose {@link DamageEvent#by()} is {@link AttackerKind#SELF} (the
 * same filter {@code MeasureRun.selfDamage} uses for self damage) — the lowest such value over the run.
 * That is what the self-budget's reserve promises even while the opponent is also hitting us. Empty when
 * the run had no hit from one of our own crystals. Pure: it only reads {@link DamageEvent#by()} and
 * {@link DamageEvent#after()}, in any order.
 */
public final class MinHealthAfterOwnHit {
    private MinHealthAfterOwnHit() {
    }

    public static OptionalDouble of(List<DamageEvent> damage) {
        return damage.stream().filter(e -> e.by() == AttackerKind.SELF).mapToDouble(DamageEvent::after).min();
    }
}
