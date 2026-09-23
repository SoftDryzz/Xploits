package com.xploits.console.core;

import com.xploits.shared.core.i18n.Catalog;
import com.xploits.shared.core.i18n.Language;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FiltroTest {
    private static final Catalog ES = Catalog.load(Language.ES, p -> {
        throw new AssertionError(p);
    });
    private static final Catalog EN = Catalog.load(Language.EN, p -> {
        throw new AssertionError(p);
    });
    private static Registro.Mensaje m(Nivel nivel, String fuente) {
        return new Registro.Mensaje(0, 0, "s", nivel, fuente, "x");
    }

    @Test
    void cadaFiltroConSuContraejemplo() {
        assertTrue(Filtro.TODO.acepta(m(Nivel.INFO, "stash-keeper")));
        assertTrue(Filtro.PVP.acepta(m(Nivel.INFO, "auto-pvp")));
        assertFalse(Filtro.PVP.acepta(m(Nivel.INFO, "auto-travel")));
        assertTrue(Filtro.TRAVEL.acepta(m(Nivel.INFO, "auto-travel")));
        assertFalse(Filtro.TRAVEL.acepta(m(Nivel.INFO, "nether-sweep")));
        assertTrue(Filtro.SWEEP.acepta(m(Nivel.INFO, "nether-sweep")));
        assertFalse(Filtro.SWEEP.acepta(m(Nivel.AVISO, "auto-pvp")));
        assertTrue(Filtro.AVISOS.acepta(m(Nivel.AVISO, "auto-pvp")));
        assertTrue(Filtro.AVISOS.acepta(m(Nivel.ERROR, "xploits")));
        assertFalse(Filtro.AVISOS.acepta(m(Nivel.INFO, "auto-pvp")));
    }

    @Test
    void theLabelsComeFromTheCatalog() {
        assertEquals("solo avisos", Filtro.AVISOS.etiqueta(ES));
        assertEquals("warnings only", Filtro.AVISOS.etiqueta(EN));
    }
}
