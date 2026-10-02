package com.xploits.restock.core;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Owner ruling R44: a box the player carries that holds the block that ran out is unpacked before any trip. */
class UnpackChoiceTest {
    private static final String STONE = "minecraft:stone";
    private static final String GLASS = "minecraft:glass";
    private static final BorrowedShulkers.Kind PLAIN = new BorrowedShulkers.Kind("minecraft:shulker_box", "");
    private static final BorrowedShulkers.Kind KIT = new BorrowedShulkers.Kind("minecraft:red_shulker_box", "kit");

    /** A carried box in {@code slot}: item id and count pairs inside. */
    private static UnpackChoice.Carried box(int slot, BorrowedShulkers.Kind kind, boolean borrowed, Object... inside) {
        Map<String, Integer> m = new HashMap<>();
        for (int i = 0; i < inside.length; i += 2) m.put((String) inside[i], (Integer) inside[i + 1]);
        return new UnpackChoice.Carried(slot, kind, m, borrowed);
    }

    private static UnpackChoice.Inventory inv(int empty, int emptyHotbar, UnpackChoice.Carried... boxes) {
        return new UnpackChoice.Inventory(Map.of(), List.of(boxes), empty, emptyHotbar);
    }

    @Test
    void aCarriedBoxHoldingTheMaterialIsUnpackedBeforeAnyTrip() {
        assertEquals(new UnpackChoice.Unpack(STONE, 2, KIT),
            UnpackChoice.choose(List.of(STONE), inv(5, 2, box(2, KIT, false, STONE, 64)), true, Set.of()));
    }

    @Test
    void withTheSettingOffOnlyBorrowedBoxesCount() {
        assertEquals(new UnpackChoice.None(List.of()),
            UnpackChoice.choose(List.of(STONE), inv(5, 2, box(2, KIT, false, STONE, 64)), false, Set.of()));
        assertEquals(new UnpackChoice.Unpack(STONE, 2, PLAIN),
            UnpackChoice.choose(List.of(STONE), inv(5, 2, box(2, PLAIN, true, STONE, 64)), false, Set.of()));
    }

    @Test
    void theHotbarFirstThenTheFullestThenTheLowerSlot() {
        assertEquals(new UnpackChoice.Unpack(STONE, 5, PLAIN), UnpackChoice.choose(List.of(STONE),
            inv(5, 2, box(20, PLAIN, false, STONE, 1728), box(5, PLAIN, false, STONE, 64)), true, Set.of()),
            "no click to move one from the hotbar");
        assertEquals(new UnpackChoice.Unpack(STONE, 4, PLAIN), UnpackChoice.choose(List.of(STONE),
            inv(5, 2, box(5, PLAIN, false, STONE, 64), box(4, PLAIN, false, STONE, 128)), true, Set.of()));
        assertEquals(new UnpackChoice.Unpack(STONE, 3, PLAIN), UnpackChoice.choose(List.of(STONE),
            inv(5, 2, box(5, PLAIN, false, STONE, 64), box(3, PLAIN, false, STONE, 64)), true, Set.of()));
    }

    @Test
    void aBoxInTheMainInventoryNeedsAFreeHotbarSlot() {
        assertEquals(new UnpackChoice.None(List.of(STONE)),
            UnpackChoice.choose(List.of(STONE), inv(5, 0, box(20, PLAIN, false, STONE, 64)), true, Set.of()),
            "said once, then the containers");
        assertEquals(new UnpackChoice.Unpack(STONE, 20, PLAIN),
            UnpackChoice.choose(List.of(STONE), inv(5, 1, box(20, PLAIN, false, STONE, 64)), true, Set.of()),
            "ruling R43 moves it into the free slot");
    }

    @Test
    void aFullInventoryUnpacksNothing() {
        assertEquals(new UnpackChoice.None(List.of()),
            UnpackChoice.choose(List.of(STONE), inv(0, 0, box(2, PLAIN, false, STONE, 64)), true, Set.of()));
    }

    @Test
    void aMaterialWhoseUnpackGaveNothingGoesToTheContainers() {
        UnpackChoice.Inventory both = inv(5, 2, box(2, PLAIN, false, STONE, 64), box(3, KIT, false, GLASS, 64));
        assertEquals(new UnpackChoice.None(List.of()), UnpackChoice.choose(List.of(STONE), both, true, Set.of(STONE)));
        assertEquals(new UnpackChoice.Unpack(GLASS, 3, KIT),
            UnpackChoice.choose(List.of(STONE, GLASS), both, true, Set.of(STONE)));
    }

    @Test
    void theFirstDueMaterialWithABoxWins() {
        assertEquals(new UnpackChoice.Unpack(GLASS, 3, KIT), UnpackChoice.choose(List.of(STONE, GLASS),
            inv(5, 2, box(3, KIT, false, GLASS, 64)), true, Set.of()), "carried first, before any trip for stone");
        assertEquals(new UnpackChoice.Unpack(GLASS, 3, KIT), UnpackChoice.choose(List.of(GLASS, STONE),
            inv(5, 2, box(2, PLAIN, false, STONE, 64), box(3, KIT, false, GLASS, 64)), true, Set.of()));
    }

    @Test
    void whatATripCountsAsCarriedIsWhatWouldBeUnpackedFirst() {
        UnpackChoice.Inventory kit = new UnpackChoice.Inventory(Map.of(STONE, 5),
            List.of(box(2, KIT, false, STONE, 64, GLASS, 10)), 5, 2);
        assertEquals(Map.of(STONE, 69, GLASS, 10), UnpackChoice.available(kit, true, Set.of()));
        assertEquals(Map.of(STONE, 5), UnpackChoice.available(kit, false, Set.of()), "the player's own, setting off");
        assertEquals(Map.of(STONE, 69), UnpackChoice.available(kit, true, Set.of(GLASS)), "an unpack of glass gave nothing");
        UnpackChoice.Inventory mainFull = new UnpackChoice.Inventory(Map.of(STONE, 5),
            List.of(box(20, KIT, false, STONE, 64)), 5, 0);
        assertEquals(Map.of(STONE, 5), UnpackChoice.available(mainFull, true, Set.of()), "it could not be unpacked now");
        UnpackChoice.Inventory full = new UnpackChoice.Inventory(Map.of(STONE, 5),
            List.of(box(2, KIT, false, STONE, 64)), 0, 0);
        assertEquals(Map.of(STONE, 5), UnpackChoice.available(full, true, Set.of()));
    }
}
