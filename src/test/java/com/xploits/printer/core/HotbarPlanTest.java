package com.xploits.printer.core;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Printer spec §5.5 and N-M7: select, one QUICK_MOVE, or stop — never overwrite. */
class HotbarPlanTest {
    private static final String STONE = "minecraft:stone";

    private static List<HotbarPlan.Slot> inventory() {
        List<HotbarPlan.Slot> slots = new ArrayList<>();
        for (int i = 0; i < 36; i++) slots.add(HotbarPlan.Slot.EMPTY);
        return slots;
    }

    private static HotbarPlan.Slot of(String item, int count) {
        return new HotbarPlan.Slot(item, count);
    }

    @Test
    void theSelectedSlotIsReadyWhenItHoldsTheMaterial() {
        List<HotbarPlan.Slot> inv = inventory();
        inv.set(4, of(STONE, 10));
        assertEquals(new HotbarPlan.Ready(4), HotbarPlan.forMaterial(inv, 4, STONE));
    }

    @Test
    void anotherHotbarSlotIsSelectedTheLowestFirst() {
        List<HotbarPlan.Slot> inv = inventory();
        inv.set(6, of(STONE, 10));
        inv.set(3, of(STONE, 2));
        inv.set(0, of("minecraft:diamond_pickaxe", 1));
        assertEquals(new HotbarPlan.Select(3), HotbarPlan.forMaterial(inv, 0, STONE));
    }

    @Test
    void aMainInventoryStackIsQuickMovedWhenTheHotbarHasRoom() {
        List<HotbarPlan.Slot> inv = inventory();
        inv.set(20, of(STONE, 64));
        inv.set(12, of(STONE, 30));
        for (int i = 0; i < 9; i++) if (i != 5) inv.set(i, of("minecraft:bread", 1));
        assertEquals(new HotbarPlan.QuickMove(12), HotbarPlan.forMaterial(inv, 0, STONE));
    }

    @Test
    void aFullHotbarStopsNeverOverwrites() {
        List<HotbarPlan.Slot> inv = inventory();
        for (int i = 0; i < 9; i++) inv.set(i, of("minecraft:totem_of_undying", 1));
        inv.set(30, of(STONE, 64));
        assertEquals(new HotbarPlan.NoRoom(), HotbarPlan.forMaterial(inv, 0, STONE));
    }

    @Test
    void nothingCarriedIsSaid() {
        assertEquals(new HotbarPlan.NotCarried(), HotbarPlan.forMaterial(inventory(), 0, STONE));
    }

    @Test
    void theHotbarStackIsUsedWhenTheMainInventoryHoldsTheMaterialToo() {
        List<HotbarPlan.Slot> inv = inventory();
        inv.set(6, of(STONE, 3));
        inv.set(20, of(STONE, 64));
        assertEquals(new HotbarPlan.Select(6), HotbarPlan.forMaterial(inv, 0, STONE));
        assertEquals(new HotbarPlan.Ready(6), HotbarPlan.forMaterial(inv, 6, STONE));
    }
}
