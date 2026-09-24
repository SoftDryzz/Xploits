package com.xploits.sweep.core;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoverageTest {
    @Test
    void aValidLineMarksThatChunkAsSeenAndNoOther() {
        Coverage coverage = Coverage.ofLines(List.of("12,-7"));
        assertTrue(coverage.seen(new ChunkPos(12, -7)));
        assertFalse(coverage.seen(new ChunkPos(13, -7)));
        assertEquals(1, coverage.size());
    }

    @Test
    void anEmptyLineIsSkippedNotFatal() {
        Coverage coverage = Coverage.ofLines(List.of("", "4,9"));
        assertEquals(1, coverage.size());
        assertTrue(coverage.seen(new ChunkPos(4, 9)));
    }

    @Test
    void aLineOfOnlyWhitespaceIsSkipped() {
        Coverage coverage = Coverage.ofLines(List.of("   ", "4,9"));
        assertEquals(1, coverage.size());
        assertTrue(coverage.seen(new ChunkPos(4, 9)));
    }

    @Test
    void aLineWithOnlyOneFieldIsSkipped() {
        // The typical case of a file cut off mid-write: the comma never made it out.
        Coverage coverage = Coverage.ofLines(List.of("4", "4,9"));
        assertEquals(1, coverage.size());
        assertTrue(coverage.seen(new ChunkPos(4, 9)));
    }

    @Test
    void aLineWithTextWhereANumberGoesIsSkipped() {
        Coverage coverage = Coverage.ofLines(List.of("abc,9", "4,xyz", "4,9"));
        assertEquals(1, coverage.size());
        assertTrue(coverage.seen(new ChunkPos(4, 9)));
    }

    @Test
    void aHalfWrittenFileDoesNotLoseTheLinesThatDidMakeItToDisk() {
        // Simulates the abrupt shutdown: NewerNewChunks writes line by line and the client dies
        // halfway through the last one. The earlier, complete lines must still count.
        Coverage coverage = Coverage.ofLines(List.of("1,1", "2,2", "3,2", "3,"));
        assertEquals(3, coverage.size());
        assertTrue(coverage.seen(new ChunkPos(1, 1)));
        assertTrue(coverage.seen(new ChunkPos(2, 2)));
        assertTrue(coverage.seen(new ChunkPos(3, 2)));
        // The broken line cannot slip in as if it were a valid chunk.
        assertFalse(coverage.seen(new ChunkPos(3, 0)));
    }

    @Test
    void aCorruptLineNeverMakesAnAlreadySeenChunkLookUnseen() {
        // The real risk: a garbage line must not "subtract" coverage already confirmed by another line.
        Coverage coverage = Coverage.ofLines(List.of("5,5", "garbage", "5,5"));
        assertTrue(coverage.seen(new ChunkPos(5, 5)));
        assertEquals(1, coverage.size());
    }

    @Test
    void mergeIsTheUnionWithoutDuplicates() {
        Coverage first = Coverage.ofLines(List.of("1,1", "2,2"));
        Coverage second = Coverage.ofLines(List.of("2,2", "3,3"));

        Coverage merged = Coverage.merge(List.of(first, second));

        assertEquals(3, merged.size());
        assertTrue(merged.seen(new ChunkPos(1, 1)));
        assertTrue(merged.seen(new ChunkPos(2, 2)));
        assertTrue(merged.seen(new ChunkPos(3, 3)));
    }

    @Test
    void mergingAnEmptyCollectionIsEmptyNotAnError() {
        // No file existing at all -all five missing- means starting from scratch, not failing.
        Coverage merged = Coverage.merge(List.of());
        assertEquals(0, merged.size());
    }

    @Test
    void emptyCoverageSeesNothing() {
        assertFalse(Coverage.empty().seen(new ChunkPos(0, 0)));
        assertEquals(0, Coverage.empty().size());
    }

    // ---------------------------------------------------------------------------------------
    // ofFileContent: the last line of a cut-off file cannot be believed
    // ---------------------------------------------------------------------------------------

    @Test
    void aFileEndingInANewlineKeepsAllItsLines() {
        Coverage coverage = Coverage.ofFileContent("12,-7\n4,9\n");

        assertEquals(2, coverage.size());
        assertTrue(coverage.seen(new ChunkPos(12, -7)));
        assertTrue(coverage.seen(new ChunkPos(4, 9)));
    }

    @Test
    void aTruncatedLineThatParsesDoesNotSneakInAsASeenChunk() {
        // The exact case: a line that was going to be "-412,1087" and that the client's abrupt
        // shutdown left as "-412,1". It is a perfectly valid x,z of a chunk that was never seen, so
        // parseLine cannot catch it; if it slips in and it was the one its band was missing,
        // SweepPlanner skips the whole band and counts as combed a lane that was not flown.
        Coverage coverage = Coverage.ofFileContent("100,200\n-412,1");

        assertEquals(1, coverage.size());
        assertTrue(coverage.seen(new ChunkPos(100, 200)));
        assertFalse(coverage.seen(new ChunkPos(-412, 1)));
        assertFalse(coverage.seen(new ChunkPos(-412, 1087)));
    }

    @Test
    void aSingleUnterminatedLineFileLeavesNothingSeen() {
        // That single line never finished being written and there is none before it: nothing is
        // known from this file, and "nothing" is right, not the chunk it appears to be.
        assertEquals(0, Coverage.ofFileContent("12,-7").size());
    }

    @Test
    void aWindowsLineEndingDoesNotCountAsTwoLines() {
        Coverage coverage = Coverage.ofFileContent("12,-7\r\n4,9\r\n");

        assertEquals(2, coverage.size());
        assertTrue(coverage.seen(new ChunkPos(12, -7)));
        assertTrue(coverage.seen(new ChunkPos(4, 9)));
    }

    @Test
    void aTruncatedWindowsFileAlsoLosesItsLastLine() {
        Coverage coverage = Coverage.ofFileContent("12,-7\r\n4,9");

        assertEquals(1, coverage.size());
        assertTrue(coverage.seen(new ChunkPos(12, -7)));
        assertFalse(coverage.seen(new ChunkPos(4, 9)));
    }

    @Test
    void anEmptyFileIsAFreshStartNotAnError() {
        assertEquals(0, Coverage.ofFileContent("").size());
    }

    @Test
    void garbageInTheMiddleIsSkippedWithoutDroppingTheRest() {
        Coverage coverage = Coverage.ofFileContent("12,-7\ngarbage\n1,2,3\n\n4,9\n");

        assertEquals(2, coverage.size());
        assertTrue(coverage.seen(new ChunkPos(12, -7)));
        assertTrue(coverage.seen(new ChunkPos(4, 9)));
    }

    @Test
    void nullContentIsNotReadAsAnEmptyFile() {
        assertThrows(NullPointerException.class, () -> Coverage.ofFileContent(null));
    }

    // ---------------------------------------------------------------------------------------
    // seenIn: what the previous coverage saves is what falls INSIDE the area
    // ---------------------------------------------------------------------------------------

    @Test
    void seenInCountsOnlyChunksInsideTheArea() {
        // Three inside the rectangle 0,0..2,2 and two outside.
        Coverage coverage = Coverage.ofLines(List.of("0,0", "1,1", "2,2", "3,0", "-1,-1"));

        assertEquals(5, coverage.size());
        assertEquals(3, coverage.seenIn(SweepArea.ofChunks(0, 0, 2, 2)));
    }

    @Test
    void seenInNeverExceedsTheAreasChunkCount() {
        // The bug this method fixes: with size() -the coverage of the whole dimension- the message
        // went as far as announcing more chunks seen than the area has. Here there are 9 chunks of
        // area and 12 seen in total.
        Coverage coverage = Coverage.ofLines(List.of(
            "0,0", "0,1", "0,2", "1,0", "1,1", "1,2", "2,0", "2,1", "2,2",
            "50,50", "51,50", "52,50"));
        SweepArea area = SweepArea.ofChunks(0, 0, 2, 2);

        assertEquals(12, coverage.size());
        assertEquals(area.chunkCount(), coverage.seenIn(area));
    }

    @Test
    void seenInOverAnAreaWithNothingSeenIsZero() {
        Coverage coverage = Coverage.ofLines(List.of("50,50"));

        assertEquals(0, coverage.seenIn(SweepArea.ofChunks(0, 0, 9, 9)));
    }

    @Test
    void seenInCountsTheAreasEdges() {
        // All four corners are in: the area includes both edges of each axis.
        Coverage coverage = Coverage.ofLines(List.of("0,0", "0,3", "3,0", "3,3"));

        assertEquals(4, coverage.seenIn(SweepArea.ofChunks(0, 0, 3, 3)));
    }

    @Test
    void seenInNeedsAnArea() {
        assertThrows(NullPointerException.class, () -> Coverage.empty().seenIn(null));
    }

    // ---------------------------------------------------------------------------------------
    // The promise to skip "anything else" is kept in full, nulls included
    // ---------------------------------------------------------------------------------------

    @Test
    void aNullLineIsSkippedLikeAnyOtherBadLine() {
        // The javadoc promises to skip "anything else" and carry on with the following lines. A
        // null broke that promise from inside the path whose stated purpose is never to abort the
        // read because of a bad line.
        List<String> lines = new ArrayList<>();
        lines.add("1,1");
        lines.add(null);
        lines.add("2,2");

        Coverage coverage = Coverage.ofLines(lines);

        assertEquals(2, coverage.size());
        assertTrue(coverage.seen(new ChunkPos(1, 1)));
        assertTrue(coverage.seen(new ChunkPos(2, 2)));
    }

    @Test
    void ofLinesWithoutLinesComplainsInsteadOfFakingAnEmptyFile() {
        assertThrows(NullPointerException.class, () -> Coverage.ofLines(null));
    }

    @Test
    void aNullReadDoesNotTakeTheOthersDownWithIt() {
        // There are five files and the union is the answer to "has this chunk been seen yet?":
        // nothing having come out of one cannot drop what did come out of the others, because a
        // coverage read as empty means replanning hours of terrain already seen.
        List<Coverage> parts = new ArrayList<>();
        parts.add(Coverage.ofLines(List.of("1,1")));
        parts.add(null);
        parts.add(Coverage.ofLines(List.of("2,2")));

        Coverage union = Coverage.merge(parts);

        assertEquals(2, union.size());
        assertTrue(union.seen(new ChunkPos(1, 1)));
        assertTrue(union.seen(new ChunkPos(2, 2)));
    }

    @Test
    void mergeWithNothingToMergeComplainsInsteadOfPretendingThereWereNoFiles() {
        assertThrows(NullPointerException.class, () -> Coverage.merge(null));
    }
}
