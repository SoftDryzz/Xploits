package com.xploits.elytra.core;

/**
 * Una elytra del inventario que podría servir de repuesto (spec §4.2).
 *
 * @param slot    índice del inventario, 0-35
 * @param percent durabilidad restante, de 0 a 100
 */
public record ElytraCandidate(int slot, int percent) {}
