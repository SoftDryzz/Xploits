package com.xploits.console.ventana;

import com.xploits.console.core.Lector;
import com.xploits.console.core.Registro;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Sigue el final de {@code vivo.log} mientras el juego lo escribe y lo rota (spec consola §5).
 *
 * <p>Nunca deja un fichero abierto: abre por NIO, lee y cierra en cada vuelta. Un fichero abierto
 * con {@code FileInputStream} no comparte el borrado en Windows y bloquearía la rotación del juego.
 */
final class Seguidor {
    private final Path vivo;
    private final Path anterior;
    private final Lector lector = new Lector();
    private String generacion;
    private long leido;

    Seguidor(Path carpeta) {
        vivo = carpeta.resolve("vivo.log");
        anterior = carpeta.resolve("vivo.1.log");
    }

    /** Las líneas completas nuevas desde la vuelta anterior, sin la cabecera. */
    List<String> leer() throws IOException {
        if (!Files.exists(vivo)) return List.of();
        String actual = generacionDe(vivo);
        if (actual == null) return List.of();
        List<String> lineas = new ArrayList<>();
        if (generacion == null) {
            generacion = actual;
            leido = 0;
            lector.olvidar();
        } else if (Lector.detectar(leido, Files.size(vivo), generacion, actual) == Lector.Cambio.ROTADO) {
            // Lo que quedaba del fichero rotado, si es el que se estaba siguiendo.
            if (Files.exists(anterior) && generacion.equals(generacionDe(anterior))) {
                lineas.addAll(cola(anterior, leido).lineas());
            }
            generacion = actual;
            leido = 0;
            lector.olvidar();
        }
        Cola cola = cola(vivo, leido);
        leido = cola.hasta();
        lineas.addAll(cola.lineas());
        lineas.removeIf(Registro::esCabecera);
        return lineas;
    }

    private record Cola(List<String> lineas, long hasta) {
    }

    private Cola cola(Path fichero, long desde) throws IOException {
        try (FileChannel canal = FileChannel.open(fichero, StandardOpenOption.READ)) {
            if (canal.size() <= desde) return new Cola(List.of(), desde);
            canal.position(desde);
            ByteBuffer buffer = ByteBuffer.allocate(64 * 1024);
            List<String> lineas = new ArrayList<>();
            long posicion = desde;
            int n;
            while ((n = canal.read(buffer)) > 0) {
                lineas.addAll(lector.alimentar(buffer.array(), 0, n));
                posicion += n;
                buffer.clear();
            }
            return new Cola(lineas, posicion);
        }
    }

    /** La generación de la cabecera, o null si la primera línea aún no está entera. Lanza si es de otra versión. */
    private static String generacionDe(Path fichero) throws IOException {
        try (FileChannel canal = FileChannel.open(fichero, StandardOpenOption.READ)) {
            ByteBuffer buffer = ByteBuffer.allocate(256);
            int n = canal.read(buffer);
            if (n <= 0) return null;
            String inicio = new String(buffer.array(), 0, n, StandardCharsets.UTF_8);
            int salto = inicio.indexOf('\n');
            if (salto < 0) return null;
            return Registro.generacionDe(inicio.substring(0, salto));
        }
    }
}
