package com.xploits.pvp.crystal.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Test shortcut to build what {@link CrystalBrain} is fed, giving only what a test is about and leaving the
 * rest at neutral values: one enemy at 3 blocks with full health and no armour that could wear out, you
 * at full health holding end crystals in the main hand with a weapon elsewhere in the hotbar, no effects,
 * no pause, crystals within break range at 3 blocks. Every number is exact in binary, so each boundary is
 * the real one.
 */
final class Crystals {
    private Crystals() {}

    static final String ENEMY = "enemy";
    /** Where our feet are in every tick unless a test moves them. */
    static final Feet FEET = new Feet(0, 64, 0);
    /** Meteor's defaults and the Safety group's: the budget on. */
    static final CrystalSettings DEFAULTS = CrystalSettings.defaults();
    /** Meteor's defaults with the budget off: Meteor's rules only. */
    static final CrystalSettings METEOR = DEFAULTS.toBuilder().selfBudget(false).build();

    /** End crystals in the main hand, a weapon elsewhere in the hotbar, no effects. */
    static final CrystalTick.Hands HANDS = hands(true, true, false);

    static CrystalTick.Hands hands(boolean crystalsInHotbar, boolean mainHand, boolean offhand) {
        return new CrystalTick.Hands(crystalsInHotbar, mainHand, offhand, false, false,
            CrystalTick.Hands.NO_EFFECT, CrystalTick.Hands.NO_EFFECT, false, true);
    }

    static CrystalTick.Hands with(CrystalTick.Hands h, boolean gapple, boolean bow) {
        return new CrystalTick.Hands(h.crystalsInHotbar(), h.mainHandCrystals(), h.offhandCrystals(), gapple, bow,
            h.weaknessAmplifier(), h.strengthAmplifier(), h.mainHandBreaksWeakened(), h.hotbarBreaksWeakened(),
            h.shielding());
    }

    /** The same hands, blocking with a shield. */
    static CrystalTick.Hands shielding(CrystalTick.Hands h) {
        return new CrystalTick.Hands(h.crystalsInHotbar(), h.mainHandCrystals(), h.offhandCrystals(), h.gappleInHand(),
            h.bowInHand(), h.weaknessAmplifier(), h.strengthAmplifier(), h.mainHandBreaksWeakened(),
            h.hotbarBreaksWeakened(), true);
    }

    static CrystalTick.Hands weakened(int weakness, int strength, boolean mainHandBreaks, boolean hotbarBreaks) {
        return new CrystalTick.Hands(true, true, false, false, false, weakness, strength, mainHandBreaks, hotbarBreaks);
    }

    static TargetView enemy() {
        return player(ENEMY, 3, 20);
    }

    static TargetView player(String name, double distance, double health) {
        return new TargetView(name, distance, health, TargetView.NO_ARMOR, false, true, false);
    }

    /** A crystal on base {@code 1000 + id}, at 3 blocks, in break range, dealing {@code damage} to the enemy. */
    static CrystalSeen crystal(int id, double damage, double self) {
        return crystal(id, 1000L + id, damage, self);
    }

    static CrystalSeen crystal(int id, long pos, double damage, double self) {
        return new CrystalSeen(id, pos, Map.of(ENEMY, damage), self, 3, true);
    }

    static CrystalSeen crystal(int id, long pos, Map<String, Double> damage, double self) {
        return new CrystalSeen(id, pos, damage, self, 3, true);
    }

    /** As {@link #crystal(int, double, double)}, with the raw damage to you ({@link RawExplosion}). */
    static CrystalSeen crystalRaw(int id, double damage, double self, double raw) {
        return crystal(id, 1000L + id, damage, self, raw);
    }

    static CrystalSeen crystal(int id, long pos, double damage, double self, double raw) {
        return new CrystalSeen(id, pos, Map.of(ENEMY, damage), self, 3, true, raw);
    }

    /** The same crystal measured again, now dealing this damage to the enemy. */
    static CrystalSeen dealing(CrystalSeen c, double damage) {
        return new CrystalSeen(c.id(), c.pos(), Map.of(ENEMY, damage), c.selfDamage(), c.distance(), c.inBreakRange(),
            c.rawSelfDamage());
    }

    static CrystalSeen outOfBreakRange(CrystalSeen c) {
        return new CrystalSeen(c.id(), c.pos(), c.targetDamage(), c.selfDamage(), c.distance(), false, c.rawSelfDamage());
    }

    static CrystalSeen at(CrystalSeen c, double distance) {
        return new CrystalSeen(c.id(), c.pos(), c.targetDamage(), c.selfDamage(), distance, c.inBreakRange(),
            c.rawSelfDamage());
    }

    /** A spot in range with nothing in its box, dealing {@code damage} to the enemy. */
    static Candidate spot(long pos, double damage, double self) {
        return new Candidate(pos, Map.of(ENEMY, damage), self, true, Set.of(), false);
    }

    /** As {@link #spot(long, double, double)}, with the raw damage to you ({@link RawExplosion}). */
    static Candidate spot(long pos, double damage, double self, double raw) {
        return new Candidate(pos, Map.of(ENEMY, damage), self, true, Set.of(), false, raw);
    }

    static Candidate spot(long pos, Map<String, Double> damage, double self) {
        return new Candidate(pos, damage, self, true, Set.of(), false);
    }

    static Candidate boxed(Candidate c, Set<Integer> crystals, boolean otherEntity) {
        return new Candidate(c.pos(), c.targetDamage(), c.selfDamage(), c.inRange(), crystals, otherEntity,
            c.rawSelfDamage());
    }

    static Candidate outOfRange(Candidate c) {
        return new Candidate(c.pos(), c.targetDamage(), c.selfDamage(), false, c.crystalsInBox(), c.otherEntityInBox(),
            c.rawSelfDamage());
    }

    static Tick tick(long n) {
        return new Tick(n);
    }

    /** A {@link CrystalTick} with neutral values; each setter changes one. */
    static final class Tick {
        private final long n;
        private double health = 20;
        private int totems;
        private boolean usingItem;
        private boolean mining;
        private boolean lagging;
        private boolean pauseModule;
        private CrystalTick.Hands hands = HANDS;
        private Feet feet = FEET;
        private List<TargetView> targets = List.of(enemy());
        private final List<CrystalSeen> crystals = new ArrayList<>();
        private final List<Candidate> candidates = new ArrayList<>();

        private Tick(long n) {
            this.n = n;
        }

        Tick health(double v) { health = v; return this; }
        Tick totems(int v) { totems = v; return this; }
        Tick usingItem() { usingItem = true; return this; }
        Tick mining() { mining = true; return this; }
        Tick lagging() { lagging = true; return this; }
        Tick pauseModule() { pauseModule = true; return this; }
        Tick hands(CrystalTick.Hands v) { hands = v; return this; }
        Tick feet(Feet v) { feet = v; return this; }
        Tick targets(TargetView... v) { targets = List.of(v); return this; }
        Tick crystals(CrystalSeen... v) { crystals.addAll(List.of(v)); return this; }
        Tick crystals(List<CrystalSeen> v) { crystals.addAll(v); return this; }
        Tick candidates(Candidate... v) { candidates.addAll(List.of(v)); return this; }

        CrystalTick build() {
            return new CrystalTick(n, health, totems, usingItem, mining, lagging, pauseModule, hands, targets,
                crystals, candidates, feet);
        }
    }
}
