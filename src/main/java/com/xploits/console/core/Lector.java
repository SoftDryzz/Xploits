package com.xploits.console.core;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Convierte los bytes que se van leyendo del final de {@code vivo.log} en líneas completas.
 *
 * <p>Se decodifica <b>por línea</b>, nunca por trozo: una lectura puede cortar un carácter de varios
 * bytes por la mitad ({@code é}, {@code ▀}), y decodificar el trozo lo convertiría en basura.
 */
public final class Lector {
    private final ByteArrayOutputStream pendiente = new ByteArrayOutputStream();

    public List<String> alimentar(byte[] bytes) {
        return alimentar(bytes, 0, bytes.length);
    }

    public List<String> alimentar(byte[] bytes, int desde, int hasta) {
        List<String> lineas = new ArrayList<>();
        for (int i = desde; i < hasta; i++) {
            byte b = bytes[i];
            if (b == '\n') {
                lineas.add(pendiente.toString(StandardCharsets.UTF_8));
                pendiente.reset();
            } else {
                pendiente.write(b);
            }
        }
        return lineas;
    }

    /** Si hay una línea empezada y sin terminar. */
    public boolean aMedias() {
        return pendiente.size() > 0;
    }

    /** Tira lo pendiente: se usa al pasar de un fichero rotado al nuevo. */
    public void olvidar() {
        pendiente.reset();
    }

    public enum Cambio { SIGUE, ROTADO }

    /**
     * Si el fichero que se sigue es el mismo. Está rotado si su cabecera es de otra generación o si
     * es más corto que lo ya leído.
     */
    public static Cambio detectar(long leido, long tamano, String generacionSeguida, String generacionActual) {
        if (generacionActual != null && !generacionActual.equals(generacionSeguida)) return Cambio.ROTADO;
        if (tamano < leido) return Cambio.ROTADO;
        return Cambio.SIGUE;
    }
}
