package com.xploits.console.core;

import com.xploits.shared.core.i18n.Catalog;
import com.xploits.shared.core.i18n.Language;
import org.junit.jupiter.api.Test;

import java.time.ZoneOffset;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarcoTest {
    private static final Catalog ES = Catalog.load(Language.ES, p -> {
        throw new AssertionError(p);
    });
    private static final Catalog EN = Catalog.load(Language.EN, p -> {
        throw new AssertionError(p);
    });
    private static final List<String> ARTE = Collections.nCopies(13, "X".repeat(99) + Ansi.RESET);
    private static final Instantanea FOTO = new Instantanea("minecraft:overworld", 2, 1, 64, 90, null, null,
        20.0, 20, 64, 12, 4, 1, List.of(new Instantanea.EstadoModulo("auto-pvp", true, "SUPERFICIE")), Language.ES);
    private static final Latido.EstadoJuego VIVO = new Latido.EstadoJuego.Vivo();

    private static Marco.Entrada entrada(int cols, int filas, Instantanea foto, Latido.EstadoJuego juego,
                                         List<Registro.Mensaje> mensajes, Filtro filtro, boolean pausa, int nuevas) {
        return entrada(cols, filas, foto, juego, mensajes, filtro, pausa, nuevas, ES);
    }

    private static Marco.Entrada entrada(int cols, int filas, Instantanea foto, Latido.EstadoJuego juego,
                                         List<Registro.Mensaje> mensajes, Filtro filtro, boolean pausa, int nuevas,
                                         Catalog textos) {
        return new Marco.Entrada(new Tamano(cols, filas), ARTE, foto, juego, mensajes, filtro, pausa, nuevas, null,
            Glifos.UNICODE, ZoneOffset.UTC, textos);
    }

    private static List<String> pintar(int cols, int filas) {
        return sinColor(Marco.componer(entrada(cols, filas, FOTO, VIVO, List.of(), Filtro.TODO, false, 0)));
    }

    private static List<String> sinColor(List<String> filas) {
        return filas.stream().map(Ansi::sinColor).toList();
    }

    @Test
    void alTamanoPedidoCabeTodo() {
        List<String> f = pintar(110, 46);
        assertEquals(45, f.size());
        assertEquals("X".repeat(99), f.get(0));
        assertEquals("X".repeat(99), f.get(12));
        assertEquals("─".repeat(110), f.get(13));
        assertEquals("dimensión overworld · jugadores cargados 2 · nuestros 1", f.get(14));
        assertEquals("● auto-pvp SUPERFICIE", f.get(17));
        assertEquals("─".repeat(110), f.get(18));
        assertEquals("", f.get(41));
        assertEquals("─".repeat(110), f.get(42));
        assertEquals(Menu.linea(ES), f.get(43));
        assertEquals("filtro: todo · juego conectado", f.get(44));
    }

    @Test
    void ningunaFilaEsMasAnchaQueLaVentanaNiFaltaNiSobraNinguna() {
        List<Registro.Mensaje> largos = List.of(new Registro.Mensaje(0, 0, "s", Nivel.INFO, "auto-pvp", "x".repeat(300)));
        for (Tamano t : List.of(new Tamano(60, 12), new Tamano(80, 20), new Tamano(110, 46), new Tamano(200, 60))) {
            List<String> f = sinColor(Marco.componer(entrada(t.cols(), t.filas(), FOTO, VIVO, largos, Filtro.TODO, false, 0)));
            assertEquals(t.filas() - 1, f.size(), t.toString());
            for (String fila : f) assertTrue(Texto.ancho(fila) <= t.cols(), t + ": " + fila);
        }
    }

    @Test
    void sinSitioParaElLogoSaleElNombreYSeDice() {
        List<String> f = pintar(99, 46);
        assertEquals(45, f.size());
        assertEquals("XTO2002", f.get(0));
        assertEquals("filtro: todo · juego conectado · ocultas: logo", f.get(44));
    }

    @Test
    void conPocasFilasSeQuitanDatosEnOrdenYSeDice() {
        List<String> f = pintar(80, 14);
        assertEquals(13, f.size());
        assertTrue(f.get(2).startsWith("cohetes 64"), f.get(2));
        assertTrue(f.get(3).startsWith("vida 20.0"), f.get(3));
        assertEquals("filtro: todo · juego conectado · ocultas: logo, módulos, entorno", f.get(12));
    }

    @Test
    void enElMinimoNoQuedaNingunDato() {
        List<String> f = pintar(80, 12);
        assertEquals(11, f.size());
        assertEquals("XTO2002", f.get(0));
        assertEquals("─".repeat(80), f.get(1));
        assertEquals("─".repeat(80), f.get(8));
        assertEquals(Menu.linea(ES), f.get(9));
        assertEquals("filtro: todo · juego conectado · ocultas: logo, módulos, entorno, vuelo, combate", f.get(10));
    }

    @Test
    void demasiadoPequenaLoDiceYNadaMas() {
        List<String> f = pintar(50, 10);
        assertEquals(9, f.size());
        assertEquals("ventana demasiado pequeña: 50x10, mínimo 60x12", f.get(0));
        for (int i = 1; i < 9; i++) assertEquals("", f.get(i));
    }

    @Test
    void unDatoDesconocidoEsInterrogacionNuncaCero() {
        List<String> f = sinColor(Marco.componer(entrada(110, 46, Instantanea.sinJugador(List.of(), Language.ES), VIVO, List.of(),
            Filtro.TODO, false, 0)));
        assertEquals("dimensión ? · jugadores cargados ? · nuestros ?", f.get(14));
        assertEquals("vida ? · armadura ? · en barra: obsidiana ? · cristales ? · telarañas ? · yunques ?", f.get(16));
    }

    @Test
    void sinNingunaFotoDelJuego() {
        List<String> f = sinColor(Marco.componer(entrada(110, 46, null, new Latido.EstadoJuego.SinDatos(), List.of(),
            Filtro.TODO, false, 0)));
        assertEquals("sin datos del juego todavía", f.get(14));
        assertEquals("filtro: todo · esperando al juego", f.get(44));
    }

    @Test
    void elRegistroFiltraYColoreaLosAvisos() {
        List<Registro.Mensaje> ms = List.of(
            new Registro.Mensaje(0, 43_200_000, "s", Nivel.INFO, "auto-pvp", "a"),
            new Registro.Mensaje(1, 43_200_000, "s", Nivel.AVISO, "auto-travel", "b"));
        List<String> soloPvp = sinColor(Marco.componer(entrada(110, 46, FOTO, VIVO, ms, Filtro.PVP, false, 0)));
        assertEquals("12:00:00  auto-pvp      a", soloPvp.get(41));
        assertEquals("", soloPvp.get(40));
        List<String> todo = Marco.componer(entrada(110, 46, FOTO, VIVO, ms, Filtro.TODO, false, 0));
        assertTrue(todo.get(41).startsWith(Ansi.color(Ansi.AMARILLO)));
        assertEquals("12:00:00  auto-travel   b", Ansi.sinColor(todo.get(41)));
        assertEquals("12:00:00  auto-pvp      a", Ansi.sinColor(todo.get(40)));
    }

    @Test
    void unMensajeConCaracteresAnchosNoDesbordaLaVentana() {
        List<Registro.Mensaje> ms = List.of(new Registro.Mensaje(0, 0, "s", Nivel.INFO, "jugador漢字漢字",
            "漢字".repeat(100) + "😀é"));
        List<String> f = sinColor(Marco.componer(entrada(60, 20, FOTO, VIVO, ms, Filtro.TODO, false, 0)));
        for (String fila : f) assertTrue(Texto.ancho(fila) <= 60, fila);
    }

    @Test
    void laPausaSeVeConLasNuevas() {
        List<String> f = pintar(110, 46);
        assertEquals("filtro: todo · juego conectado", f.get(44));
        List<String> enPausa = sinColor(Marco.componer(entrada(110, 46, FOTO, VIVO, List.of(), Filtro.TODO, true, 3)));
        assertEquals("filtro: todo · PAUSA (3 nuevas) · juego conectado", enPausa.get(44));
    }

    @Test
    void unJuegoQueNoRespondeSeVeEnRojo() {
        List<String> f = Marco.componer(entrada(110, 46, FOTO, new Latido.EstadoJuego.NoResponde(20), List.of(),
            Filtro.TODO, false, 0));
        assertTrue(f.get(44).startsWith(Ansi.color(Ansi.ROJO)));
        assertEquals("filtro: todo · EL JUEGO NO RESPONDE (20 s)", Ansi.sinColor(f.get(44)));
    }

    @Test
    void theStatusLineInEnglish() {
        List<String> f = sinColor(Marco.componer(entrada(110, 46, FOTO, VIVO, List.of(), Filtro.AVISOS, true, 3, EN)));
        assertEquals("[1] all  [2] pvp  [3] travel  [4] sweep  [5] warnings only  [6] pause  [0] exit", f.get(43));
        assertEquals("filter: warnings only · PAUSED (3 new) · game connected", f.get(44));
    }
}
