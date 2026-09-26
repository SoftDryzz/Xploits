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
 * @param pos              the base block, packed by the adapter; an opaque key the core never shows
 * @param targetDamage     the damage a crystal here would deal to each target, by target name
 * @param selfDamage       the damage it would deal to you, with no totem or invulnerability counted
 * @param inRange          whether the crystal position is within place range from the feet, or within
 *                         place-walls-range when the eye raycast hits a wall (lines 947-949, 1164-1171)
 * @param crystalsInBox    ids of the crystals intersecting the 1x2x1 box above the base
 * @param otherEntityInBox whether any other entity that is not a spectator intersects that box
 */
public record Candidate(long pos, Map<String, Double> targetDamage, double selfDamage, boolean inRange,
                        Set<Integer> crystalsInBox, boolean otherEntityInBox) {
    public Candidate {
        targetDamage = Damage.copyOf(targetDamage, "target damage");
        Damage.check(selfDamage, "self damage");
        crystalsInBox = Set.copyOf(Objects.requireNonNull(crystalsInBox, "crystals in box"));
    }

    /** The damage to these targets, summed (Meteor, lines 1198-1210). */
    public double damageTo(Collection<String> targets) {
        return Damage.sum(targetDamage, targets);
    }
}
