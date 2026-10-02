package com.xploits.restock.core;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Restock spec §3 "Take": one QUICK_MOVE a tick; the material that ran out first, then every other still needed, as
 * much as fits. {@code room} is what the adapter measured: how many of that exact stack the player's 36 slots take.
 */
class TakePlanTest {
    private static final String STONE = "minecraft:stone";
    private static final String DIRT = "minecraft:dirt";

    private static Map<String, Long> need(Object... itemsAndCounts) {
        Map<String, Long> m = new LinkedHashMap<>();
        for (int i = 0; i < itemsAndCounts.length; i += 2) m.put((String) itemsAndCounts[i], (Long) itemsAndCounts[i + 1]);
        return m;
    }

    private static final List<TakePlan.Slot> CHEST = List.of(new TakePlan.Slot(0, STONE, 20, 128),
        new TakePlan.Slot(1, STONE, 64, 128), new TakePlan.Slot(2, DIRT, 64, 64));

    @Test
    void theTripsMaterialFirstFromItsLargestStack() {
        assertEquals(new TakePlan.Click(1), TakePlan.next(CHEST, STONE, need(DIRT, 5L, STONE, 10L)));
    }

    @Test
    void thenEveryOtherMaterialStillNeeded() {
        assertEquals(new TakePlan.Click(2), TakePlan.next(CHEST, STONE, need(DIRT, 5L)));
    }

    @Test
    void doneWhenNothingMoreIsNeeded() {
        assertEquals(new TakePlan.Done(true), TakePlan.next(CHEST, STONE, need()));
    }

    @Test
    void aContainerWithoutTheMaterialIsStale() {
        assertEquals(new TakePlan.Done(false), TakePlan.next(List.of(new TakePlan.Slot(2, DIRT, 64, 64)), STONE,
            need(STONE, 10L)), "dirt is not needed, so nothing is taken");
    }

    @Test
    void theMaterialThereWithoutRoomIsNothingFits() {
        assertEquals(new TakePlan.NothingFits(), TakePlan.next(List.of(new TakePlan.Slot(0, STONE, 64, 0)), STONE,
            need(STONE, 10L)));
    }

    @Test
    void anotherMaterialThatFitsIsTakenBeforeSayingNothingFits() {
        List<TakePlan.Slot> chest = List.of(new TakePlan.Slot(0, STONE, 64, 0), new TakePlan.Slot(1, DIRT, 64, 64));
        assertEquals(new TakePlan.Click(1), TakePlan.next(chest, STONE, need(STONE, 10L, DIRT, 5L)));
        assertEquals(new TakePlan.NothingFits(), TakePlan.next(chest, STONE, need(STONE, 10L)));
    }

    @Test
    void aPartialStackIsToppedUpWhenNoSlotIsFree() {
        // No empty slot, one stack of 59 stone: room for 5. QUICK_MOVE moves those 5 and leaves the rest.
        assertEquals(new TakePlan.Click(0), TakePlan.next(List.of(new TakePlan.Slot(0, STONE, 64, 5)), STONE,
            need(STONE, 10L)));
    }

    @Test
    void aShulkerBoxThatHoldsAnythingIsNeverTakenAsABlock() {
        // Ruling R34: the build places red shulker boxes, and a marked chest keeps a stash in a red shulker box. Taken,
        // the printer would place it, contents and all, into a public build. It is not there for the trip at all.
        String box = "minecraft:red_shulker_box";
        assertEquals(new TakePlan.Done(false), TakePlan.next(List.of(new TakePlan.Slot(0, box, 1, 64, true)), box,
            need(box, 2L)), "not taken, and not 'nothing fits' either: for the trip the container does not have it");
        assertEquals(new TakePlan.Click(4), TakePlan.next(List.of(new TakePlan.Slot(0, box, 1, 64, true),
            new TakePlan.Slot(4, box, 1, 64, false)), box, need(box, 2L)), "an empty one is a block like any other");
        assertEquals(new TakePlan.Done(false), TakePlan.next(List.of(new TakePlan.Slot(0, box, 1, 64, true)), STONE,
            need(STONE, 10L, box, 2L)), "nor as another material the build still needs");
    }

    @Test
    void aTieGoesToTheLowerSlot() {
        assertEquals(new TakePlan.Click(2), TakePlan.next(List.of(new TakePlan.Slot(5, STONE, 64, 128),
            new TakePlan.Slot(2, STONE, 64, 128)), STONE, need(STONE, 10L)));
    }
}
