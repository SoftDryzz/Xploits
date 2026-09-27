package com.xploits.pvp.crystal.core;

import java.util.Collection;
import java.util.Map;

/**
 * One end crystal as {@link CrystalBrain} knows it: the adapter's latest measurement ({@link CrystalSeen})
 * plus what we did to it, or as it last was when it has just disappeared (spec §1, P3). Its position is
 * only an opaque key, never shown.
 *
 * <p>Ticks are client pre-tick numbers. An attack made between two pre-ticks (fast-break, on
 * {@code EntityAdded}) carries the number of the last pre-tick before it, so "the 5th pre-tick after
 * the attack" is always {@code attackedTick + 5}.
 *
 * @param id           the entity id
 * @param pos          its base block (the crystal's block minus one), packed exactly like
 *                     {@link Candidate#pos}, so a crystal that appears can be matched against a pending
 *                     placement (Meteor matches by block, lines 734-738)
 * @param targetDamage the damage this crystal's explosion would deal to each target, by target name
 * @param selfDamage   the damage it would deal to you ({@code DamageUtils.crystalDamage}, raw damage truncated
 *                     to an int), with no totem or invulnerability taken into account: Meteor's checks read it
 * @param budgetSelfDamage the same with the raw damage exact ({@link ExplosionMath}): {@link SelfBudget} reads
 *                     only this one. Never below {@code selfDamage}: a lower value is replaced by it
 * @param distance     the distance from your feet to the crystal, as {@code DamageUtils} measures it:
 *                     beyond {@link SelfBudget#HAZARD_RADIUS} its explosion cannot reach you
 * @param inBreakRange whether it is within break range (Meteor's break-range or break-walls-range after
 *                     the eye raycast, lines 1164-1171)
 * @param ours         whether we placed it
 * @param attempts     how many times we attacked it
 * @param attackedTick the pre-tick of our last attack on it, or {@link #NEVER}
 * @param removedTick  the pre-tick at which it was first seen gone, or {@link #NEVER} while it stands
 */
public record CrystalView(int id, long pos, Map<String, Double> targetDamage, double selfDamage, double budgetSelfDamage,
                          double distance, boolean inBreakRange, boolean ours, int attempts, long attackedTick, long removedTick) {
    /** No such tick: never attacked, or still standing. */
    public static final long NEVER = -1;

    /**
     * After an attack, Meteor leaves the crystal out of its break and multiplace checks until the 5th
     * pre-tick after it ({@code waitingToExplode}, lines 697-708: the counter starts at 0 on the attack
     * and the crystal is released when it goes past 3, which happens on the 5th pre-tick).
     */
    public static final int ATTACK_WAIT_TICKS = 5;

    public CrystalView {
        targetDamage = Damage.copyOf(targetDamage, "target damage");
        Damage.check(selfDamage, "self damage");
        budgetSelfDamage = Damage.budgetSelf(budgetSelfDamage, selfDamage);
        if (!Double.isFinite(distance) || distance < 0) throw new IllegalArgumentException("distance " + distance);
        if (attempts < 0) throw new IllegalArgumentException("attempts " + attempts);
        if (attackedTick < NEVER) throw new IllegalArgumentException("attacked tick " + attackedTick);
        if (removedTick < NEVER) throw new IllegalArgumentException("removed tick " + removedTick);
        if (attackedTick != NEVER && attempts == 0) {
            throw new IllegalArgumentException("attacked with no attempts");
        }
    }

    /** A crystal whose budget self damage is Meteor's own. */
    public CrystalView(int id, long pos, Map<String, Double> targetDamage, double selfDamage, double distance,
                       boolean inBreakRange, boolean ours, int attempts, long attackedTick, long removedTick) {
        this(id, pos, targetDamage, selfDamage, selfDamage, distance, inBreakRange, ours, attempts, attackedTick,
            removedTick);
    }

    /** Whether it is still standing. */
    public boolean live() {
        return removedTick == NEVER;
    }

    /** Whether we ever attacked it. */
    public boolean attacked() {
        return attackedTick != NEVER;
    }

    /**
     * Whether it is standing and still inside Meteor's wait after our last attack at this pre-tick:
     * from the attack until the pre-tick before the 5th ({@link #ATTACK_WAIT_TICKS}).
     */
    public boolean waitingAt(long now) {
        return live() && attacked() && now - attackedTick < ATTACK_WAIT_TICKS;
    }

    /** The damage to these targets, summed in {@code float} in their order (Meteor, lines 1190-1210). */
    public float damageTo(Collection<String> targets) {
        return Damage.sum(targetDamage, targets);
    }
}
