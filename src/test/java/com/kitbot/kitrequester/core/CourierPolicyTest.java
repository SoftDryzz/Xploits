package com.kitbot.kitrequester.core;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static com.kitbot.kitrequester.core.CourierPolicy.shouldAccept;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CourierPolicyTest {
    private static final Set<String> KNOWN = Set.of("StormAegis44", "ValorKnight27", "IronSentri08");

    @Test
    void acceptsKnownCourierEvenBeforeReady() {
        assertTrue(shouldAccept("StormAegis44", false, KNOWN, false));
    }

    @Test
    void namesAreCaseSensitive() {
        assertFalse(shouldAccept("stormaegis44", false, KNOWN, false));
    }

    @Test
    void rejectsLookalikeWithoutReadyEvenIfTrusted() {
        assertFalse(shouldAccept("StormAegls44", false, KNOWN, true));
    }

    @Test
    void rejectsUnknownWhenNotTrusted() {
        assertFalse(shouldAccept("NewCourier1", true, KNOWN, false));
    }

    @Test
    void acceptsUnknownOnlyWithReadyWhenTrusted() {
        assertTrue(shouldAccept("NewCourier1", true, KNOWN, true));
        assertFalse(shouldAccept("NewCourier1", false, KNOWN, true));
    }

    @Test
    void rejectsMissingName() {
        assertFalse(shouldAccept(null, true, KNOWN, true));
        assertFalse(shouldAccept("", true, KNOWN, true));
        assertFalse(shouldAccept("  ", true, KNOWN, true));
    }
}
