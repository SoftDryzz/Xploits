package com.xploits.pvp.core;

/**
 * Un módulo de combate de Meteor que el director dirige (spec §6).
 *
 * @param name           el nombre exacto con el que aparece en la ClickGUI
 * @param needs          qué recurso necesita para servir de algo
 * @param minimum        cuánto hace falta como mínimo
 * @param turnsItselfOff si este módulo puede apagarse solo, por diseño de Meteor, sin que el
 *                       jugador toque nada (spec §7) — no las cuatro marcas por el mismo motivo, ni
 *                       todas de fábrica: {@code auto-trap} tras colocar el trap con éxito, con
 *                       {@code self-toggle} activo de fábrica; {@code surround} de fábrica por
 *                       {@code toggle-on-y-change} y {@code toggle-on-death} ({@code
 *                       toggle-on-complete} existe pero es {@code false} de fábrica); {@code
 *                       auto-city} de fábrica y sin ningún ajuste de por medio, si no encuentra
 *                       objetivo, bloque o pico (incluso dentro de su propio {@code onActivate()})
 *                       y también tras minar con éxito; y {@code auto-anvil}, marcado como
 *                       decisión conservadora aunque el {@code toggle()} que lo apaga con la cabeza
 *                       del objetivo vacía está detrás de {@code toggle-on-break}, que es {@code
 *                       false} de fábrica — con los ajustes de fábrica {@code auto-anvil} no se
 *                       apaga solo. {@link ModuleLedger} usa esta marca para no confundir ese
 *                       apagado automático con que el jugador lo soltó a mano.
 */
public record ManagedModule(String name, Resource needs, int minimum, boolean turnsItselfOff) {}
