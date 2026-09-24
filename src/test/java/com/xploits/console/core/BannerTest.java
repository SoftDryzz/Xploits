package com.xploits.console.core;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BannerTest {
    private static final List<String> ART = Collections.nCopies(13, "X".repeat(99) + Ansi.RESET);

    @Test
    void theRealResourceIsValidAndIsTheLogo() throws IOException {
        List<String> art = Banner.load();
        assertEquals(13, art.size());
        int widest = art.stream().mapToInt(Banner::visibleWidth).max().orElse(0);
        assertTrue(widest >= 90 && widest <= 100, "logo width: " + widest);
    }

    @Test
    void validateRejectsWhatIsUnusable() {
        assertThrows(IllegalArgumentException.class, () -> Banner.validate(ART.subList(0, 12)));
        List<String> withClear = new ArrayList<>(ART);
        withClear.set(3, "\u001b[2J" + Ansi.RESET);
        assertThrows(IllegalArgumentException.class, () -> Banner.validate(withClear));
        List<String> withoutReset = new ArrayList<>(ART);
        withoutReset.set(0, "X");
        assertThrows(IllegalArgumentException.class, () -> Banner.validate(withoutReset));
        List<String> wide = new ArrayList<>(ART);
        wide.set(0, "X".repeat(101) + Ansi.RESET);
        assertThrows(IllegalArgumentException.class, () -> Banner.validate(wide));
    }

    @Test
    void withAHundredColumnsAndFortyRowsTheLogoIsShown() {
        assertEquals(ART, Banner.choose(ART, 100, 40));
    }

    @Test
    void withoutRoomTheNameGoesOnOneRow() {
        List<String> text = List.of(Ansi.color(Ansi.CYAN) + "XTO2002" + Ansi.RESET);
        assertEquals(text, Banner.choose(ART, 99, 40));
        assertEquals(text, Banner.choose(ART, 100, 39));
    }
}
