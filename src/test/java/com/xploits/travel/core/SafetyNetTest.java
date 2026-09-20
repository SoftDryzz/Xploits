package com.xploits.travel.core;

import com.xploits.travel.core.SafetyNet.Channel;
import com.xploits.travel.core.SafetyNet.Restoration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SafetyNetTest {
    private static final String PREFIX = "#";

    // El texto que el jugador habría escrito

    @Test
    void plainChatTravelsAsItIsTyped() {
        assertEquals("#elytra", SafetyNet.typedText(Channel.CHAT, "#elytra"));
        assertEquals("hola a todos", SafetyNet.typedText(Channel.CHAT, "hola a todos"));
    }

    @Test
    void aServerCommandGetsItsSlashBack() {
        // El paquete de comando lleva "tpy Pepe", no "/tpy Pepe": sin devolverle la barra, la red
        // compararía contra un texto que el jugador nunca escribió.
        assertEquals("/tpy Pepe", SafetyNet.typedText(Channel.COMANDO, "tpy Pepe"));
    }

    @Test
    void nothingIsNull() {
        assertThrows(IllegalArgumentException.class, () -> SafetyNet.typedText(Channel.CHAT, null));
        assertThrows(IllegalArgumentException.class, () -> SafetyNet.typedText(null, "hola"));
    }

    // Qué reconoce la red como comando nuestro

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
        // Lleva el prefijo, pero no al principio: no es un comando.
        assertFalse(SafetyNet.directs(PREFIX, Channel.CHAT, "el canal es #general"));
    }

    @Test
    void theOtherModulesCommandsSurviveTheTrip() {
        // auto-tpy y kit-requester mandan comandos con barra mientras auto-travel dirige.
        assertFalse(SafetyNet.directs(PREFIX, Channel.COMANDO, "tpy Pepe"));
        assertFalse(SafetyNet.directs(PREFIX, Channel.COMANDO, "msg Pepe kit por favor"));
    }

    @Test
    void aDifferentPrefixIsHonoured() {
        assertTrue(SafetyNet.directs(">", Channel.CHAT, ">elytra"));
        assertFalse(SafetyNet.directs(">", Channel.CHAT, "#elytra"));
        assertTrue(SafetyNet.directs(".b ", Channel.CHAT, ".b elytra"));
    }

    @Test
    void theSlashIsPartOfTheComparison() {
        // Si alguien llegara aquí con un prefijo de barra -no puede: se rechaza al lanzar-, el
        // canal de comando casaría. Esta es la razón exacta por la que se rechaza antes.
        assertTrue(SafetyNet.typedText(Channel.COMANDO, "tpy Pepe").startsWith("/"));
    }

    @Test
    void anUnusablePrefixRecognisesNothingInsteadOfEverything() {
        assertFalse(SafetyNet.directs("", Channel.CHAT, "hola a todos"));
        assertFalse(SafetyNet.directs(null, Channel.CHAT, "hola a todos"));
        assertFalse(SafetyNet.directs("/", Channel.COMANDO, "tpy Pepe"));
    }

    // Qué prefijos se aceptan al lanzar

    @Test
    void anOrdinaryPrefixIsAccepted() {
        assertNull(SafetyNet.prefixRejection("#"));
        assertNull(SafetyNet.prefixRejection(">"));
        assertNull(SafetyNet.prefixRejection(".b "));
    }

    @Test
    void anEmptyPrefixIsRejectedWithItsReason() {
        assertNotNull(SafetyNet.prefixRejection(null));
        String reason = SafetyNet.prefixRejection("");
        assertNotNull(reason);
        assertTrue(reason.contains("baritone-prefix"), reason);
    }

    @Test
    void aBlankPrefixIsRejectedWithItsReason() {
        String reason = SafetyNet.prefixRejection("   ");
        assertNotNull(reason);
        assertTrue(reason.contains("baritone-prefix"), reason);
    }

    @Test
    void aSlashPrefixIsRejectedAndTheReasonNamesWhatItWouldEat() {
        String reason = SafetyNet.prefixRejection("/");
        assertNotNull(reason);
        assertTrue(reason.contains("/tpy"), reason);
        assertTrue(reason.contains("kit-requester"), reason);
        assertTrue(reason.contains("baritone-prefix"), reason);
    }

    @Test
    void aPrefixThatMerelyContainsASlashIsFine() {
        assertNull(SafetyNet.prefixRejection("#/"));
    }

    // El veredicto de la restauración

    @Test
    void aDeliveredRestorationHasNothingToWarnAbout() {
        assertTrue(Restoration.ENTREGADA.arrived());
        assertNull(Restoration.ENTREGADA.warning(PREFIX));
    }

    @Test
    void aCancelledRestorationSaysBaritoneMayStillBeFlying() {
        assertFalse(Restoration.CANCELADA.arrived());
        String warning = Restoration.CANCELADA.warning(PREFIX);
        assertNotNull(warning);
        assertTrue(warning.contains("puede seguir volando"), warning);
    }

    @Test
    void aCancelledRestorationSaysTheSettingsStayedWritten() {
        String warning = Restoration.CANCELADA.warning(PREFIX);
        assertTrue(warning.contains("elytraAutoSwap"), warning);
        assertTrue(warning.contains("elytraAutoJump"), warning);
        assertTrue(warning.contains("elytraAllowEmergencyLand"), warning);
        assertTrue(warning.contains("elytraConserveFireworks"), warning);
        assertTrue(warning.contains("elytraFireworkSpeed"), warning);
        assertTrue(warning.contains("disco"), warning);
    }

    @Test
    void aCancelledRestorationSaysWhatToTypeByHand() {
        String warning = Restoration.CANCELADA.warning(">");
        // El prefijo que falló se nombra, y a la vez se dice que hay que usar otro: el que Baritone
        // escuche de verdad. Decirle que reintente con ">" sería mandarlo al mismo agujero.
        assertTrue(warning.contains(">cancel"), warning);
        assertTrue(warning.contains("#cancel"), warning);
        assertTrue(warning.contains("a mano"), warning);
    }

    @Test
    void aRestorationWithNoPlayerSaysTheSettingsStayedWritten() {
        assertFalse(Restoration.SIN_JUGADOR.arrived());
        String warning = Restoration.SIN_JUGADOR.warning(PREFIX);
        assertNotNull(warning);
        assertTrue(warning.contains("elytraFireworkSpeed"), warning);
        assertTrue(warning.contains("#set nombre valor"), warning);
    }

    @Test
    void everyFailedRestorationWarns() {
        for (Restoration outcome : Restoration.values()) {
            assertEquals(outcome.arrived(), outcome.warning(PREFIX) == null, outcome.name());
        }
    }
}
