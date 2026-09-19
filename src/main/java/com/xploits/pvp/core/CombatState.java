package com.xploits.pvp.core;

/** Las fases de una pelea (spec §4.1). */
public enum CombatState {
    /** Ningún objetivo a tiro: se suelta todo lo tomado. */
    SIN_COMBATE,
    /** Objetivo a la vista pero lejos. */
    ACERCAMIENTO,
    /** Objetivo cerca, a pie, sin surround y no enterrado. */
    SUPERFICIE,
    /** El objetivo tiene surround puesto. */
    RODEADO,
    /** El objetivo está dentro de un bloque. */
    ENTERRADO,
    /** El objetivo o tú estáis volando. */
    PERSECUCION,
    /** No se puede sostener ningún módulo de la fase que tocaba. */
    SIN_RECURSOS
}
