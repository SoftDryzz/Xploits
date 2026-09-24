package com.xploits.console.core;

import com.xploits.shared.core.i18n.Catalog;

/** Qué mensajes enseña el registro según la opción del menú. */
public enum Filtro {
    TODO(WindowText.FILTER_ALL),
    PVP(WindowText.FILTER_PVP),
    TRAVEL(WindowText.FILTER_TRAVEL),
    SWEEP(WindowText.FILTER_SWEEP),
    AVISOS(WindowText.FILTER_WARNINGS);

    private final WindowText etiqueta;

    Filtro(WindowText etiqueta) {
        this.etiqueta = etiqueta;
    }

    public String etiqueta(Catalog textos) {
        return textos.render(etiqueta);
    }

    public boolean acepta(Registro.Mensaje m) {
        return switch (this) {
            case TODO -> true;
            case PVP -> m.fuente().equals("auto-pvp");
            case TRAVEL -> m.fuente().equals("auto-travel");
            case SWEEP -> m.fuente().equals("nether-sweep");
            case AVISOS -> m.nivel() != Nivel.INFO;
        };
    }
}
