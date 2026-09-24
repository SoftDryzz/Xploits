package com.xploits.console.core;

import com.xploits.shared.core.i18n.Catalog;
import com.xploits.shared.core.i18n.Language;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CentinelaTest {
    private static final Catalog ES = Catalog.load(Language.ES, p -> {
        throw new AssertionError(p);
    });
    private static final Catalog EN = Catalog.load(Language.EN, p -> {
        throw new AssertionError(p);
    });
    @Test
    void laClaveDeUnContenedorSeRetiene() {
        assertTrue(Centinela.sospecha("obsidian x64 · minecraft:overworld@1234,64,-5678 · visto hace 2 h"));
    }

    @Test
    void tambienConTodasLasCoordenadasNegativas() {
        assertTrue(Centinela.sospecha("minecraft:the_nether@-12,-100,-40"));
    }

    @Test
    void unGoalOGotoDeBaritoneSeRetieneConCualquierPrefijo() {
        assertTrue(Centinela.sospecha("he cancelado \"#goal 1200 -800\" antes de que saliera"));
        assertTrue(Centinela.sospecha("&goto 1 64 -3"));
        assertTrue(Centinela.sospecha("goal 5 6"));
    }

    @Test
    void elFragmentoAntiguoDeStatusSeRetiene() {
        assertTrue(Centinela.sospecha("waypoint 3 de 12 en 1200, -800 a 850 bloques"));
        assertTrue(Centinela.sospecha("en -1200, 800"));
    }

    @Test
    void loQueNoSonCoordenadasPasa() {
        for (String texto : List.of(
                "Waypoint 3 de 5 alcanzado.",
                "Pasada 2 de 4.",
                "10x20 chunks",
                "a 1234 bloques",
                "64 %",
                "12:30:45",
                "#goal 64",
                "subgoal 1 2",
                "Patrón ESPIRAL · destino 5000 bloques por X+",
                "overworld a 850 bloques",
                "en 12, 40")) {
            assertFalse(Centinela.sospecha(texto), texto);
        }
    }

    @Test
    void englishPositionIsHeldToo() {
        assertTrue(Centinela.sospecha("Destination at 1234, -5678"));
        assertTrue(Centinela.sospecha("at -1234, 5678."));
        assertFalse(Centinela.sospecha("that 1234, 5678"));
        assertFalse(Centinela.sospecha("at 12, 34"));
    }

    @Test
    void loRetenidoDiceDeQuienVenia() {
        assertEquals("[retenido: parecía llevar coordenadas · fuente auto-travel]", ES.render(Centinela.retenido("auto-travel")));
        assertEquals("[held back: looked like coordinates · source auto-travel]", EN.render(Centinela.retenido("auto-travel")));
        assertFalse(Centinela.sospecha(ES.render(Centinela.retenido("auto-travel"))));
    }
}
