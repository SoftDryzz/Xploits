package com.xploits.pvp.shell.core;

/**
 * What the auto-pvp panel says about the shell (spec §8).
 *
 * @param headCovered every cell next to our head holds a block, or has one planned this tick
 * @param openThreats spots still worth a block after this tick's plan
 * @param underAttack hard blocks next to us that someone is mining
 */
public record ShellStatus(boolean headCovered, int openThreats, int underAttack) {
}
