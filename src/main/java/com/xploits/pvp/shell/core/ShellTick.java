package com.xploits.pvp.shell.core;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * What surround++ does this tick.
 *
 * @param placements   blocks to place, in order
 * @param breakCrystal the id of the crystal to attack
 * @param keys         the movement keys to hold this tick; empty releases the ones surround++ holds
 * @param walkTo       a walk starting this tick, to this hole
 * @param walkEnded    the walk under way ended this tick
 * @param centre       centre us on our block
 * @param burrow       turn Meteor's burrow on
 * @param status       for the panel
 */
public record ShellTick(List<Placement> placements, Optional<Integer> breakCrystal, Set<HoleWalk.Key> keys,
                        Optional<Cell> walkTo, boolean walkEnded, boolean centre, boolean burrow, ShellStatus status) {
    public ShellTick {
        placements = List.copyOf(placements);
        keys = Set.copyOf(keys);
        Objects.requireNonNull(breakCrystal, "breakCrystal");
        Objects.requireNonNull(walkTo, "walkTo");
        Objects.requireNonNull(status, "status");
    }
}
