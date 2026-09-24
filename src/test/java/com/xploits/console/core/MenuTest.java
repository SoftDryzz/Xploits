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
    void eachNumberItsCommand() {
        assertEquals(new Menu.ChangeFilter(LogFilter.ALL), Menu.parse("1"));
        assertEquals(new Menu.ChangeFilter(LogFilter.PVP), Menu.parse("2"));
        assertEquals(new Menu.ChangeFilter(LogFilter.TRAVEL), Menu.parse(" 3 "));
        assertEquals(new Menu.ChangeFilter(LogFilter.SWEEP), Menu.parse("4"));
        assertEquals(new Menu.ChangeFilter(LogFilter.WARNINGS), Menu.parse("5"));
        assertEquals(new Menu.TogglePause(), Menu.parse("6"));
        assertEquals(new Menu.Quit(), Menu.parse("0"));
    }

    @Test
    void whatIsNotOnTheMenuIsReported() {
        assertEquals("escribe el número de una opción del menú", ES.render(reason(Menu.parse("   "))));
        assertEquals("«9» no es una opción del menú", ES.render(reason(Menu.parse("9"))));
        assertEquals("«9» is not a menu option", EN.render(reason(Menu.parse("9"))));
    }

    @Test
    void theMenuLine() {
        assertEquals("[1] todo  [2] pvp  [3] travel  [4] sweep  [5] solo avisos  [6] pausar  [0] salir", Menu.line(ES));
        assertEquals(80, TerminalText.width(Menu.line(ES)));
        assertEquals("[1] all  [2] pvp  [3] travel  [4] sweep  [5] warnings only  [6] pause  [0] exit", Menu.line(EN));
        assertTrue(TerminalText.width(Menu.line(EN)) <= 80);
    }

    private static Msg reason(Menu.Command command) {
        return ((Menu.Unknown) command).reason();
    }
}
