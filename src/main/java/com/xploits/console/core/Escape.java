package com.xploits.console.core;

import java.util.ArrayList;
import java.util.List;

/**
 * El escapado de los ficheros de la consola (spec consola §5). Se escapan todos los caracteres que
 * hacen de separador en algún nivel: tabulador (campos), {@code ;} y {@code =} (claves de la
 * instantánea), {@code ,} y {@code |} (lista de módulos), y los saltos.
 */
public final class Escape {
    private Escape() {
    }

    public static String escapar(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '\t' -> sb.append("\\t");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case ';' -> sb.append("\\;");
                case '=' -> sb.append("\\=");
                case ',' -> sb.append("\\,");
                case '|' -> sb.append("\\|");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }

    public static String desescapar(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c != '\\') {
                sb.append(c);
                continue;
            }
            if (i + 1 >= s.length()) throw new IllegalArgumentException("una barra invertida suelta al final del campo");
            char siguiente = s.charAt(++i);
            switch (siguiente) {
                case '\\' -> sb.append('\\');
                case 't' -> sb.append('\t');
                case 'n' -> sb.append('\n');
                case 'r' -> sb.append('\r');
                case ';', '=', ',', '|' -> sb.append(siguiente);
                default -> throw new IllegalArgumentException("escape desconocido: \\" + siguiente);
            }
        }
        return sb.toString();
    }

    /** Parte por un separador que no esté escapado. Las piezas siguen escapadas. */
    public static List<String> partir(String s, char separador) {
        List<String> piezas = new ArrayList<>();
        StringBuilder actual = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                actual.append(c).append(s.charAt(++i));
                continue;
            }
            if (c == separador) {
                piezas.add(actual.toString());
                actual.setLength(0);
                continue;
            }
            actual.append(c);
        }
        piezas.add(actual.toString());
        return piezas;
    }
}
