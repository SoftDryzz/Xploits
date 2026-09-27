package com.xploits.pvp.crystal.core;

import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * A place the crystal could go, as {@code BlockIterator} handed it to the adapter (spec P1, lines
 * 934-969). The adapter only reports obsidian or bedrock bases with air above; everything else Meteor
 * checks is here so the core decides it.
 *
 * @param pos              the base block, packed by the adapter; an opaque key the core never shows.
 *                         {@link CrystalView#pos} is packed the same way
 * @param targetDamage     the damage a crystal here would deal to each target, by target name
 * @param targetRaw        the exact raw damage (before armour, {@link ExplosionMath}) it would deal to each target,
 *                         by target name, for {@link TargetWindows}; measured only with the budget on, so it may be
 *                         empty, and a target with no entry is never a reason to hold the spot back
 * @param selfDamage       the damage it would deal to you as Meteor predicts it ({@code DamageUtils}, raw damage
 *                         truncated to an int), with no totem or invulnerability counted: Meteor's max-damage
 *                         and anti-suicide read this one
 * @param budgetSelfDamage the same with the raw damage exact, as the server deals it ({@link ExplosionMath}):
 *                         the self-damage budget reads this one. Never below {@code selfDamage}: a lower
 *                         value is replaced by it
 * @param inRange          whether the crystal position is within place range from the feet, or within
 *                         place-walls-range when the eye raycast hits a wall (lines 947-949, 1164-1171)
 * @param crystalsInBox    ids of the crystals intersecting the 1x2x1 box above the base
 * @param otherEntityInBox whether any other entity that is not a spectator intersects that box
 */
public record Candidate(long pos, Map<String, Double> targetDamage, Map<String, Double> targetRaw, double selfDamage,
                        double budgetSelfDamage, boolean inRange, Set<Integer> crystalsInBox, boolean otherEntityInBox) {
    public Candidate {
        targetDamage = Damage.copyOf(targetDamage, "target damage");
        targetRaw = Damage.copyOf(targetRaw, "target raw damage");
        Damage.check(selfDamage, "self damage");
        budgetSelfDamage = Damage.budgetSelf(budgetSelfDamage, selfDamage);
        crystalsInBox = Set.copyOf(Objects.requireNonNull(crystalsInBox, "crystals in box"));
    }

    /** A spot with no exact raw damage to its targets. */
    public Candidate(long pos, Map<String, Double> targetDamage, double selfDamage, double budgetSelfDamage,
                     boolean inRange, Set<Integer> crystalsInBox, boolean otherEntityInBox) {
        this(pos, targetDamage, Map.of(), selfDamage, budgetSelfDamage, inRange, crystalsInBox, otherEntityInBox);
    }

    /** A spot whose budget self damage is Meteor's own, with no exact raw damage to its targets. */
    public Candidate(long pos, Map<String, Double> targetDamage, double selfDamage, boolean inRange,
                     Set<Integer> crystalsInBox, boolean otherEntityInBox) {
        this(pos, targetDamage, selfDamage, selfDamage, inRange, crystalsInBox, otherEntityInBox);
    }

    /** The damage to these targets, summed in {@code float} in their order (Meteor, lines 1190-1210). */
    public float damageTo(Collection<String> targets) {
        return Damage.sum(targetDamage, targets);
    }
}
