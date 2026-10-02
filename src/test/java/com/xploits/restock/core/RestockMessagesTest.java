package com.xploits.restock.core;

import com.xploits.shared.core.i18n.Catalog;
import com.xploits.shared.core.i18n.Language;
import com.xploits.shared.core.i18n.Msg;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Restock spec §5 "Messages": every reason says what happened, what to do and which setting, in both languages. */
class RestockMessagesTest {
    private static final Catalog ES = Catalog.load(Language.ES, p -> {
        throw new AssertionError(p);
    });
    private static final Catalog EN = Catalog.load(Language.EN, p -> {
        throw new AssertionError(p);
    });
    private static final RestockMessages.Facts FACTS = new RestockMessages.Facts(48, 10, "#", 4_194_304L, "0.26.14", 10);

    @Test
    void everyReasonRendersWithItsArgumentsInBothLanguages() {
        for (RestockReason r : RestockReason.values()) {
            ES.render(RestockMessages.reason(r, "scaffold", FACTS));
            EN.render(RestockMessages.reason(r, "scaffold", FACTS));
        }
    }

    @Test
    void thePlayerStopNamesItsSettings() {
        assertEquals("a player who is not your Meteor friend came within 48 blocks. Turn restock on again when it is "
                + "safe, or switch stop-near-players off (or lower player-distance) to keep fetching with players around.",
            EN.render(RestockMessages.reason(RestockReason.PLAYER_NEAR, "", FACTS)));
    }

    @Test
    void aPauseForAnUnnamedModuleSaysAnotherModule() {
        assertEquals("another module is rotating; it carries on by itself 2 s after it stops.",
            EN.render(RestockMessages.reason(RestockReason.COMBAT, "", FACTS)));
        assertEquals("surround++ is rotating; it carries on by itself 2 s after it stops.",
            EN.render(RestockMessages.reason(RestockReason.COMBAT, "surround++", FACTS)));
    }

    @Test
    void theUnreadableBaritoneValueNamesItsSettingWhenKnown() {
        assertTrue(EN.render(RestockMessages.reason(RestockReason.BARITONE_SETTINGS_UNREADABLE, "allowBreak", FACTS))
            .startsWith("baritone/settings.txt has a value for allowBreak that is not exactly true or false (Baritone "
                + "reads anything but a bare true as false, trailing spaces included)"));
        assertTrue(EN.render(RestockMessages.reason(RestockReason.BARITONE_SETTINGS_UNREADABLE, "", FACTS))
            .startsWith("baritone/settings.txt has a value for one of them that"));
    }

    @Test
    void theStallAndTheFullInventoryNameTheirFacts() {
        assertTrue(EN.render(RestockMessages.reason(RestockReason.NO_PATH, "", FACTS)).contains("(10 s without getting closer)"));
        assertEquals("your inventory has no room for stone: free some slots and turn restock on again.",
            EN.render(RestockMessages.reason(RestockReason.NOTHING_FITS, "stone", FACTS)));
    }

    @Test
    void theMaterialListDropsTheNamespaceAndSaysNothingWhenEmpty() {
        assertEquals("glass ×4, stone ×12", RestockMessages.materials(Map.of("minecraft:stone", 12, "minecraft:glass", 4)));
        assertEquals("", RestockMessages.materials(Map.of()));
        assertEquals("Watching: 0 block(s) of the build still to place; short of: nothing.",
            EN.render(Msg.of(RestockText.STATUS_IDLE, "remaining", 0, "short", RestockMessages.orNone(""))));
        assertEquals("other:thing", RestockMessages.itemName("other:thing"));
    }

    @Test
    void everyActivityFitsTheConsoleHeader() {
        for (RestockText t : List.of(RestockText.ACTIVITY_SCANNING, RestockText.ACTIVITY_WATCHING,
            RestockText.ACTIVITY_PAUSING, RestockText.ACTIVITY_WALKING, RestockText.ACTIVITY_OPENING,
            RestockText.ACTIVITY_TAKING, RestockText.ACTIVITY_RETURNING, RestockText.ACTIVITY_PAUSED)) {
            assertTrue(ES.render(Msg.of(t)).length() <= 30, t.name());
            assertTrue(EN.render(Msg.of(t)).length() <= 30, t.name());
        }
    }
}
