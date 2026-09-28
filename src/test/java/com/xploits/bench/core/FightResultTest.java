package com.xploits.bench.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Task A1 requirement 3: how a fight-mode run's {@code result} and {@code net_pops} come out of what
 * happened in it. Pure and deterministic.
 */
class FightResultTest {
    @Test
    void theSparringDyingFirstIsAWin() {
        assertEquals(1, FightResult.result(false, true));
    }

    @Test
    void ourOwnDeathIsALoss() {
        assertEquals(-1, FightResult.result(true, false));
    }

    @Test
    void theTimeLimitWithNeitherDeadIsADraw() {
        assertEquals(0, FightResult.result(false, false));
    }

    @Test
    void bothDeadInTheSameJudgementIsRefused() {
        // The bench stops the run at the first death: this pure core never has to pick a winner between them.
        assertThrows(IllegalArgumentException.class, () -> FightResult.result(true, true));
    }

    @Test
    void netPopsIsDealtMinusTaken() {
        assertEquals(3, FightResult.netPops(5, 2));
        assertEquals(-2, FightResult.netPops(1, 3));
        assertEquals(0, FightResult.netPops(0, 0));
    }
}
