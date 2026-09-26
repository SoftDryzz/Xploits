package com.xploits.pvp.crystal.core;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Everything the adapter measured for one pre-tick (spec §1, P1, P3). No position leaves the core:
 * candidates and crystals carry an opaque key, crystals also an id.
 *
 * @param tick              this pre-tick's number
 * @param health            your health plus absorption ({@code EntityUtils.getTotalHealth}); "health"
 *                          means this everywhere (P3)
 * @param totems            totems you carry; diagnostic only, the budget never counts them (§1)
 * @param usingItem         using an item or holding the use key (Meteor's pause-on-use, lines 1154-1156)
 * @param mining            breaking a block (pause-on-mine, line 1160)
 * @param lagging           at least 1 s since the last server tick (pause-on-lag, line 1158)
 * @param pauseModuleActive one of the pause-modules is on (line 1159)
 * @param hands             what you hold and carry, and your effects, as Meteor reads them
 * @param targets           the players seen, other than you, in the world's entity order (Meteor sums
 *                          the damage in that order, in {@code float})
 * @param crystals          every end crystal standing in the world, in the world's entity order (Meteor's
 *                          break choice keeps the first of equal ones); {@link CrystalBrain} keeps what
 *                          we did to each and remembers the ones that have just disappeared
 * @param candidates        the places a crystal could go, in {@code BlockIterator} order (ties go to the
 *                          first found); empty for {@link CrystalBrain#breakPhase}, which is measured
 *                          before the scan
 */
public record CrystalTick(long tick, double health, int totems, boolean usingItem, boolean mining,
                          boolean lagging, boolean pauseModuleActive, Hands hands, List<TargetView> targets,
                          List<CrystalSeen> crystals, List<Candidate> candidates) {
    public CrystalTick {
        if (tick < 0) throw new IllegalArgumentException("tick " + tick);
        if (!Double.isFinite(health) || health < 0) throw new IllegalArgumentException("health " + health);
        if (totems < 0) throw new IllegalArgumentException("totems " + totems);
        Objects.requireNonNull(hands, "hands");
        targets = List.copyOf(targets);
        crystals = List.copyOf(crystals);
        candidates = List.copyOf(candidates);
        Set<String> names = new HashSet<>();
        for (TargetView t : targets) {
            if (!names.add(t.name())) throw new IllegalArgumentException("target twice: " + t.name());
        }
        Set<Integer> ids = new HashSet<>();
        for (CrystalSeen c : crystals) {
            if (!ids.add(c.id())) throw new IllegalArgumentException("crystal twice: " + c.id());
        }
        Set<Long> spots = new HashSet<>();
        for (Candidate c : candidates) {
            if (!spots.add(c.pos())) throw new IllegalArgumentException("candidate twice");
        }
    }

    /**
     * Meteor's {@code pause-modules} check (line 1159): whether any of the selected modules is on, which is
     * {@link #pauseModuleActive}. With none selected it never pauses.
     *
     * @param selected the modules in the setting
     * @param on       whether one of them is on
     */
    public static <M> boolean anyOn(Iterable<M> selected, Predicate<? super M> on) {
        for (M module : selected) {
            if (on.test(module)) return true;
        }
        return false;
    }

    /**
     * The hand, inventory and effect facts Meteor decides switching, placing and anti-weakness with.
     * Meteor reads the two hands once, at the start of the pre-tick (lines 693-694).
     *
     * @param crystalsInHotbar       end crystals in either hand or the hotbar ({@code InvUtils.testInHotbar},
     *                               which tests the hands first); without them there is no placing (line 908)
     * @param mainHandCrystals       the main hand holds end crystals (line 919)
     * @param offhandCrystals        the offhand holds end crystals: no-gap-switch does not apply (line 912)
     * @param gappleInHand           either hand holds a golden or an enchanted golden apple (lines 913-916)
     * @param bowInHand              either hand holds a bow (line 918)
     * @param weaknessAmplifier      the Weakness amplifier, or {@link #NO_EFFECT} (line 827)
     * @param strengthAmplifier      the Strength amplifier, or {@link #NO_EFFECT} (line 828)
     * @param mainHandBreaksWeakened the main hand item deals damage to a crystal
     *                               ({@code DamageUtils.getAttackDamage > 0}, lines 833, 877-879)
     * @param hotbarBreaksWeakened   some item in the hands or hotbar does ({@code InvUtils.findInHotbar},
     *                               line 835)
     */
    public record Hands(boolean crystalsInHotbar, boolean mainHandCrystals, boolean offhandCrystals,
                        boolean gappleInHand, boolean bowInHand, int weaknessAmplifier, int strengthAmplifier,
                        boolean mainHandBreaksWeakened, boolean hotbarBreaksWeakened) {
        /** The effect is not active. */
        public static final int NO_EFFECT = -1;

        public Hands {
            if (weaknessAmplifier < NO_EFFECT) throw new IllegalArgumentException("weakness " + weaknessAmplifier);
            if (strengthAmplifier < NO_EFFECT) throw new IllegalArgumentException("strength " + strengthAmplifier);
            if ((mainHandCrystals || offhandCrystals) && !crystalsInHotbar) {
                throw new IllegalArgumentException("crystals in hand but not in the hotbar");
            }
            if (mainHandBreaksWeakened && !hotbarBreaksWeakened) {
                throw new IllegalArgumentException("the main hand breaks but the hotbar does not");
            }
        }

        /** Whether Weakness is active. */
        public boolean weakened() {
            return weaknessAmplifier != NO_EFFECT;
        }

        /** Whether Strength is active. */
        public boolean strengthened() {
            return strengthAmplifier != NO_EFFECT;
        }
    }
}
