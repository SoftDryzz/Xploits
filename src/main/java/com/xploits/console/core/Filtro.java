package com.xploits.console.core;

/** Qué mensajes enseña el registro según la opción del menú. */
public enum Filtro {
    TODO("todo"),
    PVP("pvp"),
    TRAVEL("travel"),
    SWEEP("sweep"),
    AVISOS("solo avisos");

    private final String etiqueta;

    Filtro(String etiqueta) {
        this.etiqueta = etiqueta;
    }

    public String etiqueta() {
        return etiqueta;
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
