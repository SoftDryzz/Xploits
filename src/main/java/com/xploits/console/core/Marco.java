package com.xploits.console.core;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.StringJoiner;

/**
 * La pantalla entera, fila a fila (spec consola §6): logo, separador, datos, separador, registro,
 * separador, menú y estado. La fila de entrada es la última de la ventana y la pinta la ventana.
 *
 * <p>Si no cabe todo se quita en un orden fijo y <b>se dice en la línea de estado</b>: enseñar
 * menos datos sin avisar sería presentar una cabecera incompleta como si fuera la entera.
 */
public final class Marco {
    public static final int FILAS_MINIMAS_DEL_REGISTRO = 5;
    public static final int FILAS_POR_MENSAJE = 3;
    private static final int ANCHO_FUENTE = 12;
    private static final DateTimeFormatter HORA = DateTimeFormatter.ofPattern("HH:mm:ss");
    /** Índices de {@link Cabecera#NOMBRES} en el orden en que se quitan: módulos, entorno, vuelo, combate. */
    private static final int[] ORDEN_DE_QUITAR = {3, 0, 1, 2};

    private Marco() {
    }

    public record Entrada(Tamano tamano, List<String> arte, Instantanea instantanea, Latido.EstadoJuego juego,
                          List<Registro.Mensaje> mensajes, Filtro filtro, boolean pausa, int nuevas,
                          String aviso, Glifos glifos, ZoneId zona) {
    }

    /** Exactamente {@code tamano.filas() - 1} filas. */
    public static List<String> componer(Entrada e) {
        int cols = e.tamano().cols();
        int alto = e.tamano().filas() - 1;
        List<String> filas = new ArrayList<>();
        if (alto <= 0) return filas;
        if (!e.tamano().cabeElMinimo()) {
            filas.add(Texto.recortar("ventana demasiado pequeña: " + cols + "x" + e.tamano().filas()
                + ", mínimo " + Tamano.MINIMO.cols() + "x" + Tamano.MINIMO.filas(), cols));
            while (filas.size() < alto) filas.add("");
            return filas;
        }

        List<String> ocultas = new ArrayList<>();
        List<String> logo = Banner.elegir(e.arte(), cols, e.tamano().filas());
        if (!Banner.cabe(cols, e.tamano().filas())) ocultas.add("logo");

        List<String> datos = e.instantanea() == null
            ? List.of("sin datos del juego todavía", "", "", "")
            : Cabecera.filas(e.instantanea(), e.glifos());
        boolean[] visible = {true, true, true, true};
        int quedan = 4;
        int cuerpo = filasDelRegistro(alto, logo.size(), quedan);
        for (int i = 0; i < ORDEN_DE_QUITAR.length && cuerpo < FILAS_MINIMAS_DEL_REGISTRO; i++) {
            visible[ORDEN_DE_QUITAR[i]] = false;
            ocultas.add(Cabecera.NOMBRES.get(ORDEN_DE_QUITAR[i]));
            quedan--;
            cuerpo = filasDelRegistro(alto, logo.size(), quedan);
        }

        String separador = Ansi.color(Ansi.CIAN) + e.glifos().linea(cols) + Ansi.RESET;
        filas.addAll(logo);
        filas.add(separador);
        if (quedan > 0) {
            for (int i = 0; i < datos.size(); i++) {
                if (visible[i]) filas.add(Texto.recortar(Texto.limpiar(datos.get(i)), cols));
            }
            filas.add(separador);
        }
        filas.addAll(registro(e, cols, cuerpo));
        filas.add(separador);
        filas.add(Texto.recortar(Menu.LINEA, cols));
        filas.add(estado(e, cols, ocultas));
        return filas;
    }

    private static int filasDelRegistro(int alto, int filasDeLogo, int filasDeDatos) {
        int separadores = filasDeDatos > 0 ? 3 : 2;
        return alto - filasDeLogo - filasDeDatos - separadores - 2;
    }

    /** Los mensajes más recientes abajo; uno que no cabe entero no se enseña a medias. */
    private static List<String> registro(Entrada e, int cols, int filas) {
        LinkedList<String> salida = new LinkedList<>();
        List<Registro.Mensaje> todos = e.mensajes();
        for (int i = todos.size() - 1; i >= 0 && salida.size() < filas; i--) {
            Registro.Mensaje m = todos.get(i);
            if (!e.filtro().acepta(m)) continue;
            String prefijo = HORA.format(Instant.ofEpochMilli(m.epochMs()).atZone(e.zona())) + "  " + fuente(m.fuente()) + "  ";
            List<String> bloque = Texto.envolver(prefijo + Texto.limpiar(m.texto()), cols, FILAS_POR_MENSAJE);
            if (salida.size() + bloque.size() > filas) break;
            String color = switch (m.nivel()) {
                case INFO -> "";
                case AVISO -> Ansi.color(Ansi.AMARILLO);
                case ERROR -> Ansi.color(Ansi.ROJO);
            };
            for (int j = bloque.size() - 1; j >= 0; j--) {
                salida.addFirst(color.isEmpty() ? bloque.get(j) : color + bloque.get(j) + Ansi.RESET);
            }
        }
        while (salida.size() < filas) salida.addFirst("");
        return salida;
    }

    private static String fuente(String fuente) {
        String limpia = Texto.recortar(Texto.limpiar(fuente).replace('\n', ' '), ANCHO_FUENTE);
        return limpia + " ".repeat(Math.max(0, ANCHO_FUENTE - Texto.ancho(limpia)));
    }

    private static String estado(Entrada e, int cols, List<String> ocultas) {
        StringJoiner sj = new StringJoiner(" · ");
        sj.add("filtro: " + e.filtro().etiqueta());
        if (e.pausa()) sj.add("PAUSA (" + e.nuevas() + " nuevas)");
        sj.add(Latido.describir(e.juego()));
        if (!ocultas.isEmpty()) sj.add("ocultas: " + String.join(", ", ocultas));
        if (e.aviso() != null) sj.add(Texto.limpiar(e.aviso()).replace('\n', ' '));
        String texto = Texto.recortar(sj.toString(), cols);
        int color = Latido.color(e.juego());
        return color == 0 ? texto : Ansi.color(color) + texto + Ansi.RESET;
    }
}
