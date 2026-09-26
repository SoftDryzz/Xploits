package com.xploits.pvp.crystal.core;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Everything the adapter measured for one pre-tick (spec §1, P1, P3). No position leaves the core:
 * candidates carry an opaque key, crystals an id.
 *
 * @param tick              this pre-tick's number
 * @param health            your health plus absorption ({@code EntityUtils.getTotalHealth}); "health"
 *                          means this everywhere (P3)
 * @param totems            totems you carry; diagnostic only, the budget never counts them (§1)
 * @param usingItem         using an item or holding the use key (Meteor's pause-on-use, lines 1154-1156)
 * @param mining            breaking a block (pause-on-mine, line 1160)
 * @param lagging           at least 1 s since the last server tick (pause-on-lag, line 1158)
 * @param pauseModuleActive one of the pause-modules is on (line 1159)
 * @param targets           the players seen, other than you
 * @param crystals          the crystals standing within reach, plus those that have just disappeared
 * @param candidates        the places a crystal could go, in {@code BlockIterator} order (ties go to the
 *                          first found)
 */
public record CrystalTick(long tick, double health, int totems, boolean usingItem, boolean mining,
                          boolean lagging, boolean pauseModuleActive, List<TargetView> targets,
                          List<CrystalView> crystals, List<Candidate> candidates) {
    public CrystalTick {
        if (tick < 0) throw new IllegalArgumentException("tick " + tick);
        if (!Double.isFinite(health) || health < 0) throw new IllegalArgumentException("health " + health);
        if (totems < 0) throw new IllegalArgumentException("totems " + totems);
        targets = List.copyOf(targets);
        crystals = List.copyOf(crystals);
        candidates = List.copyOf(candidates);
        Set<String> names = new HashSet<>();
        for (TargetView t : targets) {
            if (!names.add(t.name())) throw new IllegalArgumentException("target twice: " + t.name());
        }
        SelfBudget.checkCrystals(tick, crystals);
        Set<Long> spots = new HashSet<>();
        for (Candidate c : candidates) {
            if (!spots.add(c.pos())) throw new IllegalArgumentException("candidate twice");
        }
    }
}
