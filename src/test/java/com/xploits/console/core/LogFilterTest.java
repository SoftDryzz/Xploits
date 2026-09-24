package com.xploits.console.core;

import com.xploits.shared.core.i18n.Catalog;
import com.xploits.shared.core.i18n.Language;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LogFilterTest {
    private static final Catalog ES = Catalog.load(Language.ES, p -> {
        throw new AssertionError(p);
    });
    private static final Catalog EN = Catalog.load(Language.EN, p -> {
        throw new AssertionError(p);
    });
    private static LogEntry.Message m(Level level, String source) {
        return new LogEntry.Message(0, 0, "s", level, source, "x");
    }

    @Test
    void eachFilterWithItsCounterexample() {
        assertTrue(LogFilter.ALL.accepts(m(Level.INFO, "stash-keeper")));
        assertTrue(LogFilter.PVP.accepts(m(Level.INFO, "auto-pvp")));
        assertTrue(LogFilter.PVP.accepts(m(Level.INFO, "fight-recorder")));
        assertFalse(LogFilter.PVP.accepts(m(Level.INFO, "auto-travel")));
        assertTrue(LogFilter.TRAVEL.accepts(m(Level.INFO, "auto-travel")));
        assertFalse(LogFilter.TRAVEL.accepts(m(Level.INFO, "nether-sweep")));
        assertTrue(LogFilter.SWEEP.accepts(m(Level.INFO, "nether-sweep")));
        assertFalse(LogFilter.SWEEP.accepts(m(Level.WARNING, "auto-pvp")));
        assertTrue(LogFilter.WARNINGS.accepts(m(Level.WARNING, "auto-pvp")));
        assertTrue(LogFilter.WARNINGS.accepts(m(Level.ERROR, "xploits")));
        assertFalse(LogFilter.WARNINGS.accepts(m(Level.INFO, "auto-pvp")));
    }

    @Test
    void theLabelsComeFromTheCatalog() {
        assertEquals("solo avisos", LogFilter.WARNINGS.label(ES));
        assertEquals("warnings only", LogFilter.WARNINGS.label(EN));
    }
}
