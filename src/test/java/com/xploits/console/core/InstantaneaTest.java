package com.xploits.console.core;

import com.xploits.shared.core.i18n.Language;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InstantaneaTest {
    private static final Instantanea COMPLETA = new Instantanea("minecraft:the_nether", 3, 1, 64, 87,
        new Instantanea.Progreso(3, 12, 850), null, 18.5, 20, 128, 7, 0, null,
        List.of(new Instantanea.EstadoModulo("auto-pvp", true, "SUPERFICIE"),
            new Instantanea.EstadoModulo("auto-travel", false, "")), Language.ES);

    @Test
    void codificacionExactaCalculadaAMano() {
        assertEquals("dim=minecraft:the_nether;jug=3;nue=1;coh=64;ely=87;via=3/12/850;bar=-;vid=18.5;arm=20;"
            + "obs=128;cri=7;tel=0;yun=-;mod=auto-pvp,1,SUPERFICIE|auto-travel,0,;lng=es", COMPLETA.codificar());
    }

    @Test
    void idaYVueltaConSeparadoresEnLosTextos() {
        Instantanea rara = new Instantanea("a;b=c", 0, 0, 0, -1, null, new Instantanea.Progreso(2, 7, 12400),
            0.0, 0, 0, 0, 0, 0,
            List.of(new Instantanea.EstadoModulo("m,|;=\\", true, "SUPERFICIE · Foo, el de; la=base|x")), Language.EN);
        assertEquals(rara, Instantanea.decodificar(rara.codificar()));
        assertEquals(COMPLETA, Instantanea.decodificar(COMPLETA.codificar()));
    }

    @Test
    void desconocidoNoEsCero() {
        Instantanea sin = Instantanea.decodificar(Instantanea.sinJugador(List.of(), Language.EN).codificar());
        assertNull(sin.jugadores());
        assertNull(sin.vida());
        assertTrue(sin.modulos().isEmpty());
    }

    @Test
    void conModulosSoloCambiaLosModulos() {
        Instantanea otra = COMPLETA.conModulos(List.of());
        assertTrue(otra.modulos().isEmpty());
        assertEquals(COMPLETA.cohetes(), otra.cohetes());
        assertEquals(COMPLETA.viaje(), otra.viaje());
    }

    @Test
    void ceroSigueSiendoCero() {
        assertEquals(0, Instantanea.decodificar(COMPLETA.codificar()).telas());
    }

    @Test
    void unaClaveDesconocidaSeRechazaNombrandola() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
            () -> Instantanea.decodificar(COMPLETA.codificar() + ";zzz=1"));
        assertTrue(e.getMessage().contains("zzz"));
    }

    @Test
    void faltarUnaClaveSeRechazaNombrandola() {
        String sinArmadura = COMPLETA.codificar().replace("arm=20;", "");
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
            () -> Instantanea.decodificar(sinArmadura));
        assertTrue(e.getMessage().contains("arm"));
    }

    @Test
    void languageTravelsInTheSnapshot() {
        Instantanea i = Instantanea.sinJugador(List.of(), Language.ES);
        assertEquals(Language.ES, Instantanea.decodificar(i.codificar()).idioma());
    }

    @Test
    void unknownLanguageFallsBackToEnglish() {
        String s = Instantanea.sinJugador(List.of(), Language.ES).codificar().replace("lng=es", "lng=fr");
        assertEquals(Language.EN, Instantanea.decodificar(s).idioma());
    }

    @Test
    void languageIsRequired() {
        String s = Instantanea.sinJugador(List.of(), Language.ES).codificar().replace(";lng=es", "");
        assertThrows(IllegalArgumentException.class, () -> Instantanea.decodificar(s));
    }
}
