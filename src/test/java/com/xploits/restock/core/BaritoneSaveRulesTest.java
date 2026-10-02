package com.xploits.restock.core;

import com.xploits.printer.core.BaritoneSession;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Ruling P26 and restock spec §3 "Baritone": what restock may do with the player's saved Baritone values. */
class BaritoneSaveRulesTest {
    private static Map<String, Boolean> values(boolean breaks) {
        Map<String, Boolean> m = new HashMap<>();
        for (String name : BaritoneSession.CHANGED) m.put(name, false);
        m.put("allowBreak", breaks);
        return m;
    }

    private static BaritoneSession.Saved saved(String prefix, boolean breaks) {
        return new BaritoneSession.Saved(prefix, BaritoneSession.Mode.MINE, values(breaks));
    }

    private static Optional<List<String>> file(String... lines) {
        return Optional.of(List.of(lines));
    }

    @Test
    void aPendingSavedSessionIsTheSourceEvenWhenSettingsSaySomethingElse() {
        BaritoneSaveRules.Values v = BaritoneSaveRules.playerValues(true, Optional.of(saved("#", true)),
            file("allowBreak false"));
        assertEquals(Optional.empty(), v.refusal());
        assertEquals(values(true), v.player());
    }

    @Test
    void anUnreadablePendingFileIsARefusalNamingNoSettingAndNeverFallsBackToSettings() {
        BaritoneSaveRules.Values v = BaritoneSaveRules.playerValues(true, Optional.empty(), file("allowBreak true"));
        assertEquals(Optional.of(new RestockReason.Refusal(RestockReason.BARITONE_SAVED_UNREADABLE, "")), v.refusal());
        assertTrue(v.player().isEmpty());
    }

    @Test
    void aPendingFileWhosePrefixTheNetRejectsIsUnreadable() {
        for (String bad : new String[]{"/x", "/"}) {
            BaritoneSaveRules.Values v = BaritoneSaveRules.playerValues(true, Optional.of(saved(bad, true)), file());
            assertEquals(Optional.of(RestockReason.BARITONE_SAVED_UNREADABLE), v.refusal().map(RestockReason.Refusal::reason), bad);
        }
    }

    @Test
    void withNothingPendingTheSettingsFileIsRead() {
        BaritoneSaveRules.Values v = BaritoneSaveRules.playerValues(false, Optional.empty(),
            file("allowBreak false", "censorCoordinates true"));
        assertEquals(Optional.empty(), v.refusal());
        assertFalse(v.player().get("allowBreak"));
        assertTrue(v.player().get("censorCoordinates"));
    }

    @Test
    void aMissingSettingsFileMeansBaritonesDefaults() {
        BaritoneSaveRules.Values v = BaritoneSaveRules.playerValues(false, Optional.empty(), file());
        assertEquals(Optional.empty(), v.refusal());
        assertEquals(BaritoneSession.playerValues(Map.of()).get(), v.player());
    }

    @Test
    void anUnreadableSettingsFileRefuses() {
        BaritoneSaveRules.Values v = BaritoneSaveRules.playerValues(false, Optional.empty(), Optional.empty());
        assertEquals(Optional.of(new RestockReason.Refusal(RestockReason.BARITONE_SETTINGS_UNREADABLE, "")), v.refusal());
    }

    @Test
    void aNonBooleanValueRefusesNamingTheSetting() {
        BaritoneSaveRules.Values v = BaritoneSaveRules.playerValues(false, Optional.empty(), file("allowPlace yes"));
        assertEquals(Optional.of(new RestockReason.Refusal(RestockReason.BARITONE_SETTINGS_UNREADABLE, "allowPlace")),
            v.refusal());
    }

    @Test
    void settingsAreNotLookedAtWhenAPendingFileExists() {
        BaritoneSaveRules.Values v = BaritoneSaveRules.playerValues(true, Optional.of(saved("#", true)), Optional.empty());
        assertEquals(Optional.empty(), v.refusal());
    }

    @Test
    void theSessionPrefixIsCheckedBeforeAnythingIsSaved() {
        assertEquals(Optional.empty(), BaritoneSaveRules.prefixRefusal("#"));
        assertEquals(Optional.of(new RestockReason.Refusal(RestockReason.PREFIX_INVALID, "")),
            BaritoneSaveRules.prefixRefusal(""));
        assertEquals(RestockReason.PREFIX_INVALID, BaritoneSaveRules.prefixRefusal("/").get().reason());
        assertEquals(RestockReason.PREFIX_INVALID, BaritoneSaveRules.prefixRefusal("  ").get().reason());
    }

    @Test
    void savesOnlyWhenNothingIsPending() {
        assertTrue(BaritoneSaveRules.saveBeforeFirstSet(false));
        assertFalse(BaritoneSaveRules.saveBeforeFirstSet(true));
    }

    @Test
    void aFailedBeginDeletesTheFileOnlyWhenNothingWasDeliveredAndItIsOurs() {
        assertTrue(BaritoneSaveRules.deleteAfterFailedBegin(0, true));
        assertFalse(BaritoneSaveRules.deleteAfterFailedBegin(0, false), "a pending file is never ours to delete");
        assertFalse(BaritoneSaveRules.deleteAfterFailedBegin(1, true), "a #set reached Baritone: the debt is real");
        assertFalse(BaritoneSaveRules.deleteAfterFailedBegin(4, true));
        assertFalse(BaritoneSaveRules.deleteAfterFailedBegin(3, false));
    }

    @Test
    void theEndAndTheJoinRepairDeleteOnlyAfterFullDelivery() {
        assertTrue(BaritoneSaveRules.deleteAfterRestoration(true));
        assertFalse(BaritoneSaveRules.deleteAfterRestoration(false));
    }

    @Test
    void aSavedFileForTheJoinRepairNeedsAnAcceptedPrefix() {
        assertEquals(Optional.of(saved("#", true)), BaritoneSaveRules.repairable(Optional.of(saved("#", true))));
        assertEquals(Optional.empty(), BaritoneSaveRules.repairable(Optional.empty()));
        assertEquals(Optional.empty(), BaritoneSaveRules.repairable(Optional.of(saved("/", true))));
        assertEquals(Optional.empty(), BaritoneSaveRules.repairable(Optional.of(saved("/x", true))));
    }
}
