package com.xploits.console;

import com.xploits.console.core.Arranque;
import com.xploits.console.core.Ciclo;
import meteordevelopment.meteorclient.MeteorClient;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.metadata.ModOrigin;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

/** Lo que el módulo necesita del sistema para abrir, vigilar y cerrar la ventana (spec consola §8). */
final class Lanzamiento {
    private Lanzamiento() {
    }

    static Path carpeta() {
        return MeteorClient.FOLDER.toPath().resolve("xploits").resolve("consola");
    }

    static String nuevoId() {
        return Long.toString(ThreadLocalRandom.current().nextLong() & Long.MAX_VALUE, 36);
    }

    static Arranque.Resultado preparar(Path carpeta, String lanzamiento) {
        Path java = Path.of(System.getProperty("java.home"), "bin", "java.exe");
        if (!Files.exists(java)) {
            java = ProcessHandle.current().info().command().map(c -> Path.of(c).resolveSibling("java.exe")).orElse(java);
        }
        return Arranque.preparar(java, Files.exists(java), classpath(), carpeta, ProcessHandle.current().pid(), lanzamiento,
            Salida.SESION);
    }

    /** El jar del mod, o sus carpetas en desarrollo. {@code getRootPaths()} no sirve: apunta dentro del zip. */
    private static List<Path> classpath() {
        return FabricLoader.getInstance().getModContainer("xploits")
            .map(ModContainer::getOrigin)
            .filter(origen -> origen.getKind() == ModOrigin.Kind.PATH)
            .map(ModOrigin::getPaths)
            .orElse(List.of());
    }

    /**
     * Sin redirigir nada: es exactamente la forma verificada en la sonda. {@code start} abre la ventana
     * y el {@code cmd} intermedio termina enseguida.
     */
    static void lanzar(List<String> argv) throws IOException {
        new ProcessBuilder(argv).start();
    }

    static Optional<Ciclo.Pid> leerPid(Path carpeta) {
        return leer(carpeta.resolve("consola.pid")).flatMap(Ciclo.Pid::leer);
    }

    static Optional<Ciclo.Salida> leerSalida(Path carpeta) {
        return leer(carpeta.resolve("consola.salida")).flatMap(Ciclo.Salida::leer);
    }

    private static Optional<String> leer(Path fichero) {
        try {
            return Files.exists(fichero) ? Optional.of(Files.readString(fichero, StandardCharsets.UTF_8)) : Optional.empty();
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    /** Si el pid sigue vivo y es el mismo proceso: se compara también el instante de arranque, contra la reutilización de pids. */
    static boolean vivo(Ciclo.Pid pid) {
        return ProcessHandle.of(pid.pid())
            .filter(ProcessHandle::isAlive)
            .filter(h -> h.info().startInstant().map(i -> Math.abs(i.toEpochMilli() - pid.inicioMs()) < 2_000).orElse(true))
            .isPresent();
    }

    /** Antes de lanzar: el pid y la salida de lanzamientos anteriores no deben confundir al ciclo. */
    static void borrarRestos(Path carpeta) {
        try {
            Files.deleteIfExists(carpeta.resolve("consola.pid"));
            Files.deleteIfExists(carpeta.resolve("consola.salida"));
        } catch (IOException ignorada) {
            // El ciclo ya ignora lo que no es de su lanzamiento: esto solo es limpieza.
        }
    }

    /** Espera a que la ventana se cierre sola al leer su F; si no lo hace a tiempo, la mata. */
    static void vigilarCierre(Path carpeta, Ciclo.VigilarCierre v) {
        Thread hilo = new Thread(() -> {
            long limite = System.currentTimeMillis() + v.esperaMs();
            while (System.currentTimeMillis() < limite) {
                // Si ya se sabe su pid y está muerta, se cerró sola: no hay nada que hacer.
                Optional<Long> pid = pidDe(carpeta, v);
                if (pid.isPresent() && !ProcessHandle.of(pid.get()).map(ProcessHandle::isAlive).orElse(false)) return;
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    return;
                }
            }
            pidDe(carpeta, v).flatMap(ProcessHandle::of).ifPresent(ProcessHandle::destroy);
        }, "xploits-consola-cierre");
        hilo.setDaemon(true);
        hilo.start();
    }

    private static Optional<Long> pidDe(Path carpeta, Ciclo.VigilarCierre v) {
        if (v.pid() != null) return Optional.of(v.pid());
        return leerPid(carpeta).filter(p -> p.lanzamiento().equals(v.lanzamiento())).map(Ciclo.Pid::pid);
    }
}
