package com.xploits.pvp.core;

import java.util.Map;

/**
 * Atajo de los tests para construir un {@link CombatSnapshot} dando solo la mitad del enemigo y la
 * del inventario, con el resto en valores neutros: sin objetivo identificado, sin hostiles a rango
 * de cristal, la vida llena, nada de daño apuntándote, ni en agujero ni en el suelo, con la altura quieta y con el
 * {@code anti-suicide} de {@code crystal-aura} <b>encendido</b>, que es como viene de fábrica.
 *
 * <p>Este atajo vivía en el núcleo como constructor de transición mientras el adaptador todavía no
 * leía los campos nuevos del rediseño. Ese andamio ya no existe —el adaptador los rellena todos—,
 * así que la comodidad se queda donde hacía falta de verdad, en los tests, y el record de
 * producción tiene una sola forma. Los tests que sí miran los ejes nuevos construyen el record
 * entero o parten de aquí con sus {@code with*}.
 */
final class Snapshots {
    private Snapshots() {}

    static CombatSnapshot of(boolean hasTarget, double targetDistance, int targetSurroundSides,
                             double cityBlockDistance, boolean targetBurrowed,
                             boolean targetGliding, boolean selfGliding, int selfTotems,
                             Map<Resource, Integer> resources) {
        return new CombatSnapshot(hasTarget, targetDistance, targetSurroundSides, cityBlockDistance,
            targetBurrowed, targetGliding, selfGliding, selfTotems, resources,
            null, 0, CombatSnapshot.FULL_HEALTH, 0, false, false, false, true);
    }

    /**
     * El mismo snapshot con el {@code anti-suicide} de {@code crystal-aura} apagado: el único caso
     * en el que el suelo de tótems sigue en pie (rediseño §7, por la puerta de §10).
     */
    static CombatSnapshot antiSuicideOff(CombatSnapshot base) {
        return new CombatSnapshot(base.hasTarget(), base.targetDistance(), base.targetSurroundSides(),
            base.cityBlockDistance(), base.targetBurrowed(), base.targetGliding(),
            base.selfGliding(), base.selfTotems(), base.resources(),
            base.targetId(), base.hostilesInCrystalRange(), base.selfTotalHealth(),
            base.incomingDamage(), base.selfInHole(), base.selfOnGround(), base.selfYChanged(),
            false);
    }
}
