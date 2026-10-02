package com.xploits.printer.core;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static com.xploits.printer.core.Fixtures.block;
import static com.xploits.printer.core.Fixtures.stone;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** Printer spec §4 MaterialNeeds and N-M14: no double count, whichever answer comes first. */
class MaterialNeedsTest {
    private static final String STONE = "minecraft:stone";
    private static final Map<String, Long> TOTAL = Map.of(STONE, 10L);
    private static final Pos A = new Pos(0, 0, 3);

    private static long need(int placed, int carried, PendingBlocks p) {
        return MaterialNeeds.need(TOTAL, Map.of(STONE, placed), Map.of(STONE, carried), p.placedNotSynced(),
            p.syncedNotPlaced()).getOrDefault(STONE, 0L);
    }

    @Test
    void theBlockUpdateFirst() {
        PendingBlocks p = new PendingBlocks(PrinterLimits.DEFAULTS);
        assertEquals(5, need(0, 5, p));
        p.sent(A, STONE, 1, 1);
        assertEquals(5, need(0, 5, p), "in flight, neither answer yet: nothing changes");
        p.settle(2, 0, pos -> true);
        assertEquals(5, need(1, 5, p), "placed, the inventory not yet synced");
        p.inventoryDrop(STONE, 1);
        assertEquals(5, need(1, 4, p));
    }

    @Test
    void theInventorySyncFirst() {
        PendingBlocks p = new PendingBlocks(PrinterLimits.DEFAULTS);
        p.sent(A, STONE, 1, 1);
        p.inventoryDrop(STONE, 1);
        assertEquals(5, need(0, 4, p), "synced, the block update not yet in");
        p.settle(2, 0, pos -> true);
        assertEquals(5, need(1, 4, p));
    }

    @Test
    void needIsNeverNegativeAndZeroesAreLeftOut() {
        assertEquals(Map.of(), MaterialNeeds.need(TOTAL, Map.of(), Map.of(STONE, 64), Map.of(), Map.of()));
        assertEquals(Map.of(STONE, 10L), MaterialNeeds.need(TOTAL, Map.of(), Map.of(), Map.of(), Map.of()));
    }

    @Test
    void totalsCountOnlyPhaseOneTargetsByTheirItem() {
        Map<BlockFacts, Long> build = Map.of(stone(), 10L, block("glass").build(), 2L,
            block("oak_stairs").props().notFull().build(), 4L, block("dirt").build(), 3L, BlockFacts.AIR, 50L);
        assertEquals(Map.of("minecraft:glass", 2L, STONE, 10L), MaterialNeeds.totals(build));
        assertEquals(7L, MaterialNeeds.later(build));
    }

    @Test
    void whatIsShortIsWhatRemainsBeyondWhatIsCarried() {
        assertEquals(Map.of("minecraft:glass", 2), MaterialNeeds.shortOf(Map.of(STONE, 10, "minecraft:glass", 2),
            Map.of(STONE, 12)));
    }
}
