package com.xploits.pvp.crystal.core;

import java.util.Objects;

/**
 * A player the adapter saw this tick, other than you (spec P1, lines 1222-1253). Whether it is a
 * target is the core's decision.
 *
 * @param name               stable identity; the key of the damage maps
 * @param squaredDistance    the squared distance from you to it ({@code Entity.squaredDistanceTo}): Meteor
 *                           compares it with the squared target-range (line 1250), so the core does too and the
 *                           boundary is Meteor's own ({@link Reach#inTargetRange})
 * @param totalHealth        its health plus absorption
 * @param lowestArmorPercent the lowest durability left, in percent, among the armour pieces it wears
 *                           that can wear out (Meteor, lines 1136-1145); {@link #NO_ARMOR} when none
 * @param creative           whether it is in creative mode
 * @param alive              whether it is alive
 * @param friend             whether Meteor's friends say not to attack it
 */
public record TargetView(String name, double squaredDistance, double totalHealth, double lowestArmorPercent,
                         boolean creative, boolean alive, boolean friend, boolean totemInHand,
                         boolean handsVisible) {
    /** No worn piece counts for face-placing. */
    public static final double NO_ARMOR = Double.POSITIVE_INFINITY;

    public TargetView {
        Objects.requireNonNull(name, "name");
        if (!Double.isFinite(squaredDistance) || squaredDistance < 0) {
            throw new IllegalArgumentException("squared distance " + squaredDistance);
        }
        if (!Double.isFinite(totalHealth) || totalHealth < 0) throw new IllegalArgumentException("health " + totalHealth);
        if (Double.isNaN(lowestArmorPercent) || lowestArmorPercent < 0) {
            throw new IllegalArgumentException("armor " + lowestArmorPercent);
        }
    }

    /**
     * Whether a crystal that finishes it kills it rather than only pops it (task B0c): no totem in either hand
     * AND the hands visible. Hidden equipment shows both hands empty, which is never "no totem".
     */
    public boolean killable() {
        return !totemInHand && handsVisible;
    }
}
