package com.xploits.bench.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task A3 (city): the timer behind SurroundMiner's re-mining of one of our hole's four wall blocks. The first
 * mine takes {@link MiningSchedule#CITY_FIRST_BREAK} ticks; every later one, after the gap has been refilled,
 * takes only {@link MiningSchedule#CITY_REBREAK} — an "instant" mine next to the first. Pure and deterministic.
 */
class MiningScheduleTest {
    @Test
    void theConstantsAreTheBriefsExactValues() {
        assertEquals(40, MiningSchedule.CITY_FIRST_BREAK);
        assertEquals(2, MiningSchedule.CITY_REBREAK);
    }

    @Test
    void nothingIsDueBeforeItIsEverArmed() {
        MiningSchedule s = new MiningSchedule();
        assertFalse(s.armed());
        for (int tick = 0; tick < 1000; tick++) assertFalse(s.due(tick));
    }

    @Test
    void theFirstArmWaitsCityFirstBreakTicks() {
        MiningSchedule s = new MiningSchedule();
        s.arm(100);
        assertTrue(s.armed());
        for (int tick = 100; tick < 140; tick++) assertFalse(s.due(tick), "tick " + tick + " is too early");
        assertTrue(s.due(140));
    }

    @Test
    void firingConsumesTheArming() {
        MiningSchedule s = new MiningSchedule();
        s.arm(0);
        assertTrue(s.due(40));
        assertFalse(s.armed());
        assertFalse(s.due(41), "it must not fire twice for the same arm");
    }

    @Test
    void aLaterArmWaitsOnlyCityRebreakTicks() {
        MiningSchedule s = new MiningSchedule();
        s.arm(0);
        assertTrue(s.due(40));
        s.arm(40);
        assertFalse(s.due(41), "tick 41 is too early: the rebreak needs 2 ticks, not 40");
        assertTrue(s.due(42));
    }

    @Test
    void reArmingBeforeItFiresReplacesThePendingOneAndStillUsesRebreak() {
        // The second arm() ever called always uses CITY_REBREAK, whether or not the first one ever fired:
        // the pure class only tracks "has arm() ever run before", not whether it ever became due.
        MiningSchedule s = new MiningSchedule();
        s.arm(0); // would be due at 40 (CITY_FIRST_BREAK), but is replaced below before that
        s.arm(5); // the second-ever arm: CITY_REBREAK from tick 5
        assertFalse(s.due(6));
        assertTrue(s.due(7));
    }

    @Test
    void armedReportsThePendingStateUntilItFires() {
        MiningSchedule s = new MiningSchedule();
        assertFalse(s.armed());
        s.arm(10);
        assertTrue(s.armed());
        assertFalse(s.due(15), "not yet due");
        assertTrue(s.armed(), "still pending while not yet due");
        assertTrue(s.due(50));
        assertFalse(s.armed(), "consumed once it fired");
    }

    @Test
    void aLateCheckStillFiresOnceAndOnlyOnce() {
        // A tick can be skipped in principle; due() must still catch up and fire exactly once.
        MiningSchedule s = new MiningSchedule();
        s.arm(0);
        assertTrue(s.due(50));
        assertFalse(s.due(51));
    }
}
