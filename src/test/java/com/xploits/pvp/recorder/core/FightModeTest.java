package com.xploits.pvp.recorder.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FightModeTest {
    @Test
    void noSecondOfAutoPvpIsManual() {
        assertEquals(FightMode.MANUAL, FightMode.of(0, 43));
        assertEquals(FightMode.MANUAL, FightMode.of(0, 0));
    }

    @Test
    void ninetyPercentIsAutoPvpAndOneSecondLessIsMixed() {
        assertEquals(FightMode.AUTO_PVP, FightMode.of(27, 30));
        assertEquals(FightMode.MIXED, FightMode.of(26, 30));
        assertEquals(FightMode.AUTO_PVP, FightMode.of(9, 10));
        assertEquals(FightMode.MIXED, FightMode.of(8, 10));
        assertEquals(FightMode.AUTO_PVP, FightMode.of(43, 43));
        assertEquals(FightMode.MIXED, FightMode.of(1, 43));
    }
}
