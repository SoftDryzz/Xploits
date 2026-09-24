package com.xploits.sweep.core;

import com.xploits.shared.core.i18n.Catalog;
import com.xploits.shared.core.i18n.Language;
import com.xploits.shared.core.i18n.Msg;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests of the sweep's coverage tally: at the end it must be known <b>how much was looked at</b> and
 * not only that it finished (Nether Sweep spec §9).
 *
 * <p>All the coordinates in these tests are made up and small: what is checked is rectangle
 * arithmetic, not any concrete place in the world.
 */
class SweepTallyTest {
    private static final Catalog ES = Catalog.load(Language.ES, p -> {
        throw new AssertionError(p);
    });

    /** A text as the player reads it in Spanish, for the tests that look for words and numbers in it. */
    private static String es(Msg msg) {
        return msg == null ? "null" : ES.render(msg);
    }

    private static final SweepArea AREA = new SweepArea(0, 0, 9, 9);

    @Test
    void withoutPriorCoverageOrReceivedChunksNothingIsCovered() {
        SweepTally tally = SweepTally.of(AREA, Coverage.empty());

        assertEquals(100, tally.areaChunks());
        assertEquals(0, tally.alreadySeen());
        assertEquals(0, tally.arrived());
        assertEquals(0, tally.covered());
        assertEquals(100, tally.missing());
        assertEquals(0.0, tally.coveredFraction(), 1e-9);
    }

    @Test
    void priorCoverageOfTheAreaCountsFromTheStart() {
        // The planner skips the bands already fully seen, so their chunks are not going to arrive
        // during the flight: counting them as gaps would make every sweep over a half-known area
        // look like a failure.
        SweepTally tally = SweepTally.of(AREA, coverRow(0));

        assertEquals(10, tally.alreadySeen());
        assertEquals(10, tally.covered());
        assertEquals(0, tally.arrived());
        assertEquals(90, tally.missing());
    }

    @Test
    void priorCoverageOutsideTheAreaDoesNotCount() {
        Coverage outside = Coverage.ofLines(List.of("500,500", "-40,7"));

        SweepTally tally = SweepTally.of(AREA, outside);

        assertEquals(0, tally.alreadySeen());
        assertEquals(100, tally.missing());
    }

    @Test
    void aChunkReceivedInsideTheAreaCounts() {
        SweepTally tally = SweepTally.of(AREA, Coverage.empty());

        assertTrue(tally.record(4, 4));

        assertEquals(1, tally.covered());
        assertEquals(1, tally.arrived());
        assertEquals(99, tally.missing());
    }

    @Test
    void aChunkReceivedOutsideTheAreaDoesNotCount() {
        // During the approach thousands arrive, and none of them is terrain of the requested rectangle.
        SweepTally tally = SweepTally.of(AREA, Coverage.empty());

        assertFalse(tally.record(-1, 4));
        assertFalse(tally.record(4, 10));
        assertFalse(tally.record(1_000, 1_000));

        assertEquals(0, tally.covered());
    }

    @Test
    void theSameChunkTwiceDoesNotCountTwice() {
        // The server resends chunks when passing over them again: without this, a sweep that passed
        // twice over half a band would announce more coverage than there is.
        SweepTally tally = SweepTally.of(AREA, Coverage.empty());

        assertTrue(tally.record(4, 4));
        assertFalse(tally.record(4, 4));

        assertEquals(1, tally.covered());
    }

    @Test
    void aChunkAlreadySeenIsNotCountedAgainOnArrival() {
        SweepTally tally = SweepTally.of(AREA, coverRow(0));

        assertFalse(tally.record(0, 3));

        assertEquals(10, tally.covered());
        assertEquals(0, tally.arrived());
    }

    @Test
    void eachCornerOfTheAreaHasItsOwnSlot() {
        // If the BitSet index got misaligned, two different chunks would share a bit and the final
        // count would silently lie, which is exactly the failure this class exists not to repeat.
        SweepArea rectangle = new SweepArea(-5, 7, -2, 11);
        SweepTally tally = SweepTally.of(rectangle, Coverage.empty());

        for (int x = -5; x <= -2; x++) {
            for (int z = 7; z <= 11; z++) {
                assertTrue(tally.record(x, z), "chunk " + x + "," + z + " had to be new");
            }
        }

        assertEquals(rectangle.chunkCount(), tally.covered());
        assertEquals(0, tally.missing());
    }

    @Test
    void aSweepThatHasNotSeenHalfStaysBelowTheFloor() {
        SweepTally tally = SweepTally.of(AREA, Coverage.empty());
        for (int x = 0; x <= 4; x++) {
            for (int z = 0; z <= 9; z++) {
                tally.record(x, z);
            }
        }

        assertEquals(0.5, tally.coveredFraction(), 1e-9);
        assertTrue(tally.shortOfCoverage(0.95));
        assertFalse(tally.shortOfCoverage(0.5), "the floor is a minimum, not a strict threshold");
    }

    @Test
    void aFullyCoveredAreaFallsShortOfNoFloor() {
        SweepTally tally = SweepTally.of(AREA, Coverage.empty());
        for (int x = 0; x <= 9; x++) {
            for (int z = 0; z <= 9; z++) {
                tally.record(x, z);
            }
        }

        assertEquals(1.0, tally.coveredFraction(), 1e-9);
        assertFalse(tally.shortOfCoverage(1.0));
        assertTrue(es(tally.summary()).contains("100 %"));
        assertTrue(es(tally.summary()).contains("No falta ninguno"));
    }

    @Test
    void thePercentageRoundsDownSoItNeverAnnouncesAHundredWithGaps() {
        // 999 of 1,000 is 99.9%: rounded to the nearest it would come out as "100 %" with one chunk
        // unseen, which is the same old lie worded by the rounding.
        SweepArea thousand = new SweepArea(0, 0, 24, 39);
        SweepTally tally = SweepTally.of(thousand, Coverage.empty());
        int recorded = 0;
        for (int x = 0; x <= 24 && recorded < 999; x++) {
            for (int z = 0; z <= 39 && recorded < 999; z++) {
                tally.record(x, z);
                recorded++;
            }
        }

        assertEquals(1, tally.missing());
        assertTrue(es(tally.summary()).contains("99 %"), es(tally.summary()));
        assertFalse(es(tally.summary()).contains("100 %"), es(tally.summary()));
    }

    @Test
    void theSummarySaysHowManyArrivedOutOfHowManyAndWhereTheyCameFrom() {
        SweepTally tally = SweepTally.of(AREA, coverRow(0));
        tally.record(5, 5);
        tally.record(5, 6);

        String summary = es(tally.summary());

        assertTrue(summary.contains("12 de 100"), summary);
        assertTrue(summary.contains("12 %"), summary);
        assertTrue(summary.contains("10 que ya estaban"), summary);
        assertTrue(summary.contains("2 que han llegado"), summary);
        assertTrue(summary.contains("88 chunks"), summary);
    }

    @Test
    void withoutAreaOrPriorCoverageItThrowsInsteadOfCountingOverNothing() {
        assertThrows(NullPointerException.class, () -> SweepTally.of(null, Coverage.empty()));
        assertThrows(NullPointerException.class, () -> SweepTally.of(AREA, null));
    }

    /** A prior coverage with the whole row {@code x} of the area already seen. */
    private static Coverage coverRow(int x) {
        List<String> lines = new ArrayList<>();
        for (int z = 0; z <= 9; z++) {
            lines.add(x + "," + z);
        }
        return Coverage.ofLines(lines);
    }
}
