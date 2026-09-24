package com.xploits.sweep.core;

import com.xploits.shared.core.i18n.Catalog;
import com.xploits.shared.core.i18n.Language;
import com.xploits.shared.core.i18n.Msg;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SweepAreaTest {
    private static final Catalog ES = Catalog.load(Language.ES, p -> {
        throw new AssertionError(p);
    });

    /** A text as the player reads it in Spanish, for the tests that look for words and numbers in it. */
    private static String es(Msg msg) {
        return msg == null ? "null" : ES.render(msg);
    }

    @Test
    void widthAndHeightCountBothChunkEdgesInclusive() {
        SweepArea area = SweepArea.ofChunks(0, 0, 3, 3);
        assertEquals(4, area.widthInChunks());
        assertEquals(4, area.heightInChunks());
        assertEquals(16, area.chunkCount());
    }

    @Test
    void aSingleChunkIsStillOneByOne() {
        SweepArea area = SweepArea.ofChunks(5, -2, 5, -2);
        assertEquals(1, area.widthInChunks());
        assertEquals(1, area.heightInChunks());
        assertEquals(1, area.chunkCount());
    }

    @Test
    void givingTheGreaterCornerFirstDoesNotProduceAnEmptyArea() {
        // It is the most likely slip: min and max swapped on each axis.
        SweepArea straight = SweepArea.ofChunks(0, 0, 3, 3);
        SweepArea swapped = SweepArea.ofChunks(3, 3, 0, 0);
        assertEquals(straight, swapped);
    }

    @Test
    void cornersNormalizePerAxisIndependently() {
        // One axis reversed and the other not must also be normalised.
        SweepArea area = SweepArea.ofChunks(4, -1, -2, 5);
        assertEquals(-2, area.minChunkX());
        assertEquals(4, area.maxChunkX());
        assertEquals(-1, area.minChunkZ());
        assertEquals(5, area.maxChunkZ());
    }

    @Test
    void overworldEquivalentMultipliesChunksBySixteenAndThenByEight() {
        // 4 chunks wide/tall in the Nether: 4 * 16 * 8 = 512 blocks in the Overworld.
        SweepArea area = SweepArea.ofChunks(0, 0, 3, 3);
        assertEquals(Msg.of(SweepText.OVERWORLD_EQUIVALENT, "width", 512L, "height", 512L), area.overworldEquivalent());
    }

    @Test
    void overworldEquivalentIsIndependentPerAxis() {
        SweepArea area = SweepArea.ofChunks(0, 0, 1, 3);
        assertEquals(Msg.of(SweepText.OVERWORLD_EQUIVALENT, "width", 256L, "height", 512L), area.overworldEquivalent());
    }

    @Test
    void chunkCountFitsAsAnIntForALargeButRealisticArea() {
        // 40000x40000 chunks: really big, but the product (1,600,000,000) still fits in an int, so
        // it must neither throw nor overflow.
        SweepArea area = SweepArea.ofChunks(0, 0, 39_999, 39_999);
        assertEquals(1_600_000_000, area.chunkCount());
    }

    @Test
    void chunkCountRejectsAnAreaTooBigToCountAsAnInt() {
        // 50001x50001 chunks: typeable, far below the real world limit (~±3,750,000 in chunks), but
        // the product (2,500,100,001) no longer fits in an int. Before the fix this silently
        // overflowed to -1,794,867,295; now it must fail loudly, not return a valid-looking
        // negative number.
        SweepArea area = SweepArea.ofChunks(0, 0, 50_000, 50_000);
        assertThrows(ArithmeticException.class, area::chunkCount);
    }

    @Test
    void theCanonicalConstructorRejectsInvertedCorners() {
        // Unlike ofChunks, the canonical constructor does not reorder: the min/max names promise an
        // order, and silently accepting it reversed is exactly the bug fixed here -before, it gave
        // chunkCount()=16 (positive) and overworldEquivalent()="-512x-512" at the same time-.
        assertThrows(IllegalArgumentException.class, () -> new SweepArea(5, 5, 0, 0));
    }

    @Test
    void theCanonicalConstructorRejectsWhenOnlyOneAxisIsInverted() {
        // The Z axis is fine (min -1 <= max 3); only X comes inverted (min 5 > max 0). It must be
        // rejected anyway: one axis being fine is not enough to save the whole area.
        assertThrows(IllegalArgumentException.class, () -> new SweepArea(5, -1, 0, 3));
    }

    @Test
    void theCanonicalConstructorRejectsWhenOnlyTheZAxisIsInverted() {
        // The mirror of the previous one, and it was needed: with only the X axis case covered,
        // deleting the whole Z check broke no test. An area with Z inverted gives a negative
        // heightInChunks(), and from that come a negative chunkCount() and a walk of the rectangle
        // that visits not a single chunk: the sweep would say "nothing to sweep" over an area nobody
        // has looked at, which is the lie of spec §9 coming in through the typing door.
        assertThrows(IllegalArgumentException.class, () -> new SweepArea(0, 5, 3, -1));
    }

    @Test
    void laneLengthIsTheEuclideanDistanceBetweenItsEnds() {
        // 300-400-500: the good old Pythagorean triple, easy to check by eye.
        Lane lane = new Lane(0, 0, 300, 400);
        assertEquals(500, lane.lengthInBlocks(), 0.0001);
    }

    @Test
    void laneLengthWorksWhenNeitherAxisIsZero() {
        Lane lane = new Lane(1000, 2000, 1300, 1600);
        assertEquals(500, lane.lengthInBlocks(), 0.0001);
    }

    @Test
    void aZeroLengthLaneHasZeroLength() {
        // T3 adds up these lengths to decide whether to take off: a degenerate lane cannot slip in
        // as if it had a cost, nor break the sum.
        Lane lane = new Lane(1500, -700, 1500, -700);
        assertEquals(0, lane.lengthInBlocks(), 0.0001);
    }

    // ---------------------------------------------------------------------------------------
    // The size cap: two walks of the module cost the whole rectangle
    // ---------------------------------------------------------------------------------------

    @Test
    void aNormalAreaIsNotRejectedForSize() {
        // The whole box the player has crossed in months on this server, 384x580 chunks (spec §1):
        // 222,720 chunks, far below the cap. If this were rejected, the cap would be getting in the
        // way of the use that justifies the module.
        SweepArea area = SweepArea.ofChunks(0, 0, 383, 579);

        assertEquals(222_720, area.chunkCount());
        assertNull(area.oversizeRejection());
    }

    @Test
    void anAreaExactlyAtTheCapIsAccepted() {
        // 2,000 x 2,000 = exactly 4,000,000. The edge is in: the cap is "at most this", not "less
        // than this".
        SweepArea area = SweepArea.ofChunks(0, 0, 1_999, 1_999);

        assertEquals(SweepArea.MAX_CHUNKS, area.chunkCount());
        assertNull(area.oversizeRejection());
    }

    @Test
    void oneChunkOverTheCapIsRejected() {
        // 2,000 x 2,001 = 4,002,000, barely 0.05% above. The cut is where it says it is.
        SweepArea area = SweepArea.ofChunks(0, 0, 1_999, 2_000);

        assertNotNull(area.oversizeRejection());
    }

    @Test
    void theSliderLimitAreaIsRejectedWithoutTryingToWalkIt() {
        // 20,000 x 20,000 chunks is what the corners give at the ends of the settings slider: 400
        // million chunks. Before this cap, Coverage.seenIn and SweepPlanner visited them one by one
        // on the main thread from a command.
        SweepArea area = SweepArea.ofChunks(-10_000, -10_000, 9_999, 9_999);

        String reason = es(area.oversizeRejection());
        assertNotNull(reason);
        assertTrue(reason.contains("400000000"), reason);
    }

    @Test
    void anAreaThatDoesNotEvenFitInAnIntIsRejectedInsteadOfThrowing() {
        // 50,001 x 50,001 = 2,500,100,001 chunks: the area that makes chunkCount() throw. The cap
        // has to be able to answer precisely about it, so the count is done in long and not by
        // calling chunkCount().
        SweepArea area = SweepArea.ofChunks(0, 0, 50_000, 50_000);

        assertThrows(ArithmeticException.class, area::chunkCount);
        assertNotNull(area.oversizeRejection());
    }

    @Test
    void theReasonGivesTheSizeTheCapAndWhichSettingToChange() {
        // Same style as the module's other rejections: what happens, what it is now and what to
        // change, with the settings named as they appear in the UI.
        SweepArea area = SweepArea.ofChunks(0, 0, 2_999, 2_999);

        String reason = es(area.oversizeRejection());
        assertTrue(reason.contains("3000x3000"), reason);
        assertTrue(reason.contains("9000000"), reason);
        assertTrue(reason.contains(String.valueOf(SweepArea.MAX_CHUNKS)), reason);
        assertTrue(reason.contains("chunk-x-1"), reason);
        assertTrue(reason.contains("chunk-z-2"), reason);
    }

    @Test
    void theReasonSaysHowFarToShrinkTheLongSideKeepingTheShortOne() {
        // 8,000 wide by 1,000 tall: keeping the short side at 1,000, the long one cannot go past
        // 4,000,000 / 1,000 = 4,000. That is the actionable number.
        SweepArea area = SweepArea.ofChunks(0, 0, 7_999, 999);

        String reason = es(area.oversizeRejection());
        assertTrue(reason.contains("no puede pasar de 4000"), reason);
    }

    @Test
    void ifTheShortSideIsAlreadyOverItOnlySaysToBringTheCornersCloser() {
        // 5,000 x 5,000: the short side is 5,000 chunks and 4,000,000 / 5,000 = 800, which is LESS
        // than the short side itself. Telling them "shrink the long side to 800" would send them to
        // a rectangle that still does not fit, so here the advice is different.
        SweepArea area = SweepArea.ofChunks(0, 0, 4_999, 4_999);

        String reason = es(area.oversizeRejection());
        assertTrue(reason.contains("acerca las dos"), reason);
        assertFalse(reason.contains("no puede pasar de"), reason);
    }
}
