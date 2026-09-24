package com.xploits.pvp.recorder.core;

import com.xploits.pvp.core.CombatPosture;
import com.xploits.pvp.core.CombatState;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Everything the adapter measured in one client tick, with no position in it: distances, counts,
 * booleans and names only. {@code autoPvp} is null when auto-pvp is off or has no plan yet.
 */
public record TickInput(long tick, long epochMillis, boolean alive, SelfState self, List<Hostile> hostiles,
                        Set<String> activeModules, boolean autoPvpOn, AutoPvpView autoPvp, List<CombatEvent> events) {
    public TickInput {
        Objects.requireNonNull(self, "self");
        hostiles = List.copyOf(hostiles);
        activeModules = Set.copyOf(activeModules);
        events = List.copyOf(events);
    }

    /**
     * Your own state: health plus absorption, damage already aimed at you, totems in the inventory, hotbar
     * crystals and obsidian, golden apples, armor pieces worn.
     */
    public record SelfState(double health, double incoming, int totems, boolean offhandTotem, int crystals,
                            int obsidian, int gapples, int armor, boolean inHole, boolean gliding) {
    }

    /** A non-allied player within engage range, by name and distance. */
    public record Hostile(String name, double distance) {
        public Hostile {
            Objects.requireNonNull(name, "name");
            if (!(distance >= 0)) throw new IllegalArgumentException("distance " + distance);
        }
    }

    /** What auto-pvp is doing this tick; {@code target} is null when it has none. */
    public record AutoPvpView(CombatState state, CombatPosture posture, String target) {
        public AutoPvpView {
            Objects.requireNonNull(state, "state");
            Objects.requireNonNull(posture, "posture");
        }
    }
}
