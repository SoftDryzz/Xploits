package com.xploits.console.core;

import com.xploits.shared.core.i18n.Catalog;
import com.xploits.shared.core.i18n.Language;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FormatoTest {
    private static final Catalog ES = Catalog.load(Language.ES, p -> {
        throw new AssertionError(p);
    });
    private static final Catalog EN = Catalog.load(Language.EN, p -> {
        throw new AssertionError(p);
    });
    @Test
    void unFormatoBuenoSaleFormateado() {
        assertEquals(new Formato.Resultado("a b", false), Formato.aplicar(ES, "a %s", "b"));
        assertEquals(new Formato.Resultado("50%", false), Formato.aplicar(ES, "50%%"));
    }

    @Test
    void unFormatoRotoNoPierdeElMensajeYLoDice() {
        Formato.Resultado r = Formato.aplicar(ES, "50%");
        assertTrue(r.roto());
        assertTrue(r.texto().startsWith("50% [formato roto: "), r.texto());
        assertEquals(new Formato.Resultado("%d [formato roto: IllegalFormatConversionException]", true),
            Formato.aplicar(ES, "%d", "x"));
        assertEquals(new Formato.Resultado("%s [formato roto: MissingFormatArgumentException]", true),
            Formato.aplicar(ES, "%s"));
    }

    @Test
    void theBrokenMarkInEnglish() {
        assertEquals(new Formato.Resultado("%s [broken format: MissingFormatArgumentException]", true),
            Formato.aplicar(EN, "%s"));
    }
}
