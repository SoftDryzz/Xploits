package com.xploits.console.core;

import com.xploits.shared.core.i18n.Catalog;

import java.util.List;
import java.util.Locale;
import java.util.StringJoiner;

/** Las cuatro filas de datos de la cabecera (spec consola §9). Desconocido es {@code ?}; nunca 0. */
public final class Cabecera {
    /** The four rows' names, in row order, for the "hidden" part of the status line. */
    public static final List<WindowText> NOMBRES = List.of(
        WindowText.SECTION_ENVIRONMENT, WindowText.SECTION_FLIGHT, WindowText.SECTION_COMBAT, WindowText.SECTION_MODULES);
    private static final String DESCONOCIDO = "?";

    private Cabecera() {
    }

    public static List<String> filas(Instantanea i, Glifos glifos, Catalog textos) {
        return List.of(entorno(i, textos), vuelo(i, textos), combate(i, textos), modulos(i, glifos, textos));
    }

    private static String entorno(Instantanea i, Catalog t) {
        String dimension = i.dimension() == null ? DESCONOCIDO : i.dimension().replaceFirst("^minecraft:", "");
        return t.render(WindowText.ENVIRONMENT_ROW, "dimension", dimension, "loaded", n(i.jugadores()), "ours", n(i.nuestros()));
    }

    private static String vuelo(Instantanea i, Catalog t) {
        String elytra = i.elytraPct() == null ? DESCONOCIDO
            : i.elytraPct() < 0 ? t.render(WindowText.ELYTRA_NONE) : i.elytraPct() + " %";
        String desplazamiento;
        if (i.viaje() != null) {
            desplazamiento = t.render(WindowText.TRAVEL_PROGRESS, "current", i.viaje().actual(), "total", i.viaje().total(),
                "remaining", i.viaje().restantes());
        } else if (i.barrido() != null) {
            desplazamiento = t.render(WindowText.SWEEP_PROGRESS, "current", i.barrido().actual(), "total", i.barrido().total(),
                "remaining", i.barrido().restantes());
        } else {
            desplazamiento = t.render(WindowText.NO_TRAVEL);
        }
        return t.render(WindowText.FLIGHT_ROW, "rockets", n(i.cohetes()), "elytra", elytra, "movement", desplazamiento);
    }

    private static String combate(Instantanea i, Catalog t) {
        // Formatted here, with a point in both languages, as the header always showed it.
        String vida = i.vida() == null ? DESCONOCIDO : String.format(Locale.ROOT, "%.1f", i.vida());
        return t.render(WindowText.COMBAT_ROW, "health", vida, "armor", n(i.armadura()), "obsidian", n(i.obsidiana()),
            "crystals", n(i.cristales()), "webs", n(i.telas()), "anvils", n(i.yunques()));
    }

    private static String modulos(Instantanea i, Glifos glifos, Catalog t) {
        if (i.modulos().isEmpty()) return t.render(WindowText.NO_MODULES);
        StringJoiner sj = new StringJoiner("  ");
        for (Instantanea.EstadoModulo m : i.modulos()) {
            String ahora = m.activo() && !m.ahora().isEmpty() ? " " + Texto.limpiar(m.ahora()) : "";
            sj.add((m.activo() ? glifos.activo() : glifos.inactivo()) + " " + Texto.limpiar(m.nombre()) + ahora);
        }
        return sj.toString();
    }

    private static String n(Integer valor) {
        return valor == null ? DESCONOCIDO : valor.toString();
    }
}
