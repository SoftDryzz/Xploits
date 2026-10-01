package com.xploits.pvp.shell.core;

/**
 * An end crystal standing near us.
 *
 * @param id   the entity's id, to attack it
 * @param cell the cell it stands in
 * @param ours whether crystal-aura++ placed it (it looks after its own)
 */
public record StandingCrystal(int id, Cell cell, boolean ours) {
}
