package com.xploits.pvp.recorder.core;

import com.xploits.pvp.recorder.core.CombatEvent.AttackerKind;
import com.xploits.pvp.recorder.core.CombatEvent.SelfDamaged;
import com.xploits.pvp.recorder.core.FightRecord.DamageEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Turns your own health drops into damage events. A damage packet says what hit you and who, never how
 * much; the health update says how much, never what. This pairs them.
 *
 * <ul>
 *   <li>A drop larger than {@link #NOISE} is split evenly among the hits noted within the last
 *   {@link #WINDOW_TICKS} ticks. Even is a documented bias: two hits landing together cannot be told apart.</li>
 *   <li>A drop with no pending hit, within {@link #INVULNERABILITY_TICKS} of the last matched hit, is
 *   {@link DamageKind#UNSEEN}: the server sends no packet for a hit inside the invulnerability window, so
 *   who dealt it is never guessed. Any other drop with no hit is ignored (absorption running out, hunger).</li>
 *   <li>On a pop, the pending hits are lethal: each gets an even share of your health before it and ends at
 *   0, and the baseline moves to your health after the totem. With no pending hit the pop is one UNSEEN
 *   lethal hit: the damage packet always comes before the pop, so a pop without one was a hit inside the
 *   window. For {@link #WINDOW_TICKS} ticks after a pop a drop with no pending hit is the totem's health
 *   arriving late, not a hit: the baseline follows it silently.</li>
 *   <li>A pending hit that never produces a drop is discarded once it is older than the window.</li>
 * </ul>
 *
 * <p>Feed it in this order each tick: {@link #noteHit} for every hit of the tick, then {@link #observe};
 * on the tick you die, {@link #lethal} instead of {@link #observe}.
 */
public final class DamageLedger {
    /** Damage and health updates land one or two client ticks apart. */
    public static final int WINDOW_TICKS = 3;
    public static final int INVULNERABILITY_TICKS = 10;
    public static final double NOISE = 0.05;

    private record Pending(long tick, SelfDamaged hit) {
    }

    private final List<Pending> pending = new ArrayList<>();
    private double baseline;
    private boolean started;
    private boolean matchedAny;
    private long lastMatchedHit;
    private boolean popped;
    private long lastPop;

    /** Starts over from this health: use the health before the first hit of the fight. */
    public void start(double health) {
        pending.clear();
        baseline = health;
        started = true;
        matchedAny = false;
        popped = false;
    }

    /** A damage packet about you arrived on this tick. */
    public void noteHit(long tick, SelfDamaged hit) {
        pending.add(new Pending(tick, Objects.requireNonNull(hit, "hit")));
    }

    /** Your health (with absorption) this tick, and whether one of your totems popped on it. */
    public List<DamageEvent> observe(long tick, double health, boolean poppedThisTick) {
        health = Math.max(0, health);
        if (!started) {
            // Never started: this is the first health seen, and hits noted before it are kept.
            baseline = health;
            started = true;
        }
        expire(tick);
        if (poppedThisTick) {
            List<DamageEvent> events = lethalHits(tick);
            baseline = health;
            popped = true;
            lastPop = tick;
            return events;
        }
        double drop = baseline - health;
        if (drop <= NOISE) {
            if (health > baseline) baseline = health;
            return List.of();
        }
        List<DamageEvent> events;
        if (!pending.isEmpty()) {
            events = split(health);
        } else if (popped && tick - lastPop <= WINDOW_TICKS) {
            events = List.of();
        } else if (matchedAny && tick - lastMatchedHit <= INVULNERABILITY_TICKS) {
            events = List.of(new DamageEvent(tick, DamageKind.UNSEEN, AttackerKind.NONE, null, baseline, health, false));
        } else {
            events = List.of();
        }
        baseline = health;
        return events;
    }

    /** You died on this tick: the pending hits killed you, or an unseen one did if there are none. */
    public List<DamageEvent> lethal(long tick) {
        expire(tick);
        List<DamageEvent> events = lethalHits(tick);
        baseline = 0;
        return events;
    }

    private void expire(long tick) {
        pending.removeIf(p -> tick - p.tick() > WINDOW_TICKS);
    }

    private List<DamageEvent> split(double health) {
        int n = pending.size();
        double share = (baseline - health) / n;
        List<DamageEvent> events = new ArrayList<>(n);
        double before = baseline;
        for (int i = 0; i < n; i++) {
            double after = i == n - 1 ? health : before - share;
            events.add(event(pending.get(i), before, after, false));
            before = after;
        }
        matched();
        return List.copyOf(events);
    }

    private List<DamageEvent> lethalHits(long tick) {
        if (pending.isEmpty()) {
            return List.of(new DamageEvent(tick, DamageKind.UNSEEN, AttackerKind.NONE, null, Math.max(0, baseline), 0, true));
        }
        double share = Math.max(0, baseline) / pending.size();
        List<DamageEvent> events = new ArrayList<>(pending.size());
        for (Pending p : pending) events.add(event(p, share, 0, true));
        matched();
        return List.copyOf(events);
    }

    private void matched() {
        for (Pending p : pending) {
            if (!matchedAny || p.tick() > lastMatchedHit) lastMatchedHit = p.tick();
            matchedAny = true;
        }
        pending.clear();
    }

    private static DamageEvent event(Pending p, double before, double after, boolean lethal) {
        SelfDamaged hit = p.hit();
        return new DamageEvent(p.tick(), hit.kind(), hit.by(), hit.attacker(), before, after, lethal);
    }
}
