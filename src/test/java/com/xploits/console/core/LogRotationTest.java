package com.xploits.console.core;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LogRotationTest {
    private static final LocalDate TODAY = LocalDate.of(2026, 9, 23);
    private static final long MIB = 1L << 20;

    @Test
    void theLiveLogLimitIsOneMebibyte() {
        assertEquals(1_048_576L, LogRotation.LIVE_LOG_LIMIT);
        assertFalse(LogRotation.shouldRotateLiveLog(1_048_000, 576));
        assertTrue(LogRotation.shouldRotateLiveLog(1_048_000, 577));
    }

    @Test
    void thirtyDaysAreKept() {
        List<LogRotation.LogFile> files = List.of(
            new LogRotation.LogFile("2026-08-25.log", 1, LocalDate.of(2026, 8, 25)),
            new LogRotation.LogFile("2026-08-24.log", 1, LocalDate.of(2026, 8, 24)),
            new LogRotation.LogFile("2026-09-23.log", 1, TODAY));
        assertEquals(List.of("2026-08-24.log"), LogRotation.toDelete(files, TODAY));
    }

    @Test
    void overSixtyFourMebibytesTheOldestIsDeleted() {
        List<LogRotation.LogFile> files = List.of(
            new LogRotation.LogFile("2026-09-22.log", 30 * MIB, LocalDate.of(2026, 9, 22)),
            new LogRotation.LogFile("2026-09-21.log", 30 * MIB, LocalDate.of(2026, 9, 21)),
            new LogRotation.LogFile("2026-09-23.log", 30 * MIB, TODAY));
        assertEquals(List.of("2026-09-21.log"), LogRotation.toDelete(files, TODAY));
    }

    @Test
    void todaysFileIsNeverDeleted() {
        List<LogRotation.LogFile> files = List.of(new LogRotation.LogFile("2026-09-23.log", 100 * MIB, TODAY));
        assertEquals(List.of(), LogRotation.toDelete(files, TODAY));
    }

    @Test
    void theDateComesFromTheNameAndTheRestIsIgnored() {
        assertEquals(Optional.of(TODAY), LogRotation.dateOf("2026-09-23.log"));
        assertEquals(Optional.empty(), LogRotation.dateOf("notes.txt"));
        assertEquals(Optional.empty(), LogRotation.dateOf("2026-13-40.log"));
        assertEquals("2026-09-23.log", LogRotation.fileNameFor(TODAY));
    }
}
