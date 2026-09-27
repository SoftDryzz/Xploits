package com.xploits.pvp.core;

import java.util.Map;

/**
 * Test shortcut to build a {@link CombatSnapshot} giving only the enemy's half and the
 * inventory's, with the rest at neutral values: no identified target, no hostiles in crystal
 * range, full health, no damage aimed at you, neither in a hole nor on the ground nor with a hole just
 * breached, with your height still and with the latched crystal aura's protection (Meteor's
 * {@code anti-suicide}, or crystal-aura++'s {@code anti-suicide} or {@code self-budget}) <b>on</b>,
 * which is how it ships.
 *
 * <p>This shortcut used to live in the core as a transitional constructor while the adapter did not yet
 * read the redesign's new fields. That scaffolding no longer exists —the adapter fills them all in—,
 * so the convenience stays where it was really needed, in the tests, and the production
 * record has a single shape. The tests that do look at the new axes build the whole record
 * or start from here with its {@code with*}.
 */
final class Snapshots {
    private Snapshots() {}

    static CombatSnapshot of(boolean hasTarget, double targetDistance, int targetSurroundSides,
                             double cityBlockDistance, boolean targetBurrowed,
                             boolean targetGliding, boolean selfGliding, int selfTotems,
                             Map<Resource, Integer> resources) {
        return new CombatSnapshot(hasTarget, targetDistance, targetSurroundSides, cityBlockDistance,
            targetBurrowed, targetGliding, selfGliding, selfTotems, resources,
            null, 0, CombatSnapshot.FULL_HEALTH, 0, false, false, false, false, true);
    }

    /**
     * The same snapshot with the latched crystal aura's protection off: the only case in which the
     * totem floor stays in place (redesign §7, through the door of §10; task R3-13 fix 1).
     */
    static CombatSnapshot protectionOff(CombatSnapshot base) {
        return new CombatSnapshot(base.hasTarget(), base.targetDistance(), base.targetSurroundSides(),
            base.cityBlockDistance(), base.targetBurrowed(), base.targetGliding(),
            base.selfGliding(), base.selfTotems(), base.resources(),
            base.targetId(), base.hostilesInCrystalRange(), base.selfTotalHealth(),
            base.incomingDamage(), base.selfInHole(), base.selfOnGround(), base.selfYChanged(),
            base.selfHoleBreached(), false);
    }
}
