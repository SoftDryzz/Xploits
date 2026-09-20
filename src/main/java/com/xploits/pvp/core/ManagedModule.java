package com.xploits.pvp.core;

/**
 * Un módulo de combate de Meteor que el director dirige (spec §6).
 *
 * @param name           el nombre exacto con el que aparece en la ClickGUI
 * @param needs          qué recurso necesita para servir de algo
 * @param minimum        cuánto hace falta como mínimo
 * @param turnsItselfOff si este módulo puede apagarse solo, por diseño de Meteor, sin que el
 *                       jugador toque nada — con los ajustes de fábrica (spec §7): {@code
 *                       auto-trap} tras colocar el trap ({@code self-toggle}), {@code surround}
 *                       por sus ajustes {@code toggle-on-*}, {@code auto-city} si no encuentra
 *                       objetivo, bloque o pico (incluso dentro de su propio {@code onActivate()}),
 *                       y {@code auto-anvil} si el slot de la cabeza está vacío. {@link
 *                       ModuleLedger} usa esta marca para no confundir ese apagado automático con
 *                       que el jugador lo soltó a mano.
 */
public record ManagedModule(String name, Resource needs, int minimum, boolean turnsItselfOff) {}
