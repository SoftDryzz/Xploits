package com.xploits.stash.core;

import com.xploits.console.core.CoordinateSentinel;
import com.xploits.shared.core.i18n.Catalog;
import com.xploits.shared.core.i18n.Language;
import com.xploits.shared.core.i18n.Msg;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

    @Test
    void withoutPositionSaysDimensionAndDistanceAndNothingElse() {
        ContainerKey chest = ContainerKey.block("minecraft:overworld", 300, 64, 400);
        assertEquals(Msg.of(StashText.WHERE_DISTANCE, "dimension", "overworld", "blocks", 500L),
            chest.withoutPosition("minecraft:overworld", 0.0, 0.0));
        assertEquals(Msg.of(StashText.WHERE_DIMENSION, "dimension", "overworld"),
            chest.withoutPosition("minecraft:the_nether", 0.0, 0.0));
        assertEquals(Msg.of(StashText.WHERE_DIMENSION, "dimension", "overworld"), chest.withoutPosition(null, null, null));
        assertEquals(Msg.of(StashText.WHERE_ENDER), ContainerKey.ENDER.withoutPosition("minecraft:overworld", 0.0, 0.0));
        Catalog es = Catalog.load(Language.ES, problem -> {
            throw new AssertionError(problem);
        });
        assertFalse(CoordinateSentinel.isSuspect(es.render(chest.withoutPosition("minecraft:overworld", 0.0, 0.0))));
    }
}
