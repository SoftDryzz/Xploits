package com.xploits.pvp.core;

/**
 * Un módulo de combate de Meteor que el director dirige (spec §6).
 *
 * @param name    el nombre exacto con el que aparece en la ClickGUI
 * @param needs   qué recurso necesita para servir de algo
 * @param minimum cuánto hace falta como mínimo
 */
public record ManagedModule(String name, Resource needs, int minimum) {}
