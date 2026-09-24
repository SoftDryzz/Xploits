package com.xploits.elytra.core;

/**
 * An elytra in the inventory that could serve as a spare (spec §4.2).
 *
 * @param slot    inventory index, 0-35
 * @param percent remaining durability, from 0 to 100
 */
public record ElytraCandidate(int slot, int percent) {}
