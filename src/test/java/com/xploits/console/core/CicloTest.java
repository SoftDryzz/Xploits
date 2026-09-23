package com.xploits.console.core;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CicloTest {
    private static final Predicate<Ciclo.Pid> VIVOS = p -> true;
    private static final Predicate<Ciclo.Pid> MUERTOS = p -> false;
    private static final Ciclo.Pid PID = new Ciclo.Pid(99, 1, "l1");

    private static Ciclo ciclo() {
        Iterator<String> ids = List.of("l1", "l2", "l3").iterator();
        return new Ciclo(ids::next);
    }

    private static Ciclo.Observacion obs(long ahora, Ciclo.Pid leido, Predicate<Ciclo.Pid> vivo, Ciclo.Salida salida) {
        return new Ciclo.Observacion(ahora, leido, vivo, salida);
    }

    /** Un ciclo con la ventana l1 abierta, pid 99. */
    private static Ciclo viva() {
        Ciclo c = ciclo();
        c.encender();
        c.tick(obs(0, null, VIVOS, null));
        c.lanzado("orden", 0);
        c.tick(obs(500, PID, VIVOS, null));
        return c;
    }

    @Test
    void encenderLanzaEnElPrimerTick() {
        Ciclo c = ciclo();
        assertEquals(List.of(), c.encender());
        assertEquals(List.of(new Ciclo.Lanzar("l1")), c.tick(obs(0, null, VIVOS, null)));
        assertEquals(List.of(), c.tick(obs(50, null, VIVOS, null)));
    }

    @Test
    void conSuPidVivoEstaAbiertaYAlApagarSeLeManda() {
        assertEquals(List.of(new Ciclo.EscribirFin("l1"), new Ciclo.VigilarCierre("l1", 99L, 3000)), viva().apagar(1000));
    }

    @Test
    void sinPidEnDiezSegundosSeDiceConLaOrdenExacta() {
        Ciclo c = ciclo();
        c.encender();
        c.tick(obs(0, null, VIVOS, null));
        c.lanzado("cmd /c algo", 0);
        assertEquals(List.of(), c.tick(obs(9_999, null, VIVOS, null)));
        assertEquals(List.of(new Ciclo.Avisar(Nivel.ERROR, "La consola no ha arrancado en 10 s. Se intentó: cmd /c algo"),
            new Ciclo.ApagarModulo()), c.tick(obs(10_000, null, VIVOS, null)));
        assertFalse(c.activo());
    }

    @Test
    void unFinDeOtroLanzamientoNoCierraEsteNiUnPidAjenoCuenta() {
        Ciclo c = ciclo();
        c.encender();
        c.tick(obs(0, null, VIVOS, null));
        c.lanzado("orden", 0);
        Ciclo.Pid ajeno = new Ciclo.Pid(77, 1, "otro");
        assertEquals(List.of(), c.tick(obs(500, ajeno, VIVOS, null)));
        assertEquals(2, c.tick(obs(10_000, ajeno, VIVOS, null)).size());

        Ciclo abierta = viva();
        assertEquals(List.of(new Ciclo.Avisar(Nivel.INFO, "La ventana de la consola se cerró (con la X o desde fuera)."),
            new Ciclo.ApagarModulo()), abierta.tick(obs(600, PID, MUERTOS, new Ciclo.Salida(Ciclo.Salida.USUARIO, "otro", ""))));
    }

    @Test
    void lasTresFormasDeCerrarseSeDicenDistinto() {
        assertEquals(List.of(new Ciclo.Avisar(Nivel.INFO, "Consola cerrada desde su menú."), new Ciclo.ApagarModulo()),
            viva().tick(obs(600, PID, MUERTOS, new Ciclo.Salida(Ciclo.Salida.USUARIO, "l1", ""))));
        assertEquals(List.of(new Ciclo.Avisar(Nivel.ERROR, "La consola se cerró por un error: NullPointerException: x"),
                new Ciclo.ApagarModulo()),
            viva().tick(obs(600, PID, MUERTOS, new Ciclo.Salida(Ciclo.Salida.ERROR, "l1", "NullPointerException: x"))));
        assertEquals(List.of(new Ciclo.Avisar(Nivel.INFO, "La ventana de la consola se cerró (con la X o desde fuera)."),
            new Ciclo.ApagarModulo()), viva().tick(obs(600, PID, MUERTOS, null)));
    }

    @Test
    void unRechazoSeDiceYApagaElModulo() {
        Ciclo c = ciclo();
        c.encender();
        c.tick(obs(0, null, VIVOS, null));
        assertEquals(List.of(new Ciclo.Avisar(Nivel.ERROR, "No se puede abrir la consola: la ruta tiene &."),
            new Ciclo.ApagarModulo()), c.rechazado("la ruta tiene &"));
        assertFalse(c.activo());
    }

    @Test
    void encenderApagarYEncenderMientrasSeCierraLanzaUnaSolaVez() {
        Ciclo c = viva();
        c.apagar(1000);
        c.encender();
        List<Ciclo.Accion> todas = new ArrayList<>();
        todas.addAll(c.tick(obs(1500, PID, VIVOS, null)));
        todas.addAll(c.tick(obs(2000, PID, MUERTOS, null)));
        todas.addAll(c.tick(obs(2050, null, VIVOS, null)));
        todas.addAll(c.tick(obs(2100, null, VIVOS, null)));
        assertEquals(List.of(new Ciclo.Lanzar("l2")), todas);
    }

    @Test
    void apagadaYSinVolverAEncenderNoLanzaNada() {
        Ciclo c = viva();
        c.apagar(1000);
        assertEquals(List.of(), c.tick(obs(5000, PID, MUERTOS, null)));
        assertFalse(c.activo());
    }

    @Test
    void apagarAntesDelPrimerTickNoHaceNada() {
        Ciclo c = ciclo();
        c.encender();
        assertEquals(List.of(), c.apagar(0));
        assertEquals(List.of(), c.tick(obs(0, null, VIVOS, null)));
        assertFalse(c.activo());
    }

    @Test
    void decirQueSeLanzoSinEstarLanzandoEsUnFalloDelAdaptador() {
        assertThrows(IllegalStateException.class, () -> ciclo().lanzado("x", 0));
        assertThrows(IllegalStateException.class, () -> ciclo().rechazado("x"));
    }

    @Test
    void pidYSalidaSeEscribenYSeLeen() {
        assertEquals(Optional.of(new Ciclo.Pid(123, 456, "l1")), Ciclo.Pid.leer(new Ciclo.Pid(123, 456, "l1").escribir()));
        assertEquals(Optional.empty(), Ciclo.Pid.leer("basura"));
        assertEquals(Optional.of(new Ciclo.Salida(Ciclo.Salida.ERROR, "l1", "a\tb")), Ciclo.Salida.leer(Ciclo.Salida.error("l1", "a\tb")));
        assertEquals(Optional.of(new Ciclo.Salida(Ciclo.Salida.USUARIO, "l1", "")), Ciclo.Salida.leer(Ciclo.Salida.usuario("l1")));
        assertEquals(Optional.empty(), Ciclo.Salida.leer(""));
    }
}
