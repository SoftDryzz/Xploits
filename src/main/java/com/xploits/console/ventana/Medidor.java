package com.xploits.console.ventana;

import com.xploits.console.core.Tamano;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Mide la ventana con {@code mode con}, sin JNI: es lo que se verificó en la sonda, redirigiendo a
 * fichero dentro del propio cmd y con la consola heredada. La salida viene en la página de códigos
 * de la consola; solo interesan los números, así que se lee en ISO-8859-1.
 */
final class Medidor {
    private final Path fichero;

    Medidor(Path carpeta) {
        fichero = carpeta.resolve("tamano.txt");
    }

    Optional<Tamano> medir() {
        try {
            Process p = new ProcessBuilder("cmd.exe", "/c", "mode con > \"" + fichero + "\"").inheritIO().start();
            if (!p.waitFor(2, TimeUnit.SECONDS)) {
                p.destroy();
                return Optional.empty();
            }
            return Tamano.deModeCon(new String(Files.readAllBytes(fichero), StandardCharsets.ISO_8859_1));
        } catch (IOException e) {
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }
}
