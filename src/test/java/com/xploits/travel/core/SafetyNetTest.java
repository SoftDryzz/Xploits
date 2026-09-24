package com.xploits.travel.core;

import com.xploits.travel.core.SafetyNet.Channel;
import com.xploits.travel.core.SafetyNet.Restoration;
import com.xploits.shared.core.i18n.Catalog;
import com.xploits.shared.core.i18n.Language;
import com.xploits.shared.core.i18n.Msg;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SafetyNetTest {
    private static final String PREFIX = "#";
    private static final Catalog ES = Catalog.load(Language.ES, p -> {
        throw new AssertionError(p);
    });

    private static String es(Msg msg) {
        return ES.render(msg);
    }

    // The text the player would have typed

    @Test
    void plainChatTravelsAsItIsTyped() {
        assertEquals("#elytra", SafetyNet.typedText(Channel.CHAT, "#elytra"));
        assertEquals("hola a todos", SafetyNet.typedText(Channel.CHAT, "hola a todos"));
    }

    @Test
    void aServerCommandGetsItsSlashBack() {
        // The command packet carries "tpy Pepe", not "/tpy Pepe": without giving it its slash back,
        // the net would compare against a text the player never typed.
        assertEquals("/tpy Pepe", SafetyNet.typedText(Channel.COMMAND, "tpy Pepe"));
    }

    @Test
    void nothingIsNull() {
        assertThrows(IllegalArgumentException.class, () -> SafetyNet.typedText(Channel.CHAT, null));
        assertThrows(IllegalArgumentException.class, () -> SafetyNet.typedText(null, "hola"));
    }

    // What the net recognises as one of our commands

    @Test
    void ourOwnBaritoneCommandsAreRecognised() {
        assertTrue(SafetyNet.directs(PREFIX, Channel.CHAT, "#elytra"));
        assertTrue(SafetyNet.directs(PREFIX, Channel.CHAT, "#cancel"));
        assertTrue(SafetyNet.directs(PREFIX, Channel.CHAT, "#set elytraAutoJump true"));
        assertTrue(SafetyNet.directs(PREFIX, Channel.CHAT, "#goal 1000 -2000"));
    }

    @Test
    void whatThePlayerSaysIsNotTouched() {
        assertFalse(SafetyNet.directs(PREFIX, Channel.CHAT, "hola a todos"));
        assertFalse(SafetyNet.directs(PREFIX, Channel.CHAT, "voy al spawn"));
        // It carries the prefix, but not at the start: it is not a command.
        assertFalse(SafetyNet.directs(PREFIX, Channel.CHAT, "el canal es #general"));
    }

    @Test
    void theOtherModulesCommandsSurviveTheTrip() {
        // auto-tpy and kit-requester send slash commands while auto-travel is in charge.
        assertFalse(SafetyNet.directs(PREFIX, Channel.COMMAND, "tpy Pepe"));
        assertFalse(SafetyNet.directs(PREFIX, Channel.COMMAND, "msg Pepe kit por favor"));
    }

    @Test
    void aDifferentPrefixIsHonoured() {
        assertTrue(SafetyNet.directs(">", Channel.CHAT, ">elytra"));
        assertFalse(SafetyNet.directs(">", Channel.CHAT, "#elytra"));
        assertTrue(SafetyNet.directs(".b ", Channel.CHAT, ".b elytra"));
    }

    @Test
    void theSlashIsPartOfTheComparison() {
        // If someone got here with a slash prefix -they cannot: it is rejected at launch-, the command
        // channel would match. This is the exact reason why it is rejected earlier.
        assertTrue(SafetyNet.typedText(Channel.COMMAND, "tpy Pepe").startsWith("/"));
    }

    @Test
    void anUnusablePrefixRecognisesNothingInsteadOfEverything() {
        assertFalse(SafetyNet.directs("", Channel.CHAT, "hola a todos"));
        assertFalse(SafetyNet.directs(null, Channel.CHAT, "hola a todos"));
        assertFalse(SafetyNet.directs("/", Channel.COMMAND, "tpy Pepe"));
    }

    // Which prefixes are accepted at launch

    @Test
    void anOrdinaryPrefixIsAccepted() {
        assertNull(SafetyNet.prefixRejection("#"));
        assertNull(SafetyNet.prefixRejection(">"));
        assertNull(SafetyNet.prefixRejection(".b "));
    }

    @Test
    void anEmptyPrefixIsRejectedWithItsReason() {
        assertEquals(Msg.of(TravelText.PREFIX_EMPTY), SafetyNet.prefixRejection(null));
        assertEquals(Msg.of(TravelText.PREFIX_EMPTY), SafetyNet.prefixRejection(""));
        String reason = es(SafetyNet.prefixRejection(""));
        assertTrue(reason.contains("baritone-prefix"), reason);
    }

    @Test
    void aBlankPrefixIsRejectedWithItsReason() {
        assertEquals(Msg.of(TravelText.PREFIX_BLANK), SafetyNet.prefixRejection("   "));
        String reason = es(SafetyNet.prefixRejection("   "));
        assertTrue(reason.contains("baritone-prefix"), reason);
    }

    @Test
    void aSlashPrefixIsRejectedAndTheReasonNamesWhatItWouldEat() {
        assertEquals(Msg.of(TravelText.PREFIX_SLASH, "prefix", "/"), SafetyNet.prefixRejection("/"));
        String reason = es(SafetyNet.prefixRejection("/"));
        assertTrue(reason.contains("/tpy"), reason);
        assertTrue(reason.contains("kit-requester"), reason);
        assertTrue(reason.contains("baritone-prefix"), reason);
    }

    @Test
    void aPrefixThatMerelyContainsASlashIsFine() {
        assertNull(SafetyNet.prefixRejection("#/"));
    }

    // The restoration's verdict

    @Test
    void aDeliveredRestorationHasNothingToWarnAbout() {
        assertTrue(Restoration.DELIVERED.arrived());
        assertNull(Restoration.DELIVERED.warning(PREFIX));
    }

    @Test
    void aCancelledRestorationSaysBaritoneMayStillBeFlying() {
        assertFalse(Restoration.CANCELLED.arrived());
        assertEquals(Msg.of(TravelText.RESTORATION_CANCELLED, "prefix", PREFIX), Restoration.CANCELLED.warning(PREFIX));
        String warning = es(Restoration.CANCELLED.warning(PREFIX));
        assertTrue(warning.contains("puede seguir volando"), warning);
    }

    @Test
    void aCancelledRestorationSaysTheSettingsStayedWritten() {
        String warning = es(Restoration.CANCELLED.warning(PREFIX));
        assertTrue(warning.contains("elytraAutoSwap"), warning);
        assertTrue(warning.contains("elytraAutoJump"), warning);
        assertTrue(warning.contains("elytraAllowEmergencyLand"), warning);
        assertTrue(warning.contains("elytraConserveFireworks"), warning);
        assertTrue(warning.contains("elytraFireworkSpeed"), warning);
        assertTrue(warning.contains("disco"), warning);
    }

    @Test
    void aCancelledRestorationSaysWhatToTypeByHand() {
        String warning = es(Restoration.CANCELLED.warning(">"));
        // The prefix that failed is named, and at the same time the player is told to use another one:
        // the one Baritone really listens to. Telling them to retry with ">" would send them into the
        // same hole.
        assertTrue(warning.contains(">cancel"), warning);
        assertTrue(warning.contains("#cancel"), warning);
        assertTrue(warning.contains("a mano"), warning);
    }

    @Test
    void aRestorationWithNoPlayerSaysTheSettingsStayedWritten() {
        assertFalse(Restoration.NO_PLAYER.arrived());
        assertEquals(Msg.of(TravelText.RESTORATION_NO_PLAYER, "prefix", PREFIX), Restoration.NO_PLAYER.warning(PREFIX));
        String warning = es(Restoration.NO_PLAYER.warning(PREFIX));
        assertTrue(warning.contains("elytraFireworkSpeed"), warning);
        assertTrue(warning.contains("#set nombre valor"), warning);
    }

    @Test
    void everyFailedRestorationWarns() {
        for (Restoration outcome : Restoration.values()) {
            assertEquals(outcome.arrived(), outcome.warning(PREFIX) == null, outcome.name());
        }
    }

    // The verb of an outgoing command, without its arguments (console spec §7)

    @Test
    void theVerbOfACommandWithoutItsArguments() {
        assertEquals("#goal", SafetyNet.verb("#goal 1200 -800"));
        assertEquals("#set", SafetyNet.verb("  #set elytraAutoJump true"));
        assertEquals("#elytra", SafetyNet.verb("#elytra"));
        assertEquals("(empty)", SafetyNet.verb("   "));
        assertThrows(IllegalArgumentException.class, () -> SafetyNet.verb(null));
    }
}
