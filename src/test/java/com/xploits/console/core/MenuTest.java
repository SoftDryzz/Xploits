package com.xploits.console.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MenuTest {
    @Test
    void cadaNumeroSuOrden() {
        assertEquals(new Menu.CambiarFiltro(Filtro.TODO), Menu.interpretar("1"));
        assertEquals(new Menu.CambiarFiltro(Filtro.PVP), Menu.interpretar("2"));
        assertEquals(new Menu.CambiarFiltro(Filtro.TRAVEL), Menu.interpretar(" 3 "));
        assertEquals(new Menu.CambiarFiltro(Filtro.SWEEP), Menu.interpretar("4"));
        assertEquals(new Menu.CambiarFiltro(Filtro.AVISOS), Menu.interpretar("5"));
        assertEquals(new Menu.AlternarPausa(), Menu.interpretar("6"));
        assertEquals(new Menu.Salir(), Menu.interpretar("0"));
    }

    @Test
    void loQueNoEsDelMenuSeDice() {
        assertEquals(new Menu.Desconocida("escribe el número de una opción del menú"), Menu.interpretar("   "));
        assertEquals(new Menu.Desconocida("«9» no es una opción del menú"), Menu.interpretar("9"));
    }

    @Test
    void laLineaDelMenu() {
        assertEquals("[1] todo  [2] pvp  [3] travel  [4] sweep  [5] solo avisos  [6] pausar  [0] salir", Menu.LINEA);
        assertEquals(80, Texto.ancho(Menu.LINEA));
    }
}
