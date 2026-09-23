package com.xploits.console.core;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RegistroTest {
    @Test
    void codificacionExactaDeUnMensaje() {
        assertEquals("R\t7\t1000\ts1\tW\tauto-pvp\ta\\tb",
            new Registro.Mensaje(7, 1000, "s1", Nivel.AVISO, "auto-pvp", "a\tb").codificar());
    }

    @Test
    void cadaTipoVaYVuelve() {
        Instantanea foto = Instantanea.sinJugador(List.of(new Instantanea.EstadoModulo("auto-pvp", true, "x;y")));
        for (Registro r : List.of(
                new Registro.Mensaje(1, 2, "s", Nivel.ERROR, "xploits", "50% \\ fin\\"),
                new Registro.Foto(3, 4, "s", foto),
                new Registro.Juego(5, 6, "s", "inicio"),
                new Registro.Fin(7, 8, "s", "l1", "consola apagada"),
                new Registro.Perdida(9, 10, "s", 42))) {
            assertEquals(r, Registro.decodificar(r.codificar()));
        }
    }

    @Test
    void unaBarraSueltaSeRechaza() {
        assertThrows(IllegalArgumentException.class, () -> Registro.decodificar("R\t1\t2\ts\tI\tf\ttexto\\"));
    }

    @Test
    void camposDeMasODeMenosSeRechazan() {
        assertThrows(IllegalArgumentException.class, () -> Registro.decodificar("J\t1\t2\ts"));
        assertThrows(IllegalArgumentException.class, () -> Registro.decodificar("J\t1\t2\ts\tm\textra"));
    }

    @Test
    void unTipoDesconocidoSeRechazaNombrandolo() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
            () -> Registro.decodificar("X\t1\t2\ts\tm"));
        assertTrue(e.getMessage().contains("X"));
    }

    @Test
    void unaSecuenciaNoNumericaSeRechaza() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
            () -> Registro.decodificar("J\tuno\t2\ts\tm"));
        assertTrue(e.getMessage().contains("seq"));
    }

    @Test
    void unNivelDesconocidoSeRechaza() {
        assertThrows(IllegalArgumentException.class, () -> Registro.decodificar("R\t1\t2\ts\tX\tf\tt"));
    }

    @Test
    void laCabeceraLlevaVersionYGeneracion() {
        assertEquals("#xploits-consola\t1\tabc", Registro.cabecera("abc"));
        assertEquals("abc", Registro.generacionDe(Registro.cabecera("abc")));
        assertTrue(Registro.esCabecera(Registro.cabecera("abc")));
        assertFalse(Registro.esCabecera("R\t1\t2\ts\tI\tf\tt"));
    }

    @Test
    void unaCabeceraDeOtraVersionSeRechazaDiciendoCual() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
            () -> Registro.generacionDe("#xploits-consola\t2\tabc"));
        assertTrue(e.getMessage().contains("2"));
        assertThrows(IllegalArgumentException.class, () -> Registro.generacionDe("hola"));
    }
}
