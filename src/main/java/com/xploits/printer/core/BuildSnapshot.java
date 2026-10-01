package com.xploits.printer.core;

import java.util.Map;
import java.util.Set;

/**
 * One tick's facts as simple values (printer spec §4): the cells around the eye, what blocks placement, what the player
 * stands on, what is in flight, skipped or already broken, what is carried, and the best tool for each wrong block.
 *
 * @param yaw          the player's own yaw, for a continuous rotation
 * @param reach        the {@code reach} setting (the planner also caps it at {@link PrinterLimits#maxReach()})
 * @param cells        every position within reach plus two blocks, so each target's neighbours are known
 * @param entityBlocked target positions where {@code World.canPlace} says an entity is in the way
 * @param standingOn   blocks under the player's feet
 * @param pending      placements sent and not yet confirmed
 * @param digging      digs sent whose block the server still shows (in flight until it is gone)
 * @param skipped      positions skipped after K failed placements
 * @param broken       positions the printer broke in this session (the loop guard)
 * @param carried      item id → count in the hotbar and the main inventory
 * @param breakChoices wrong positions → the best qualifying hotbar tool; absent when none qualifies
 */
public record BuildSnapshot(Point eye, float yaw, double reach, boolean fixWrongBlocks, Map<Pos, Cell> cells,
                            Set<Pos> entityBlocked, Set<Pos> standingOn, Set<Pos> pending, Set<Pos> digging,
                            Set<Pos> skipped, Set<Pos> broken, Map<String, Integer> carried, Map<Pos, BreakPlan.Choice> breakChoices) {
    public BuildSnapshot {
        cells = Map.copyOf(cells);
        entityBlocked = Set.copyOf(entityBlocked);
        standingOn = Set.copyOf(standingOn);
        pending = Set.copyOf(pending);
        digging = Set.copyOf(digging);
        skipped = Set.copyOf(skipped);
        broken = Set.copyOf(broken);
        carried = Map.copyOf(carried);
        breakChoices = Map.copyOf(breakChoices);
    }
}
