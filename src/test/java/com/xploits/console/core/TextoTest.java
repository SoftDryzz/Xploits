package com.xploits.console.core;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TextoTest {
    @Test
    void unEscInyectadoNoLlegaALaTerminal() {
        assertEquals("hola?[2Jadios", Texto.limpiar("hola\u001b[2Jadios"));
    }

    @Test
    void losControlesC1TambienSeNeutralizan() {
        assertEquals("a?b", Texto.limpiar("a\u0085b"));
    }

    @Test
    void lasMarcasBidiSeNeutralizan() {
        assertEquals("a?b", Texto.limpiar("a‮b"));
    }

    @Test
    void losCodigosDeColorDeMinecraftSeQuitan() {
        assertEquals("Rojo normal", Texto.limpiar("§cRojo§r normal"));
    }

    @Test
    void losTokensDeMeteorSeQuitan() {
        assertEquals("x", Texto.limpiar("(highlight)x(default)"));
    }

    @Test
    void elTabuladorEsUnEspacioYElRetornoDesaparece() {
        assertEquals("a b\nc", Texto.limpiar("a\tb\r\nc"));
    }

    @Test
    void anchosPorPuntoDeCodigo() {
        assertEquals(5, Texto.ancho("áéíóú"));
        assertEquals(1, Texto.ancho("é"));
        assertEquals(4, Texto.ancho("漢字"));
        assertEquals(2, Texto.ancho("▀▄"));
        assertEquals(2, Texto.ancho("😀"));
    }

    @Test
    void recortarNoTocaLoQueCabe() {
        assertEquals("abcde", Texto.recortar("abcde", 5));
    }

    @Test
    void recortarJustoPorEncimaDelAncho() {
        assertEquals("abcd…", Texto.recortar("abcdef", 5));
    }

    @Test
    void recortarNoParteUnParSustituto() {
        String r = Texto.recortar("ab😀cd", 4);
        assertEquals("ab…", r);
        assertFalse(Character.isHighSurrogate(r.charAt(r.length() - 2)));
    }

    @Test
    void recortarAUnaColumnaOANinguna() {
        assertEquals("…", Texto.recortar("abc", 1));
        assertEquals("", Texto.recortar("abc", 0));
    }

    @Test
    void envolverPorAnchoYPorSaltos() {
        assertEquals(List.of("abc", "def", "gh", "xy"), Texto.envolver("abcdefgh\nxy", 3, 10));
    }

    @Test
    void envolverConDemasiadasFilasLoDice() {
        assertEquals(List.of("aaaaaaaaaa", "aaaa… (+1)"), Texto.envolver("a".repeat(25), 10, 2));
    }

    @Test
    void envolverIgnoraElSaltoFinal() {
        assertEquals(List.of("ab"), Texto.envolver("ab\n", 10, 5));
    }

    @Test
    void envolverUnCaracterAnchoNoDejaFilaVacia() {
        assertEquals(List.of("a", "字"), Texto.envolver("a字", 2, 5));
    }

    @Test
    void envolverConUnSufijoMasAnchoQueLaVentanaNoSeDesborda() {
        assertEquals(List.of("(+6)"), Texto.envolver("a".repeat(25), 4, 1));
    }

    @Test
    void envolverRechazaAnchosOFilasSinSentido() {
        assertThrows(IllegalArgumentException.class, () -> Texto.envolver("a", 0, 1));
        assertThrows(IllegalArgumentException.class, () -> Texto.envolver("a", 1, 1));
        assertThrows(IllegalArgumentException.class, () -> Texto.envolver("a", 5, 0));
    }
}
