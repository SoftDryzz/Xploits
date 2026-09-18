package com.kitbot.kitrequester.core;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class KitQueueTest {
    private static final String COPIED = """
        Prioridad alta (3)
        #285 Stash Kit
        #387 Stacked Bundles
        #156 Bundle

        Prioridad media (1)
        #404 Horse Armor Kit
        """;

    private static KitQueue oneToSeven() {
        return KitQueue.parse("#1 a\n#2 b\n#3 c\n#4 d\n#5 e\n#6 f\n#7 g\n");
    }

    @Test
    void parsesCopyPendingFormat() {
        assertEquals(List.of(285, 387, 156, 404), KitQueue.parse(COPIED).ids());
    }

    @Test
    void acceptsBareNumbersAndIgnoresNoise() {
        assertEquals(List.of(12, 7), KitQueue.parse("12\n  #7 x\nhola\n#abc\n\n285, 387\n").ids());
    }

    @Test
    void ignoresAbsurdlyLongNumbers() {
        assertEquals(List.of(), KitQueue.parse("#1234567 x\n").ids());
    }

    @Test
    void dropsDuplicatesKeepingFirst() {
        assertEquals(List.of(5, 6), KitQueue.parse("#5 a\n#6 b\n#5 c\n").ids());
    }

    @Test
    void batchesOfFiveWithShortLastBatch() {
        KitQueue q = oneToSeven();
        assertEquals(List.of(1, 2, 3, 4, 5), q.nextBatch(Set.of()));
        assertEquals(List.of(6, 7), q.nextBatch(Set.of(1, 2, 3, 4, 5)));
        assertEquals(List.of(), q.nextBatch(Set.of(1, 2, 3, 4, 5, 6, 7)));
    }

    @Test
    void pendingSkipsResolvedKeepingOrder() {
        KitQueue q = oneToSeven();
        assertEquals(List.of(1, 3, 5, 6, 7), q.pending(Set.of(2, 4)));
        assertEquals(List.of(1, 3, 5, 6, 7), q.nextBatch(Set.of(2, 4)));
    }

    @Test
    void unresolvedBatchComesBackFirst() {
        KitQueue q = oneToSeven();
        assertEquals(q.nextBatch(Set.of()), q.nextBatch(Set.of()));
    }
}
