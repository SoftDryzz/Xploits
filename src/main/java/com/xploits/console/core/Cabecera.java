package com.xploits.console.core;

import java.util.List;
import java.util.Locale;
import java.util.StringJoiner;

/** Las cuatro filas de datos de la cabecera (spec consola §9). Desconocido es {@code ?}; nunca 0. */
public final class Cabecera {
    public static final List<String> NOMBRES = List.of("entorno", "vuelo", "combate", "módulos");
    private static final String DESCONOCIDO = "?";

    private Cabecera() {
    }

    public static List<String> filas(Instantanea i, Glifos glifos) {
        return List.of(entorno(i), vuelo(i), combate(i), modulos(i, glifos));
    }

    private static String entorno(Instantanea i) {
        String dimension = i.dimension() == null ? DESCONOCIDO : i.dimension().replaceFirst("^minecraft:", "");
        return "dimensión " + dimension + " · jugadores cargados " + n(i.jugadores()) + " · nuestros " + n(i.nuestros());
    }

    private static String vuelo(Instantanea i) {
        String elytra = i.elytraPct() == null ? DESCONOCIDO : i.elytraPct() < 0 ? "no lleva" : i.elytraPct() + " %";
        String desplazamiento;
        if (i.viaje() != null) {
            desplazamiento = "viaje waypoint " + i.viaje().actual() + "/" + i.viaje().total()
                + " · faltan " + i.viaje().restantes() + " bloques";
        } else if (i.barrido() != null) {
            desplazamiento = "barrido pasada " + i.barrido().actual() + "/" + i.barrido().total()
                + " · faltan " + i.barrido().restantes() + " bloques";
        } else {
            desplazamiento = "sin viaje";
        }
        return "cohetes " + n(i.cohetes()) + " · elytra " + elytra + " · " + desplazamiento;
    }

    private static String combate(Instantanea i) {
        String vida = i.vida() == null ? DESCONOCIDO : String.format(Locale.ROOT, "%.1f", i.vida());
        return "vida " + vida + " · armadura " + n(i.armadura()) + " · en barra: obsidiana " + n(i.obsidiana())
            + " · cristales " + n(i.cristales()) + " · telarañas " + n(i.telas()) + " · yunques " + n(i.yunques());
    }

    private static String modulos(Instantanea i, Glifos glifos) {
        if (i.modulos().isEmpty()) return "sin módulos";
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
