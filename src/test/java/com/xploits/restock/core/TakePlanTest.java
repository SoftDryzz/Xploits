package com.xploits.restock.core;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
        assertEquals(new TakePlan.Done(false, true), TakePlan.next(List.of(new TakePlan.Slot(0, box, 1, 64, true)), box,
            need(box, 2L)), "not taken, and not 'nothing fits' either: for the trip the container does not have it");
        assertEquals(new TakePlan.Click(4), TakePlan.next(List.of(new TakePlan.Slot(0, box, 1, 64, true),
            new TakePlan.Slot(4, box, 1, 64, false)), box, need(box, 2L)), "an empty one is a block like any other");
        assertEquals(new TakePlan.Done(false), TakePlan.next(List.of(new TakePlan.Slot(0, box, 1, 64, true)), STONE,
            need(STONE, 10L, box, 2L)), "nor as another material the build still needs");
    }

    @Test
    void theMaterialThereOnlyWithItemsInsideIsSaidApartFromAStaleContainer() {
        // Deferred L86: the trip tells the player why it moves on — the container has the material, but only filled.
        String box = "minecraft:red_shulker_box";
        assertEquals(new TakePlan.Done(false, true), TakePlan.next(List.of(new TakePlan.Slot(0, box, 1, 64, true),
            new TakePlan.Slot(1, DIRT, 64, 64)), box, need(box, 2L)));
        assertEquals(new TakePlan.Done(false, false), TakePlan.next(List.of(new TakePlan.Slot(0, box, 1, 64, true)),
            STONE, need(STONE, 10L)), "a filled box of another material leaves the trip's one simply missing");
        assertEquals(new TakePlan.Done(false, false), TakePlan.next(List.of(new TakePlan.Slot(0, DIRT, 64, 64)), box,
            need(box, 2L)), "no box at all: stale");
        assertEquals(new TakePlan.Done(true, false), TakePlan.next(List.of(new TakePlan.Slot(0, box, 1, 64, true),
            new TakePlan.Slot(4, box, 1, 64, false)), box, need()), "an empty one there: the material is there");
    }

    @Test
    void aTieGoesToTheLowerSlot() {
        assertEquals(new TakePlan.Click(2), TakePlan.next(List.of(new TakePlan.Slot(5, STONE, 64, 128),
            new TakePlan.Slot(2, STONE, 64, 128)), STONE, need(STONE, 10L)));
    }

    private static final String BOX = "minecraft:shulker_box";
    private static final String RED_BOX = "minecraft:red_shulker_box";
    private static final String GLASS = "minecraft:glass";

    @Test
    void anEmptyBorrowedBoxGoesBackFirst() {
        // Owner ruling R44: the adapter lists only empty boxes of a kind borrowed from this container.
        assertEquals(new TakePlan.Click(40, true), TakePlan.next(CHEST, List.of(new TakePlan.Slot(40, BOX, 1, 1)),
            STONE, need(STONE, 10L), true));
    }

    @Test
    void aFullContainerKeepsTheBorrowedBox() {
        assertEquals(new TakePlan.Click(1), TakePlan.next(CHEST, List.of(new TakePlan.Slot(40, BOX, 1, 0)), STONE,
            need(STONE, 10L), true));
    }

    @Test
    void theBoxHoldingTheMostIsCarriedWhenNoLooseStackIs() {
        List<TakePlan.Slot> chest = List.of(new TakePlan.Slot(3, RED_BOX, 1, 1, Map.of(STONE, 64)),
            new TakePlan.Slot(4, BOX, 1, 1, Map.of(STONE, 1728)), new TakePlan.Slot(5, DIRT, 64, 64));
        assertEquals(new TakePlan.Click(4, true), TakePlan.next(chest, List.of(), STONE, need(STONE, 10L), true));
        assertEquals(new TakePlan.Done(false), TakePlan.next(chest, STONE, need(STONE, 10L)), "phase A never carries one");
    }

    @Test
    void aLooseStackComesBeforeABox() {
        // Ruling R40 inside the container: a loose stack is one click, a box is a carry and an unpack at the build.
        List<TakePlan.Slot> chest = List.of(new TakePlan.Slot(2, STONE, 5, 64),
            new TakePlan.Slot(4, BOX, 1, 1, Map.of(STONE, 1728)));
        assertEquals(new TakePlan.Click(2, false), TakePlan.next(chest, List.of(), STONE, need(STONE, 10L), true),
            "a loose stack's click is no box click");
    }

    @Test
    void aBoxWithNoRoomToCarryItIsNothingFits() {
        List<TakePlan.Slot> chest = List.of(new TakePlan.Slot(4, BOX, 1, 0, Map.of(STONE, 1728)));
        assertEquals(new TakePlan.NothingFits(), TakePlan.next(chest, List.of(), STONE, need(STONE, 10L), true));
    }

    @Test
    void aBoxOfOtherBlocksDoesNotHoldTheMaterial() {
        List<TakePlan.Slot> chest = List.of(new TakePlan.Slot(4, BOX, 1, 1, Map.of(DIRT, 1728)));
        assertEquals(new TakePlan.Done(false), TakePlan.next(chest, List.of(), STONE, need(STONE, 10L), true));
    }

    @Test
    void aBoxIsCarriedForAnotherMaterialStillNeeded() {
        List<TakePlan.Slot> chest = List.of(new TakePlan.Slot(0, STONE, 64, 0),
            new TakePlan.Slot(4, BOX, 1, 1, Map.of(GLASS, 64)));
        assertEquals(new TakePlan.Click(4, true), TakePlan.next(chest, List.of(), STONE, need(STONE, 10L, GLASS, 5L),
            true));
    }

    @Test
    void aFilledBoxRestockMayNotCarryStaysFilledOnly() {
        // Pre-flight 17-3: a filled box whose own item the build places comes with no contents from the adapter —
        // carried, the printer could place it, contents and all. It stays "filled only", as in phase A (ruling R34).
        List<TakePlan.Slot> chest = List.of(new TakePlan.Slot(0, RED_BOX, 1, 1, true));
        assertEquals(new TakePlan.Done(false, true), TakePlan.next(chest, List.of(), RED_BOX,
            need(RED_BOX, 2L, STONE, 10L), true));
    }

    @Test
    void theRoomKeepsTheSlotTheBoxComesBackTo() {
        assertEquals(64, TakePlan.room(0, 2, 64, 64, 1), "two empty slots, one kept: a whole stack fits");
        assertEquals(0, TakePlan.room(0, 1, 64, 64, 1), "the only empty slot is kept");
        assertEquals(0, TakePlan.room(10, 1, 64, 20, 1), "20 do not fit in 10 without the kept slot: vanilla would use it");
        assertEquals(10, TakePlan.room(10, 1, 64, 10, 1), "10 fit in the partial stacks");
        assertEquals(10, TakePlan.room(10, 0, 64, 20, 0), "no reserve: phase A tops up what fits");
        assertEquals(138, TakePlan.room(10, 2, 64, 64, 0));
    }

    @Test
    void aBoxIsCarriedOnlyWithAFreeHotbarSlotAndOneMore() {
        assertEquals(1, TakePlan.carryRoom(1, 2));
        assertEquals(0, TakePlan.carryRoom(0, 5), "vanilla would put it in the main inventory");
        assertEquals(0, TakePlan.carryRoom(1, 1), "nothing left for the take at the build");
    }

    @Test
    void aSlotWithContentsHoldsItems() {
        assertThrows(IllegalArgumentException.class, () -> new TakePlan.Slot(0, BOX, 1, 1, false, Map.of(STONE, 1)));
        assertTrue(new TakePlan.Slot(0, BOX, 1, 1, Map.of(STONE, 1)).holdsItems());
    }

    @Test
    void aGiveBackOfNothingIsNotClicked() {
        assertEquals(new TakePlan.Click(1), TakePlan.next(CHEST, List.of(new TakePlan.Slot(40, BOX, 0, 5)), STONE,
            need(STONE, 10L), true));
    }

    @Test
    void aReserveStillUsesThePartialStacks() {
        assertEquals(10, TakePlan.room(10, 0, 64, 10, 1), "no empty slot, one kept: the partial stacks take 10");
    }

    @Test
    void twoBoxesHoldingTheSameGoToTheLowerSlot() {
        List<TakePlan.Slot> chest = List.of(new TakePlan.Slot(7, BOX, 1, 1, Map.of(STONE, 64)),
            new TakePlan.Slot(3, RED_BOX, 1, 1, Map.of(STONE, 64)));
        assertEquals(new TakePlan.Click(3, true), TakePlan.next(chest, List.of(), STONE, need(STONE, 10L), true));
    }
}
