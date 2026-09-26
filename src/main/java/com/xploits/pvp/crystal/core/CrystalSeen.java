package com.xploits.pvp.crystal.core;

import java.util.Collection;
import java.util.Map;

/**
 * One standing end crystal as the adapter measured it: only what the world says. What we did to it
 * (whether we placed it, our attacks, when it disappeared) is {@link CrystalBrain}'s own record, which
 * turns this into a {@link CrystalView}. Its position is only an opaque key, never shown.
 *
 * @param id           the entity id
 * @param pos          its base block (the crystal's block minus one), packed exactly like
 *                     {@link Candidate#pos}, so it can be matched against a pending placement (Meteor
 *                     matches by block, lines 734-738)
 * @param targetDamage the damage its explosion would deal to each player, by name
 *                     ({@code DamageUtils.crystalDamage}); the brain sums it over its targets only
 * @param targetRaw    the exact raw damage (before armour, {@link ExplosionMath}) its explosion would deal to each
 *                     target, by name: what {@link TargetWindows} takes as the size of the hit if this crystal is ours
 *                     and hits one. Measured only with the budget on, so it may be empty
 * @param selfDamage   the damage it would deal to you as Meteor predicts it (raw damage truncated to an int),
 *                     with no totem or invulnerability taken into account: Meteor's checks read this one
 * @param budgetSelfDamage the same with the raw damage exact ({@link ExplosionMath}): the budget reads this one.
 *                     Never below {@code selfDamage}: a lower value is replaced by it
 * @param distance     the distance from your feet to the crystal, as {@code DamageUtils} measures it
 * @param inBreakRange whether it is within break range (break-range, or break-walls-range when the eye
 *                     raycast hits a wall, lines 1164-1171)
 */
public record CrystalSeen(int id, long pos, Map<String, Double> targetDamage, Map<String, Double> targetRaw,
                          double selfDamage, double budgetSelfDamage, double distance, boolean inBreakRange) {
    public CrystalSeen {
        targetDamage = Damage.copyOf(targetDamage, "target damage");
        targetRaw = Damage.copyOf(targetRaw, "target raw damage");
        Damage.check(selfDamage, "self damage");
        budgetSelfDamage = Damage.budgetSelf(budgetSelfDamage, selfDamage);
        if (!Double.isFinite(distance) || distance < 0) throw new IllegalArgumentException("distance " + distance);
    }

    /** A crystal with no exact raw damage to its targets. */
    public CrystalSeen(int id, long pos, Map<String, Double> targetDamage, double selfDamage, double budgetSelfDamage,
                       double distance, boolean inBreakRange) {
        this(id, pos, targetDamage, Map.of(), selfDamage, budgetSelfDamage, distance, inBreakRange);
    }

    /** A crystal whose budget self damage is Meteor's own, with no exact raw damage to its targets. */
    public CrystalSeen(int id, long pos, Map<String, Double> targetDamage, double selfDamage, double distance,
                       boolean inBreakRange) {
        this(id, pos, targetDamage, selfDamage, selfDamage, distance, inBreakRange);
    }

    /** The damage to these targets, summed in {@code float} in their order (Meteor, lines 1190-1210). */
    public float damageTo(Collection<String> targets) {
        return Damage.sum(targetDamage, targets);
    }
}
