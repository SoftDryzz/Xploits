package com.xploits.pvp.crystal.core;

import java.util.Optional;
import java.util.OptionalDouble;

/**
 * The checks every value that comes from the server goes through before it reaches the core. Anarchy servers
 * spoof health, and a broken or hostile server can make the game report NaN, infinities or negative values;
 * the core's records reject those by throwing, which inside a tick handler would crash the game. So each odd
 * value is turned into the cautious reading here:
 * <ul>
 *   <li>a player whose health or squared distance is not a valid number is not a target;</li>
 *   <li>our own health that is not a valid number means the tick does nothing;</li>
 *   <li>odd damage to a target counts as none;</li>
 *   <li>an odd exact raw damage to a target is left out, so no hurt window holds a spot back for it;</li>
 *   <li>a spot with odd damage to us is not an option;</li>
 *   <li>a standing crystal with odd damage to us is taken as deadly ({@link #UNKNOWN_SELF_DAMAGE}): it is
 *   never ours to break (Meteor's max-damage and anti-suicide refuse it) and it fills the budget while it
 *   stands within reach, so nothing is added next to it;</li>
 *   <li>a crystal at no measurable distance is taken as next to us, so it counts;</li>
 *   <li>a negative effect amplifier counts as 0.</li>
 * </ul>
 * Each of these makes ++ do less than it would otherwise, never more, so every action is still one Meteor
 * allows.
 */
public final class ServerValues {
    /** The damage to us assumed for a standing crystal whose damage could not be measured: more than any health. */
    public static final double UNKNOWN_SELF_DAMAGE = 1.0e9;

    private ServerValues() {}

    /**
     * A player seen this tick, or nothing if its health (health plus absorption, summed in {@code float} as
     * Meteor's {@code EntityUtils.getTotalHealth} does) or its squared distance is not a valid number.
     *
     * @param lowestArmorPercent as {@link TargetView#lowestArmorPercent}; a NaN means no piece counts, a
     *                           negative one is worn out
     * @param mainIsTotem        whether its main hand holds a totem of undying (the equipment the server syncs)
     * @param offIsTotem         whether its off hand holds one
     * @param mainEmpty          whether its main hand shows no item
     * @param offEmpty           whether its off hand shows no item; both empty means the hands are not visible
     *                           (a server that hides equipment shows that), never "no totem" ({@link
     *                           TargetView#handsVisible})
     */
    public static Optional<TargetView> target(String name, double squaredDistance, double health, double absorption,
                                              double lowestArmorPercent, boolean creative, boolean alive, boolean friend,
                                              boolean mainIsTotem, boolean offIsTotem, boolean mainEmpty, boolean offEmpty) {
        double total = floatSum(health, absorption);
        if (!valid(total) || !valid(squaredDistance)) return Optional.empty();
        double armor = Double.isNaN(lowestArmorPercent) ? TargetView.NO_ARMOR : Math.max(0, lowestArmorPercent);
        return Optional.of(new TargetView(name, squaredDistance, total, armor, creative, alive, friend,
            mainIsTotem || offIsTotem, !mainEmpty || !offEmpty || mainIsTotem || offIsTotem));
    }

    /** Our health plus absorption, summed as Meteor does, or nothing if it is not a valid number. */
    public static OptionalDouble ownHealth(double health, double absorption) {
        double total = floatSum(health, absorption);
        return valid(total) ? OptionalDouble.of(total) : OptionalDouble.empty();
    }

    /** Damage a crystal would deal to a target; an odd value counts as none. */
    public static double targetDamage(double damage) {
        return valid(damage) ? damage : 0;
    }

    /**
     * The exact raw damage (before armour) a crystal would deal to a target, for {@link TargetWindows}; nothing if
     * it is odd, and then the target has no entry, which never holds a spot back.
     */
    public static OptionalDouble targetRaw(double raw) {
        return valid(raw) ? OptionalDouble.of(raw) : OptionalDouble.empty();
    }

    /** Damage a crystal placed on a spot would deal to us; nothing (the spot is left out) if it is odd. */
    public static OptionalDouble spotSelfDamage(double damage) {
        return valid(damage) ? OptionalDouble.of(damage) : OptionalDouble.empty();
    }

    /** Damage a standing crystal would deal to us; {@link #UNKNOWN_SELF_DAMAGE} if it is odd. */
    public static double crystalSelfDamage(double damage) {
        return valid(damage) ? damage : UNKNOWN_SELF_DAMAGE;
    }

    /**
     * The self damage the budget counts for a crystal or spot: the exact one ({@link ExplosionMath}, with vanilla's
     * exposure and Meteor's reductions) when it is higher than Meteor's, which is up to a raw point short. An exact
     * value that is not a valid number falls back to Meteor's, never to 0, so it cannot make anything look safer.
     *
     * @param meteorSelfDamage Meteor's prediction, already through {@link #spotSelfDamage} or
     *                         {@link #crystalSelfDamage}
     */
    public static double budgetSelfDamage(double meteorSelfDamage, double exactSelfDamage) {
        return valid(exactSelfDamage) ? Math.max(meteorSelfDamage, exactSelfDamage) : meteorSelfDamage;
    }

    /** Our distance to a standing crystal; 0 if it is odd, so the crystal counts in the budget. */
    public static double crystalDistance(double distance) {
        return valid(distance) ? distance : 0;
    }

    /**
     * The durability left of a worn piece, in percent, as Meteor computes it (lines 1143); nothing for a piece
     * with no durability, which never counts there (Meteor's division gives NaN). A piece with more damage
     * than durability is worn out: 0.
     */
    public static OptionalDouble armorPercent(int maxDamage, int damage) {
        if (maxDamage <= 0) return OptionalDouble.empty();
        double percent = (double) (maxDamage - damage) / maxDamage * 100;
        if (Double.isNaN(percent)) return OptionalDouble.empty();
        return OptionalDouble.of(Math.max(0, percent));
    }

    /**
     * An effect's amplifier, as {@link CrystalTick.Hands} takes it: {@link CrystalTick.Hands#NO_EFFECT} when the
     * effect is not active, and never below 0 when it is (the server sends it; a negative one would be refused).
     */
    public static int amplifier(boolean active, int amplifier) {
        return active ? Math.max(0, amplifier) : CrystalTick.Hands.NO_EFFECT;
    }

    private static double floatSum(double health, double absorption) {
        return (float) health + (float) absorption;
    }

    private static boolean valid(double value) {
        return Double.isFinite(value) && value >= 0;
    }
}
