package com.xploits.pvp.shell.core;

import com.xploits.pvp.crystal.core.SelfBudget;

import java.util.Optional;

/**
 * Breaking an opponent's crystal next to us (surround++ spec §6.4): in a mined gap, at our head, a block beyond. It is
 * broken at once, the most dangerous first, if its explosion leaves us at or above {@link SelfBudget#FLOOR}, or lands
 * inside our own hurt window ({@link HurtWindow#swallows}). Otherwise it is left: breaking it ourselves would take us
 * below the floor. crystal-aura++'s own crystals are left to it.
 */
public final class CrystalBreaker {
    /** How far from our feet block, horizontally, a crystal counts as next to us; from one below to two above. */
    public static final int NEAR = 2;

    private CrystalBreaker() {
    }

    public static Optional<StandingCrystal> choose(ShellSnapshot s, DamageOracle oracle) {
        if (!s.settings().breakCrystals()) return Optional.empty();
        StandingCrystal best = null;
        double bestDamage = 0;
        for (StandingCrystal crystal : s.crystals()) {
            if (crystal.ours() || !near(crystal.cell())) continue;
            double damage = damage(oracle, crystal.cell());
            if (damage < Threat.MIN_DANGER) continue;
            boolean safe = s.health() - damage >= SelfBudget.FLOOR || s.window().swallows(damage, s.pingTicks());
            if (safe && damage > bestDamage) {
                best = crystal;
                bestDamage = damage;
            }
        }
        return Optional.ofNullable(best);
    }

    static boolean near(Cell c) {
        return Math.abs(c.x()) <= NEAR && Math.abs(c.z()) <= NEAR && c.y() >= -1 && c.y() <= NEAR;
    }

    private static double damage(DamageOracle oracle, Cell cell) {
        double damage = oracle.exact(cell);
        return Double.isFinite(damage) && damage >= 0 ? damage : ThreatMap.UNKNOWN_DAMAGE;
    }
}
