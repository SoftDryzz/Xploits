package com.xploits.console.core;

import com.xploits.shared.core.i18n.Catalog;
import com.xploits.shared.core.i18n.Language;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoordinateSentinelTest {
    private static final Catalog ES = Catalog.load(Language.ES, p -> {
        throw new AssertionError(p);
    });
    private static final Catalog EN = Catalog.load(Language.EN, p -> {
        throw new AssertionError(p);
    });
    @Test
    void aContainerKeyIsHeld() {
        assertTrue(CoordinateSentinel.isSuspect("obsidian x64 · minecraft:overworld@1234,64,-5678 · visto hace 2 h"));
    }

    @Test
    void alsoWithAllCoordinatesNegative() {
        assertTrue(CoordinateSentinel.isSuspect("minecraft:the_nether@-12,-100,-40"));
    }

    @Test
    void aBaritoneGoalOrGotoIsHeldWithAnyPrefix() {
        assertTrue(CoordinateSentinel.isSuspect("he cancelado \"#goal 1200 -800\" antes de que saliera"));
        assertTrue(CoordinateSentinel.isSuspect("&goto 1 64 -3"));
        assertTrue(CoordinateSentinel.isSuspect("goal 5 6"));
    }

    @Test
    void theOldStatusFragmentIsHeld() {
        assertTrue(CoordinateSentinel.isSuspect("waypoint 3 de 12 en 1200, -800 a 850 bloques"));
        assertTrue(CoordinateSentinel.isSuspect("en -1200, 800"));
    }

    @Test
    void whatIsNotCoordinatesPasses() {
        for (String text : List.of(
                "Waypoint 3 de 5 alcanzado.",
                "Pasada 2 de 4.",
                "10x20 chunks",
                "a 1234 bloques",
                "64 %",
                "12:30:45",
                "#goal 64",
                "subgoal 1 2",
                "Patrón SPIRAL · destino 5000 bloques por X+",
                "Pattern SPIRAL · destination 5000 blocks along X+",
                "overworld a 850 bloques",
                "en 12, 40")) {
            assertFalse(CoordinateSentinel.isSuspect(text), text);
        }
    }

    @Test
    void englishPositionIsHeldToo() {
        assertTrue(CoordinateSentinel.isSuspect("Destination at 1234, -5678"));
        assertTrue(CoordinateSentinel.isSuspect("at -1234, 5678."));
        assertFalse(CoordinateSentinel.isSuspect("that 1234, 5678"));
        assertFalse(CoordinateSentinel.isSuspect("at 12, 34"));
    }

    @Test
    void theHeldNoticeSaysWhoItCameFrom() {
        assertEquals("[retenido: parecía llevar coordenadas · fuente auto-travel]", ES.render(CoordinateSentinel.held("auto-travel")));
        assertEquals("[held back: looked like coordinates · source auto-travel]", EN.render(CoordinateSentinel.held("auto-travel")));
        assertFalse(CoordinateSentinel.isSuspect(ES.render(CoordinateSentinel.held("auto-travel"))));
    }
}
