package com.xploits.console.core;

import com.xploits.shared.core.i18n.Catalog;
import com.xploits.shared.core.i18n.Language;
import com.xploits.shared.core.i18n.Msg;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MenuTest {
    private static final Catalog ES = Catalog.load(Language.ES, p -> {
        throw new AssertionError(p);
    });
    private static final Catalog EN = Catalog.load(Language.EN, p -> {
        throw new AssertionError(p);
    });
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
        assertEquals("escribe el número de una opción del menú", ES.render(motivo(Menu.interpretar("   "))));
        assertEquals("«9» no es una opción del menú", ES.render(motivo(Menu.interpretar("9"))));
        assertEquals("«9» is not a menu option", EN.render(motivo(Menu.interpretar("9"))));
    }

    @Test
    void laLineaDelMenu() {
        assertEquals("[1] todo  [2] pvp  [3] travel  [4] sweep  [5] solo avisos  [6] pausar  [0] salir", Menu.linea(ES));
        assertEquals(80, Texto.ancho(Menu.linea(ES)));
        assertEquals("[1] all  [2] pvp  [3] travel  [4] sweep  [5] warnings only  [6] pause  [0] exit", Menu.linea(EN));
        assertTrue(Texto.ancho(Menu.linea(EN)) <= 80);
    }

    private static Msg motivo(Menu.Orden orden) {
        return ((Menu.Desconocida) orden).motivo();
    }
}
