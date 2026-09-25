package com.xploits.pvp.recorder.core;

import com.xploits.pvp.core.CombatPosture;
import com.xploits.pvp.core.CombatState;
import com.xploits.pvp.recorder.core.CombatEvent.AttackerKind;

import java.util.List;
import java.util.Objects;

/**
 * One finished fight, exactly what is written to disk (schema {@value #SCHEMA}); the component names are
 * the JSON field names. There is no position anywhere, on purpose: distances, counts, booleans and names.
 * Times are epoch milliseconds; {@code second} fields count game seconds (20 ticks) from the start of the fight.
 *
 * <p>{@code durationSeconds} is the game seconds the fight covers, begun seconds included, and there is
 * exactly one sample per such second: {@code samples.size() == durationSeconds}, so
 * {@code autoPvpSeconds <= durationSeconds} always. It is not {@code endedAt - startedAt}: that is the wall
 * clock, which a lagging client lets run ahead of the game ticks.
 *
 * <p>{@code profile} (the style profile active when the fight opened) and {@code profileChanges} are the
 * only optional fields in schema {@value #SCHEMA}: a file saved before profiles existed has neither, and
 * loads with {@code profile} null and {@code profileChanges} empty.
 */
public record FightRecord(int schema, String addonVersion, long startedAt, long endedAt, int durationSeconds,
                          FightOutcome outcome, boolean truncated, FightMode mode, int autoPvpSeconds,
                          List<Opponent> opponents, int maxHostilesNear, SelfTotals self, List<DamageEvent> damage,
                          int damageEventsDropped, List<Sample> samples, List<String> modulesAtStart,
                          List<ModuleChange> moduleChanges, List<PhaseChange> phases, String profile,
                          List<ProfileChange> profileChanges) {
    public static final int SCHEMA = 1;

    public FightRecord {
        Objects.requireNonNull(addonVersion, "addonVersion");
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(self, "self");
        if (endedAt < startedAt) throw new IllegalArgumentException("ended " + endedAt + " before it started " + startedAt);
        requireCount("durationSeconds", durationSeconds);
        requireCount("autoPvpSeconds", autoPvpSeconds);
        requireCount("maxHostilesNear", maxHostilesNear);
        requireCount("damageEventsDropped", damageEventsDropped);
        opponents = List.copyOf(opponents);
        damage = List.copyOf(damage);
        samples = List.copyOf(samples);
        modulesAtStart = List.copyOf(modulesAtStart);
        moduleChanges = List.copyOf(moduleChanges);
        phases = List.copyOf(phases);
        profileChanges = List.copyOf(profileChanges);
    }

    /** Old shape from before profiles existed: no {@link #profile}, no {@link #profileChanges}. */
    public FightRecord(int schema, String addonVersion, long startedAt, long endedAt, int durationSeconds,
                       FightOutcome outcome, boolean truncated, FightMode mode, int autoPvpSeconds,
                       List<Opponent> opponents, int maxHostilesNear, SelfTotals self, List<DamageEvent> damage,
                       int damageEventsDropped, List<Sample> samples, List<String> modulesAtStart,
                       List<ModuleChange> moduleChanges, List<PhaseChange> phases) {
        this(schema, addonVersion, startedAt, endedAt, durationSeconds, outcome, truncated, mode, autoPvpSeconds,
            opponents, maxHostilesNear, self, damage, damageEventsDropped, samples, modulesAtStart, moduleChanges,
            phases, null, List.of());
    }

    /** A player you fought: someone who hit you, was hit by you, popped, died or was auto-pvp's target. */
    public record Opponent(String name, int pops, boolean died, int hitsOnYou, double damageToYou, int hitsByYou) {
        public Opponent {
            Objects.requireNonNull(name, "name");
            requireCount("pops", pops);
            requireCount("hitsOnYou", hitsOnYou);
            requireCount("hitsByYou", hitsByYou);
        }
    }

    /**
     * Your totals. {@code enemyCrystalsNear} is the crystals that appeared near you minus those you placed,
     * never below zero.
     */
    public record SelfTotals(int pops, int totemsStart, int totemsEnd, boolean offhandTotemEnd, double damageTaken,
                             int crystalsPlaced, int crystalsBroken, int attacks, int enemyCrystalsNear) {
        public SelfTotals {
            requireCount("pops", pops);
            requireCount("totemsStart", totemsStart);
            requireCount("totemsEnd", totemsEnd);
            requireCount("crystalsPlaced", crystalsPlaced);
            requireCount("crystalsBroken", crystalsBroken);
            requireCount("attacks", attacks);
            requireCount("enemyCrystalsNear", enemyCrystalsNear);
        }
    }

    /**
     * One hit you took: your health {@code before} and {@code after} it, as the {@code DamageLedger}
     * worked them out. {@code attacker} is set only when {@code by == PLAYER}; {@code lethal} marks a hit
     * that popped a totem or killed you.
     */
    public record DamageEvent(long tick, DamageKind kind, AttackerKind by, String attacker, double before,
                              double after, boolean lethal) {
        public DamageEvent {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(by, "by");
            if ((by == AttackerKind.PLAYER) != (attacker != null)) {
                throw new IllegalArgumentException("an attacker name goes with PLAYER and only with it: " + by + "/" + attacker);
            }
            if (kind == DamageKind.UNSEEN && by != AttackerKind.NONE) throw new IllegalArgumentException("an unseen hit has no attacker");
            if (!(after >= 0) || !(before >= after)) throw new IllegalArgumentException("health " + before + " -> " + after);
        }
    }

    /**
     * Your state once per second. {@code nearestHostile} is null when no hostile was in engage range;
     * {@code placed}, {@code broken} and {@code spawnedNear} count crystals during that second only.
     */
    public record Sample(int second, double health, double incoming, int totems, boolean offhandTotem, int crystals,
                         int obsidian, int gapples, int armor, Double nearestHostile, int hostilesNear, boolean inHole,
                         boolean gliding, boolean autoPvp, int placed, int broken, int spawnedNear) {
        public Sample {
            requireCount("second", second);
            requireCount("hostilesNear", hostilesNear);
            requireCount("placed", placed);
            requireCount("broken", broken);
            requireCount("spawnedNear", spawnedNear);
        }
    }

    /** A module turned on or off during the fight. */
    public record ModuleChange(int second, String module, boolean on) {
        public ModuleChange {
            requireCount("second", second);
            Objects.requireNonNull(module, "module");
        }
    }

    /** auto-pvp's phase, posture or target changed; {@code target} is null when it had none. */
    public record PhaseChange(int second, CombatState state, CombatPosture posture, String target) {
        public PhaseChange {
            requireCount("second", second);
            Objects.requireNonNull(state, "state");
            Objects.requireNonNull(posture, "posture");
        }
    }

    /** The active style profile changed during the fight: {@code name} is what it changed to. */
    public record ProfileChange(int second, String name) {
        public ProfileChange {
            requireCount("second", second);
            Objects.requireNonNull(name, "name");
        }
    }

    private static void requireCount(String field, long value) {
        if (value < 0) throw new IllegalArgumentException(field + " is negative: " + value);
    }
}
