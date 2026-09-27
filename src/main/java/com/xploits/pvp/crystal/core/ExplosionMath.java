package com.xploits.pvp.crystal.core;

/**
 * An end crystal's explosion damage as the server computes it, before armour and protection (vanilla
 * {@code ExplosionBehavior.calculateDamage}, 1.21.11): {@code (n * n + n) / 2 * 7 * 12 + 1} in {@code double},
 * with {@code n = (1 - distance / 12) * exposure}, then cast to {@code float} and nothing else.
 *
 * <p>Meteor's {@code DamageUtils.explosionDamage} (line 95) casts that value to an {@code int} before its
 * reductions, so its prediction can be up to one raw point short of what the server deals. The self-damage
 * budget reads this exact value; Meteor's own checks keep Meteor's.
 */
public final class ExplosionMath {
    /** An end crystal's explosion power doubled: its damage radius and the {@code 12} of the formula. */
    public static final double CRYSTAL_RADIUS = 12.0;

    private ExplosionMath() {}

    /**
     * The raw damage of an end crystal's explosion, fraction kept.
     *
     * @param distance from the entity's feet to the explosion, as the server measures it; beyond
     *                 {@link #CRYSTAL_RADIUS} there is no damage
     * @param exposure the fraction of the entity the explosion reaches, 0 to 1 (vanilla
     *                 {@code ExplosionImpl.calculateReceivedDamage}); with none, the {@code + 1} is still dealt
     * @return the damage before armour, protection and difficulty. A NaN in gives a NaN out, which the adapter's
     *         checks ({@link ServerValues#budgetSelfDamage}) then refuse
     */
    public static float rawDamage(double distance, double exposure) {
        if (distance > CRYSTAL_RADIUS) return 0f;
        double n = (1 - distance / CRYSTAL_RADIUS) * exposure;
        return (float) ((n * n + n) / 2 * 7 * CRYSTAL_RADIUS + 1);
    }
}
