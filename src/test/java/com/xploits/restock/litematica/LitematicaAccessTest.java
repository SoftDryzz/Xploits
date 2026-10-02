package com.xploits.restock.litematica;

import com.xploits.printer.core.Guards;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Deferred L18 (Task 8 review M3): without Litematica — as on the unit tests' classpath, where it is compile-only —
 * opening the selected placement is the refusal that says Litematica is missing, not a list of every member of an API
 * it cannot find.
 */
class LitematicaAccessTest {
    @Test
    void withoutLitematicaOpeningRefusesAsMissing() {
        assertFalse(LitematicaAccess.installed());
        assertEquals(Optional.of(new Guards.Refusal(Guards.Reason.NO_LITEMATICA, "")),
            LitematicaAccess.open(1_000).refusal());
    }
}
