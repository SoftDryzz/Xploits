package com.xploits.bench.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task A1 requirement 3, task A4 requirement 1: how a fight-mode run's {@code result} and {@code net_pops}
 * come out of what happened in it, and how a side's totem supply running out counts as a loss. Pure and
 * deterministic.
 */
class FightResultTest {
    @Test
    void theSparringLosingFirstIsAWin() {
        assertEquals(1, FightResult.result(false, true));
    }

    @Test
    void ourOwnLossIsALoss() {
        assertEquals(-1, FightResult.result(true, false));
    }

    @Test
    void theTimeLimitWithNeitherLostIsADraw() {
        assertEquals(0, FightResult.result(false, false));
    }

    @Test
    void bothLostInTheSameJudgementIsRefused() {
        // The bench stops the run at the first side to lose: this pure core never has to pick a winner between them.
        assertThrows(IllegalArgumentException.class, () -> FightResult.result(true, true));
    }

    @Test
    void netPopsIsDealtMinusTaken() {
        assertEquals(3, FightResult.netPops(5, 2));
        assertEquals(-2, FightResult.netPops(1, 3));
        assertEquals(0, FightResult.netPops(0, 0));
    }

    @Test
    void outOfTotemsIsFalseBelowTheTotal() {
        for (int pops = 0; pops < 8; pops++) assertFalse(FightResult.outOfTotems(pops, 8), "pops " + pops);
    }

    @Test
    void outOfTotemsIsTrueAtTheTotal() {
        assertTrue(FightResult.outOfTotems(8, 8));
    }

    @Test
    void outOfTotemsStaysTrueBeyondTheTotal() {
        // A side's pop count never actually exceeds its own supply, but the check must not assume that.
        assertTrue(FightResult.outOfTotems(9, 8));
    }

    @Test
    void outOfTotemsIsRelativeToTheTotalGiven() {
        assertFalse(FightResult.outOfTotems(3, 4));
        assertTrue(FightResult.outOfTotems(4, 4));
    }
}
