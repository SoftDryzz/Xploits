package com.xploits.console.core;

import java.util.ArrayList;
import java.util.List;

/**
 * Lo que se hace con un texto antes de pintarlo en una terminal (spec consola §6).
 *
 * <p>El texto viene de fuera más de lo que parece: nombres de jugadores, susurros del kitbot,
 * mensajes de excepciones. Un ESC que llegue tal cual a la terminal es una inyección de escapes:
 * puede borrar la pantalla, mover el cursor o cambiar el título. Por eso se sanea todo.
 */
public final class Texto {
    private static final String[] TOKENS_DE_METEOR = {"(highlight)", "(default)"};

    private Texto() {
    }

    /**
     * Quita los códigos {@code §} de Minecraft con su letra y los tokens de Meteor; convierte el
     * tabulador en espacio y elimina el retorno de carro; cambia por {@code ?} los controles C0 (salvo
     * el salto de línea), DEL, los C1 y las marcas bidi. El salto de línea se conserva.
     */
    public static String limpiar(String s) {
        String sinTokens = s;
        for (String token : TOKENS_DE_METEOR) sinTokens = sinTokens.replace(token, "");
        StringBuilder sb = new StringBuilder(sinTokens.length());
        int i = 0;
        while (i < sinTokens.length()) {
            int cp = sinTokens.codePointAt(i);
            i += Character.charCount(cp);
            if (cp == '§') {
                if (i < sinTokens.length()) i += Character.charCount(sinTokens.codePointAt(i));
                continue;
            }
            if (cp == '\n') {
                sb.append('\n');
                continue;
            }
            if (cp == '\t') {
                sb.append(' ');
                continue;
            }
            if (cp == '\r') continue;
            if (esControl(cp) || esBidi(cp)) {
                sb.append('?');
                continue;
            }
            sb.appendCodePoint(cp);
        }
        return sb.toString();
    }

    private static boolean esControl(int cp) {
        return cp < 0x20 || cp == 0x7F || (cp >= 0x80 && cp <= 0x9F);
    }

    private static boolean esBidi(int cp) {
        return cp == 0x200E || cp == 0x200F || (cp >= 0x202A && cp <= 0x202E) || (cp >= 0x2066 && cp <= 0x2069);
    }

    /** Columnas de terminal que ocupa un punto de código: 0 las marcas combinantes, 2 los anchos, 1 el resto. */
    public static int anchoDe(int cp) {
        int tipo = Character.getType(cp);
        if (tipo == Character.NON_SPACING_MARK || tipo == Character.ENCLOSING_MARK) return 0;
        return esAncho(cp) ? 2 : 1;
    }

    private static boolean esAncho(int cp) {
        return (cp >= 0x1100 && cp <= 0x115F)
            || (cp >= 0x2E80 && cp <= 0xA4CF && cp != 0x303F)
            || (cp >= 0xAC00 && cp <= 0xD7A3)
            || (cp >= 0xF900 && cp <= 0xFAFF)
            || (cp >= 0xFE30 && cp <= 0xFE4F)
            || (cp >= 0xFF00 && cp <= 0xFF60)
            || (cp >= 0xFFE0 && cp <= 0xFFE6)
            || (cp >= 0x1F300 && cp <= 0x1F64F)
            || (cp >= 0x1F900 && cp <= 0x1F9FF)
            || (cp >= 0x20000 && cp <= 0x3FFFD);
    }

    /** Columnas que ocupa el texto entero. No entiende escapes: quítalos antes. */
    public static int ancho(String s) {
        int total = 0;
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            total += anchoDe(cp);
            i += Character.charCount(cp);
        }
        return total;
    }

    /** El texto en como mucho {@code cols} columnas; si hubo que cortar, acaba en {@code …}. */
    public static String recortar(String s, int cols) {
        if (cols <= 0) return "";
        if (ancho(s) <= cols) return s;
        StringBuilder sb = new StringBuilder();
        int usado = 0;
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            int w = anchoDe(cp);
            if (usado + w > cols - 1) break;
            sb.appendCodePoint(cp);
            usado += w;
            i += Character.charCount(cp);
        }
        return sb.append('…').toString();
    }

    /**
     * Parte el texto en filas de como mucho {@code cols} columnas, por saltos de línea y por ancho.
     * Si salen más de {@code maxFilas}, se enseñan las primeras y la última acaba en {@code (+N)}, con
     * N las filas que no se ven. Si el sufijo no deja espacio para texto, la última fila es el sufijo truncado.
     */
    public static List<String> envolver(String s, int cols, int maxFilas) {
        if (cols < 2) throw new IllegalArgumentException("no se envuelve en " + cols + " columnas: un carácter ancho necesita 2");
        if (maxFilas <= 0) throw new IllegalArgumentException("no se envuelve en " + maxFilas + " filas");
        String cuerpo = s.endsWith("\n") ? s.substring(0, s.length() - 1) : s;
        List<String> filas = new ArrayList<>();
        for (String linea : cuerpo.split("\n", -1)) {
            StringBuilder actual = new StringBuilder();
            int usado = 0;
            for (int i = 0; i < linea.length(); ) {
                int cp = linea.codePointAt(i);
                int w = anchoDe(cp);
                if (usado > 0 && usado + w > cols) {
                    filas.add(actual.toString());
                    actual.setLength(0);
                    usado = 0;
                }
                actual.appendCodePoint(cp);
                usado += w;
                i += Character.charCount(cp);
            }
            filas.add(actual.toString());
        }
        if (filas.size() <= maxFilas) return filas;
        String sufijo = " (+" + (filas.size() - maxFilas) + ")";
        List<String> visibles = new ArrayList<>(filas.subList(0, maxFilas));
        String ultima = visibles.get(maxFilas - 1);
        int anchoSufijo = ancho(sufijo);
        if (cols - anchoSufijo < 1) {
            visibles.set(maxFilas - 1, recortar(sufijo.strip(), cols));
        } else {
            visibles.set(maxFilas - 1, recortar(ultima, cols - anchoSufijo) + sufijo);
        }
        return visibles;
    }
}
