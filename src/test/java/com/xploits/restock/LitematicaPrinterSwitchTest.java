package com.xploits.restock;

import me.aleksilassila.litematica.printer.config.Configs;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Deferred L16/L23 (Task 8 review M1, M8): litematica-printer's print mode through reflection, against the test-only
 * stand-in {@link Configs}. A read that fails is "cannot be read", never a guess; a set is true only when the print mode
 * now is what was asked.
 */
class LitematicaPrinterSwitchTest {
    private final LitematicaPrinterSwitch printer = new LitematicaPrinterSwitch(Configs.class.getName());

    @BeforeEach
    @AfterEach
    void freshOption() {
        Configs.PRINT_MODE = new Configs.Option();
    }

    @Test
    void itReadsThePrintMode() {
        assertEquals(Boolean.FALSE, printer.printing());
        Configs.PRINT_MODE.value = true;
        assertEquals(Boolean.TRUE, printer.printing());
    }

    @Test
    void aSetIsTrueWhenThePrintModeNowIsWhatWasAsked() {
        assertTrue(printer.set(true));
        assertTrue(Configs.PRINT_MODE.value);
        assertTrue(printer.set(false));
        assertFalse(Configs.PRINT_MODE.value);
    }

    @Test
    void aSetThatDidNotTakeIsFalse() {
        Configs.PRINT_MODE.stuck = true;
        assertFalse(printer.set(true), "no exception, and still off");
        assertTrue(printer.set(false), "already what was asked");
    }

    @Test
    void aCallbackThatFailsAfterTheValueChangedStillCounts() {
        Configs.PRINT_MODE.throwAfterSet = true;
        assertTrue(printer.set(true), "the value flipped, whatever the callback did");
        Configs.PRINT_MODE.stuck = true;
        assertFalse(printer.set(false), "neither flipped nor quiet");
    }

    @Test
    void whatCannotBeReadIsNeverAGuess() {
        Configs.PRINT_MODE = null;
        assertNull(printer.printing());
        assertFalse(printer.set(true));
        LitematicaPrinterSwitch missing = new LitematicaPrinterSwitch("me.aleksilassila.litematica.printer.config.Gone");
        assertNull(missing.printing(), "the class is not there");
        assertFalse(missing.set(false));
        assertTrue(missing.installed(), "installed is what Fabric says, asked before this switch is made");
    }
}
