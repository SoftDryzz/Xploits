package com.xploits.pvp.core;

/**
 * El eje defensivo del rediseño (§3): qué te está pasando a <b>ti</b>, con independencia de en qué
 * fase esté el objetivo.
 *
 * <p>El error que corrige es haberlo metido todo en un único {@code enum}: "me están cristaleando" y
 * "él está rodeado" son verdaderas a la vez, y un enum obliga a elegir entre atacar y defenderte.
 * Los módulos que se encienden son la <b>unión</b> de los que pide cada eje.
 */
public enum CombatPosture {
    /** El daño que ya te apunta no te deja por debajo del margen: no hay nada que tapar. */
    TRANQUILO,
    /** Lo que ya está colocado contra ti te dejaría bajo el margen (§5). */
    AMENAZADO
}
