package com.xploits.console.core;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BannerTest {
    private static final List<String> ARTE = Collections.nCopies(13, "X".repeat(99) + Ansi.RESET);

    @Test
    void elRecursoRealEsValidoYEsElLogo() throws IOException {
        List<String> arte = Banner.cargar();
        assertEquals(13, arte.size());
        int maximo = arte.stream().mapToInt(Banner::anchoVisible).max().orElse(0);
        assertTrue(maximo >= 90 && maximo <= 100, "ancho del logo: " + maximo);
    }

    @Test
    void validarRechazaLoQueNoSirve() {
        assertThrows(IllegalArgumentException.class, () -> Banner.validar(ARTE.subList(0, 12)));
        List<String> conBorrado = new ArrayList<>(ARTE);
        conBorrado.set(3, "\u001b[2J" + Ansi.RESET);
        assertThrows(IllegalArgumentException.class, () -> Banner.validar(conBorrado));
        List<String> sinReset = new ArrayList<>(ARTE);
        sinReset.set(0, "X");
        assertThrows(IllegalArgumentException.class, () -> Banner.validar(sinReset));
        List<String> ancho = new ArrayList<>(ARTE);
        ancho.set(0, "X".repeat(101) + Ansi.RESET);
        assertThrows(IllegalArgumentException.class, () -> Banner.validar(ancho));
    }

    @Test
    void conCienColumnasYCuarentaFilasVaElLogo() {
        assertEquals(ARTE, Banner.elegir(ARTE, 100, 40));
    }

    @Test
    void sinSitioVaElNombreEnUnaFila() {
        List<String> texto = List.of(Ansi.color(Ansi.CIAN) + "XTO2002" + Ansi.RESET);
        assertEquals(texto, Banner.elegir(ARTE, 99, 40));
        assertEquals(texto, Banner.elegir(ARTE, 100, 39));
    }
}
