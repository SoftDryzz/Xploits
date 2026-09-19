package com.xploits.stash.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class ContainerKeyTest {
    @Test
    void blockKeyKeepsDimensionAndPosition() {
        ContainerKey key = ContainerKey.block("overworld", -1234, 63, 5678);
        assertEquals("overworld", key.dimension());
        assertEquals(-1234, key.x());
        assertEquals(63, key.y());
        assertEquals(5678, key.z());
    }

    @Test
    void doubleChestGivesSameKeyWhicheverHalfYouOpen() {
        ContainerKey left = ContainerKey.doubleChest("overworld", 10, 64, 5, 11, 64, 5);
        ContainerKey right = ContainerKey.doubleChest("overworld", 11, 64, 5, 10, 64, 5);
        assertEquals(left, right);
        assertEquals(10, left.x());
    }

    @Test
    void doubleChestOrdersByXThenYThenZ() {
        assertEquals(ContainerKey.block("overworld", 4, 70, 9),
            ContainerKey.doubleChest("overworld", 4, 70, 9, 4, 70, 10));
        assertEquals(ContainerKey.block("overworld", 4, 70, 9),
            ContainerKey.doubleChest("overworld", 4, 70, 10, 4, 70, 9));
    }

    @Test
    void sameCoordinatesInDifferentDimensionsAreDifferentContainers() {
        assertNotEquals(ContainerKey.block("overworld", 0, 64, 0),
            ContainerKey.block("the_nether", 0, 64, 0));
    }

    @Test
    void enderIsOneSingleKey() {
        assertEquals("ender", ContainerKey.ENDER.id());
    }

    @Test
    void idIsStableAndParseableByEye() {
        assertEquals("overworld@-1234,63,5678", ContainerKey.block("overworld", -1234, 63, 5678).id());
    }

    @Test
    void doubleChestWithSameXOrdersByYWhicheverHalfYouOpen() {
        assertEquals(ContainerKey.block("overworld", 4, 70, 9),
            ContainerKey.doubleChest("overworld", 4, 70, 9, 4, 71, 9));
        assertEquals(ContainerKey.block("overworld", 4, 70, 9),
            ContainerKey.doubleChest("overworld", 4, 71, 9, 4, 70, 9));
    }
}
