package com.xploits.pvp.crystal.core;

/**
 * The raw damage of an end crystal's explosion, before difficulty, shield, armour, enchantments, effects and
 * absorption: the {@code amount} the server's hurt cooldown compares (spec, Round 2).
 *
 * <p>It is vanilla's {@code ExplosionBehavior.calculateDamage}: with {@code d} the distance from the entity's
 * position to the explosion divided by 12 (vanilla's power 6 times 2; Meteor calls 12 the power) and
 * {@code i = (1 - d) * exposure}, the damage is {@code (i * i + i) / 2 * 7 * 12 + 1}, computed in {@code double}
 * and then made a {@code float}. It is unrounded: Meteor's {@code DamageUtils.explosionDamage} truncates the same
 * value to an {@code int}, and two truncated values that look equal can be almost a whole point apart.
 *
 * <p>Beyond {@code d > 1} there is no hit at all ({@code ExplosionImpl.damageEntities} skips the entity).
 */
public final class RawExplosion {
    /** The distance an end crystal's explosion reaches: vanilla's power 6 times 2, Meteor's power 12. */
    public static final double CRYSTAL_POWER = 12.0;
    /** A raw damage that could not be measured; it is never credited. */
    public static final double UNKNOWN = -1.0;

    private RawExplosion() {}

    /**
     * Vanilla's raw explosion damage.
     *
     * @param distanceOverPower the distance from the entity's position to the explosion, divided by
     *                          {@link #CRYSTAL_POWER}; finite and not negative
     * @param exposure          vanilla's {@code ExplosionImpl.calculateReceivedDamage}: the share of rays from
     *                          the entity's box that reach the explosion, from 0 to 1
     */
    public static double damage(double distanceOverPower, double exposure) {
        if (!Double.isFinite(distanceOverPower) || distanceOverPower < 0) {
            throw new IllegalArgumentException("distance " + distanceOverPower);
        }
        if (!(exposure >= 0 && exposure <= 1)) throw new IllegalArgumentException("exposure " + exposure);
        if (distanceOverPower > 1) return 0;
        double i = (1 - distanceOverPower) * exposure;
        return (float) ((i * i + i) / 2.0 * 7.0 * CRYSTAL_POWER + 1.0);
    }

    /** An end crystal's raw damage at this distance (blocks) with this exposure. */
    public static double crystal(double distance, double exposure) {
        if (!Double.isFinite(distance) || distance < 0) throw new IllegalArgumentException("distance " + distance);
        return damage(distance / CRYSTAL_POWER, exposure);
    }

    /** A raw damage is a measured one (finite, not negative) or {@link #UNKNOWN}. */
    static double check(double raw, String what) {
        if (raw == UNKNOWN) return raw;
        return Damage.check(raw, what);
    }

    /** Whether it was measured. */
    static boolean known(double raw) {
        return Double.isFinite(raw) && raw >= 0;
    }
}
