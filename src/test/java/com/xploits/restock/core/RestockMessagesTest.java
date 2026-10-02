package com.xploits.restock.core;

import com.xploits.shared.core.i18n.Catalog;
import com.xploits.shared.core.i18n.Language;
import com.xploits.shared.core.i18n.Msg;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
    void aSettingsFileThatCouldNotBeReadIsNotCalledABadValue() {
        // A1 review m1: the player is told the file could not be read, not to fix a line that may not exist.
        String en = EN.render(RestockMessages.reason(RestockReason.BARITONE_SETTINGS_NOT_READ, "", FACTS));
        assertTrue(en.startsWith("baritone/settings.txt could not be read ("), en);
        assertFalse(en.contains("true or false"), en);
        String es = ES.render(RestockMessages.reason(RestockReason.BARITONE_SETTINGS_NOT_READ, "", FACTS));
        assertTrue(es.startsWith("no se pudo leer baritone/settings.txt ("), es);
        assertFalse(es.contains("true ni false"), es);
    }

    @Test
    void theUnreadableBaritoneValueNamesThePrefixBaritoneListensTo() {
        // Deferred L11: baritone-prefix is a setting; the fix the text suggests must use it, not a hard-coded "#".
        RestockMessages.Facts dollar = new RestockMessages.Facts(48, 10, "$", 4_194_304L, "0.26.14", 10);
        assertTrue(EN.render(RestockMessages.reason(RestockReason.BARITONE_SETTINGS_UNREADABLE, "allowBreak", dollar))
            .endsWith("fix that line, or set it again with $set."));
        assertTrue(ES.render(RestockMessages.reason(RestockReason.BARITONE_SETTINGS_UNREADABLE, "allowBreak", dollar))
            .endsWith("vuelve a poner el ajuste con $set."));
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
            RestockText.ACTIVITY_TAKING, RestockText.ACTIVITY_RETURNING, RestockText.ACTIVITY_PAUSED,
            RestockText.ACTIVITY_UNPACKING)) {
            assertTrue(ES.render(Msg.of(t)).length() <= 30, t.name());
            assertTrue(EN.render(Msg.of(t)).length() <= 30, t.name());
        }
    }

    @Test
    void aRepeatedCombatStopWithoutANameSaysAnotherModuleNotACombatModuleInBrackets() {
        assertEquals("another module kept rotating while restock opened a container or unpacked a shulker box: "
                + "it paused three times and stops. Turn it on again when the fight is over.",
            EN.render(RestockMessages.reason(RestockReason.COMBAT_REPEATED, "", FACTS)));
        assertEquals("otro módulo siguió rotando mientras restock abría un contenedor o vaciaba una caja de shulker: "
                + "se pausó tres veces y se para. Vuelve a encenderlo cuando acabe la pelea.",
            ES.render(RestockMessages.reason(RestockReason.COMBAT_REPEATED, "", FACTS)));
        assertTrue(EN.render(RestockMessages.reason(RestockReason.COMBAT_REPEATED, "surround++", FACTS))
            .startsWith("surround++ kept rotating"));
    }

    @Test
    void aRepeatedRotationStopSaysAnUnpackToo() {
        // Phase B: the guards' acting is the unpack's too (its slot changes, the place and the dig aim), not only a
        // container's open.
        assertEquals("another module kept rotating while restock opened a container or unpacked a shulker box: "
                + "it paused three times and stops. Turn that module off, then restock on again.",
            EN.render(RestockMessages.reason(RestockReason.OTHER_ROTATION_REPEATED, "", FACTS)));
        assertEquals("otro módulo siguió rotando mientras restock abría un contenedor o vaciaba una caja de shulker: "
                + "se pausó tres veces y se para. Apaga ese módulo y vuelve a encender restock.",
            ES.render(RestockMessages.reason(RestockReason.OTHER_ROTATION_REPEATED, "", FACTS)));
    }

    @Test
    void anInternalStopReadsAsAnErrorNotAsAGuard() {
        assertEquals("restock hit an unexpected error (IllegalStateException) and stopped: turn it on again, and report it "
                + "if it happens again.",
            EN.render(RestockMessages.reason(RestockReason.INTERNAL, "IllegalStateException", FACTS)));
        assertEquals("restock ha tenido un error inesperado (IllegalStateException) y se ha parado: vuelve a encenderlo, "
                + "y avísalo si se repite.",
            ES.render(RestockMessages.reason(RestockReason.INTERNAL, "IllegalStateException", FACTS)));
    }

    @Test
    void aFailedLitematicaCallIsSaidInTheLanguageOfThePlayer() {
        String detail = RestockMessages.litematicaCallFailed("NoSuchMethodError");
        assertEquals("a call to Litematica failed (NoSuchMethodError): this Litematica is probably not the 0.26.14 "
                + "restock was built against. Install Litematica 0.26.14.",
            EN.render(RestockMessages.reason(RestockReason.LITEMATICA_API, detail, FACTS)));
        assertEquals("ha fallado una llamada a Litematica (NoSuchMethodError): lo más probable es que este Litematica no "
                + "sea el 0.26.14 con el que se compiló restock. Instala Litematica 0.26.14.",
            ES.render(RestockMessages.reason(RestockReason.LITEMATICA_API, detail, FACTS)));
        assertTrue(EN.render(RestockMessages.reason(RestockReason.LITEMATICA_API, "Placement.getBox", FACTS))
            .contains("(missing: Placement.getBox)"));
    }

    @Test
    void theRepairFailureNamesTheSettingToCheck() {
        assertTrue(EN.render(Msg.of(RestockText.REPAIR_FAILED, "prefix", "#")).contains("\"#\""));
        assertTrue(ES.render(Msg.of(RestockText.REPAIR_FAILED, "prefix", "#")).contains("\"#\""));
    }

    @Test
    void theFilledOnlyTextSaysWhy() {
        assertEquals("Every shulker_box in that container has items stored inside it, and restock never takes such a stack "
                + "as a building block: trying the next container.",
            EN.render(Msg.of(RestockText.TRIP_FILLED_ONLY, "material", "shulker_box")));
    }

    @Test
    void everyStopSaysWhatIsLeftOfTheShulkerBoxesByDistanceOnly() {
        assertEquals(List.of(), RestockMessages.shulkersLeft(ShulkersLeft.NONE));
        assertEquals(List.of(
            "2 shulker box(es) restock set down still stand(s) beside the build, the nearest 3 blocks from you: break and"
                + " pick up each one by hand.",
            "The shulker box restock broke lies on the ground 4 blocks from you: pick it up.",
            "You still carry 1 shulker box(es) restock took from your containers: put them back by hand."),
            RestockMessages.shulkersLeft(new ShulkersLeft(2, 3, 4, false, false, 1)).stream().map(EN::render).toList());
        assertEquals(List.of(
            "The shulker box restock broke did not come back to your inventory and is no longer on the ground near you:"
                + " something or someone took it.",
            "restock could not check whether a shulker box it set down is still standing or on the ground: look around"
                + " the build."),
            RestockMessages.shulkersLeft(new ShulkersLeft(0, -1, -1, true, true, 0)).stream().map(EN::render).toList());
        assertEquals(List.of(
            "1 shulker box(es) restock set down still stand(s) beside the build, the nearest 2 blocks from you,"
                + " unless it broke just now as restock's dig ended: then it lies on the ground near there. Break it,"
                + " or pick it up, by hand."),
            RestockMessages.shulkersLeft(new ShulkersLeft(1, 2, -1, false, false, 0, true)).stream().map(EN::render)
                .toList(), "M17: right after the dig's STOP");
        RestockMessages.shulkersLeft(new ShulkersLeft(2, 3, 4, false, false, 1)).forEach(ES::render);
        RestockMessages.shulkersLeft(new ShulkersLeft(0, -1, -1, true, true, 0)).forEach(ES::render);
        RestockMessages.shulkersLeft(new ShulkersLeft(1, 2, -1, false, false, 0, true)).forEach(ES::render);
    }
}
