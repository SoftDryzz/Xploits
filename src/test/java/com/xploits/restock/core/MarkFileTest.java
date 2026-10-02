package com.xploits.restock.core;

import com.xploits.printer.core.Pos;
import com.xploits.testing.TempFolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Restock spec §4: the marks file never loses the player's marks. */
class MarkFileTest {
    private static final String DIM = "minecraft:overworld";
    private static final MarkBook.Mark A = new MarkBook.Mark(DIM, new Pos(1, 2, 3), new Pos(1, 2, 4));
    private static final MarkBook.Mark B = new MarkBook.Mark(DIM, new Pos(5, 6, 7), new Pos(5, 6, 8));

    Path dir;

    @BeforeEach
    void createTempFolder() throws IOException {
        dir = TempFolder.create();
    }

    @AfterEach
    void deleteTempFolder() {
        TempFolder.delete(dir);
    }

    private Path marks() {
        return dir.resolve("world").resolve("marks.txt");
    }

    /** Makes the save impossible: a directory sits where its temp file goes. */
    private void breakSaves() throws IOException {
        Files.createDirectories(dir.resolve("world").resolve("marks.txt.tmp"));
    }

    @Test
    void noFileIsAnEmptyBookAndMarkingCreatesIt() {
        MarkFile f = new MarkFile(marks());
        assertEquals(0, f.book().size());
        assertEquals(MarkFile.Result.MARKED, f.toggle(A));
        assertTrue(Files.exists(marks()));
        assertEquals(List.of(A), new MarkFile(marks()).book().all());
    }

    @Test
    void aFileThatIsNotOursIsUnreadableAndKeptByEveryChange() throws IOException {
        Files.createDirectories(marks().getParent());
        Files.writeString(marks(), "something else\n", StandardCharsets.UTF_8);
        MarkFile f = new MarkFile(marks());
        assertNull(f.book());
        assertEquals(MarkFile.Result.UNREADABLE, f.toggle(A));
        assertEquals(MarkFile.Result.UNREADABLE, f.clear().result());
        assertEquals("something else\n", Files.readString(marks(), StandardCharsets.UTF_8));
    }

    @Test
    void aFileThatCannotBeToldApartFromNoFileIsUnreadableNotEmpty() throws IOException {
        // A directory where the file goes: it exists, so it is not "no file", and it cannot be read as one.
        Files.createDirectories(marks());
        MarkFile f = new MarkFile(marks());
        assertTrue(f.unreadable());
        assertEquals(MarkFile.Result.UNREADABLE, f.toggle(A));
        assertTrue(Files.isDirectory(marks()));
    }

    @Test
    void reloadingReadsTheFileAgainOnceThePlayerMovedTheBadOneAway() throws IOException {
        Files.createDirectories(marks().getParent());
        Files.writeString(marks(), "garbage", StandardCharsets.UTF_8);
        MarkFile f = new MarkFile(marks());
        assertTrue(f.unreadable());
        Files.delete(marks());
        f.reload();
        assertFalse(f.unreadable());
        assertEquals(MarkFile.Result.MARKED, f.toggle(A));
    }

    @Test
    void aFailedSaveAfterUnmarkingRestoresTheRemovedMarkExactly() throws IOException {
        MarkFile f = new MarkFile(marks());
        f.toggle(A);
        f.toggle(B);
        breakSaves();
        MarkBook.Mark sameContainerOtherSpot = new MarkBook.Mark(DIM, A.container(), new Pos(9, 9, 9));
        assertEquals(MarkFile.Result.SAVE_FAILED, f.toggle(sameContainerOtherSpot));
        assertEquals(List.of(A, B), f.book().all(), "A is back where it was, with its own stand spot");
    }

    @Test
    void aFailedSaveAfterMarkingForgetsTheMark() throws IOException {
        MarkFile f = new MarkFile(marks());
        f.toggle(A);
        breakSaves();
        assertEquals(MarkFile.Result.SAVE_FAILED, f.toggle(B));
        assertEquals(List.of(A), f.book().all());
    }

    @Test
    void aFailedSaveAfterClearingKeepsEveryMarkInOrder() throws IOException {
        MarkFile f = new MarkFile(marks());
        f.toggle(A);
        f.toggle(B);
        breakSaves();
        MarkFile.Cleared c = f.clear();
        assertEquals(MarkFile.Result.SAVE_FAILED, c.result());
        assertEquals(0, c.count());
        assertEquals(List.of(A, B), f.book().all());
    }

    @Test
    void clearingGivesHowManyWereRemoved() {
        MarkFile f = new MarkFile(marks());
        f.toggle(A);
        f.toggle(B);
        MarkFile.Cleared c = f.clear();
        assertEquals(MarkFile.Result.UNMARKED, c.result());
        assertEquals(2, c.count());
        assertEquals(0, new MarkFile(marks()).book().size());
    }

    @Test
    void aFileHoldingOneContainerTwiceIsUnreadableAndKept() throws IOException {
        Files.createDirectories(marks().getParent());
        List<String> lines = List.of("xploits-restock-marks 1", DIM + " 1 2 3 1 2 4", DIM + " 1 2 3 7 7 7");
        Files.write(marks(), lines, StandardCharsets.UTF_8);
        MarkFile f = new MarkFile(marks());
        assertNull(f.book());
        assertEquals(MarkFile.Result.UNREADABLE, f.toggle(B));
        assertEquals(lines, Files.readAllLines(marks(), StandardCharsets.UTF_8));
    }
}
