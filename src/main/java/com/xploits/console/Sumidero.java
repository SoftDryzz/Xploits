package com.xploits.console;

import com.xploits.XploitsAddon;
import com.xploits.console.core.Historial;
import com.xploits.console.core.Instantanea;
import com.xploits.console.core.Nivel;
import com.xploits.console.core.Perdidas;
import com.xploits.console.core.Registro;
import com.xploits.console.core.Rotacion;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongFunction;
import java.util.stream.Stream;

/**
 * Escribe {@code vivo.log} y el historial desde un hilo propio (spec consola §10), el primero del
 * código de Xploits.
 *
 * <p>Las reglas: el hilo del juego nunca espera aquí -salvo al apagar la consola, cuando
 * {@link #cerrar()} espera como mucho medio segundo a que el escritor termine su lote (el bucle sale
 * a los 200 ms de ver {@code parando})- ni recibe una excepción de aquí; la cola es acotada y lo que
 * no cabe se cuenta y se dice; {@code seq} se asigna al escribir, bajo el mismo cerrojo que la
 * escritura, así que en el fichero siempre crece; y un error de este hilo nunca vuelve a entrar por
 * el sumidero, o se alimentaría a sí mismo.
 */
final class Sumidero {
    private static final int CAPACIDAD = 4_096;
    private static final long LATIDO_MS = 1_000;

    private final Path vivo;
    private final Path anterior;
    private final Path historial;
    private final ZoneId zona = ZoneId.systemDefault();
    private final BlockingQueue<LongFunction<Registro>> cola = new ArrayBlockingQueue<>(CAPACIDAD);
    private final AtomicReference<Instantanea> foto = new AtomicReference<>();
    private final Perdidas perdidas = new Perdidas();
    private Thread hilo;
    private volatile boolean parando;
    private Instantanea ultimaFoto;
    private long ultimaFotoMs;
    private LocalDate diaPodado;
    private boolean falloAvisado;

    Sumidero(Path carpeta) {
        vivo = carpeta.resolve("vivo.log");
        anterior = carpeta.resolve("vivo.1.log");
        historial = carpeta.resolve("historial");
    }

    void arrancar() throws IOException {
        Files.createDirectories(historial);
        if (!Files.exists(vivo) || !cabeceraValida()) empezarFichero(false);
        podar();
        hilo = new Thread(this::bucle, "xploits-consola-escritor");
        hilo.setDaemon(true);
        hilo.start();
    }

    void mensaje(Nivel nivel, String fuente, String texto) {
        long ms = System.currentTimeMillis();
        ofrecer(seq -> new Registro.Mensaje(seq, ms, Salida.SESION, nivel, fuente, texto));
    }

    void juego(String motivo) {
        long ms = System.currentTimeMillis();
        ofrecer(seq -> new Registro.Juego(seq, ms, Salida.SESION, motivo));
    }

    void fin(String lanzamiento, String motivo) {
        long ms = System.currentTimeMillis();
        ofrecer(seq -> new Registro.Fin(seq, ms, Salida.SESION, lanzamiento, motivo));
    }

    void foto(Instantanea instantanea) {
        foto.set(instantanea);
    }

    private void ofrecer(LongFunction<Registro> registro) {
        if (!cola.offer(registro) && perdidas.descartado()) {
            Salida.alertar("La cola de la consola se ha llenado: algunos mensajes no llegarán a su registro. "
                + "Se anotará cuántos.");
        }
    }

    /** Para el hilo y escribe lo que quede. Se llama al apagar la consola. */
    void cerrar() {
        parando = true;
        if (hilo != null) {
            try {
                hilo.join(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        vaciar();
    }

    /** Desde el gancho de apagado del juego: para al escritor, lo pendiente y la despedida. */
    void despedirDelJuego() {
        parando = true;
        if (hilo != null) {
            try {
                hilo.join(1_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        long ms = System.currentTimeMillis();
        vaciar();
        escribir(List.of(seq -> new Registro.Juego(seq, ms, Salida.SESION, "fin")));
    }

    private void bucle() {
        while (!parando) {
            try {
                List<LongFunction<Registro>> lote = new ArrayList<>();
                LongFunction<Registro> primero = cola.poll(200, TimeUnit.MILLISECONDS);
                if (primero != null) {
                    lote.add(primero);
                    cola.drainTo(lote);
                }
                sumarFotoYPerdidas(lote);
                escribir(lote);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    private void vaciar() {
        List<LongFunction<Registro>> lote = new ArrayList<>();
        cola.drainTo(lote);
        sumarFotoYPerdidas(lote);
        escribir(lote);
    }

    /** La foto va si cambió o si pasó un segundo: además de cabecera, es el latido del juego. */
    private synchronized void sumarFotoYPerdidas(List<LongFunction<Registro>> lote) {
        long ahora = System.currentTimeMillis();
        Instantanea f = foto.get();
        if (f != null && (!f.equals(ultimaFoto) || ahora - ultimaFotoMs >= LATIDO_MS)) {
            ultimaFoto = f;
            ultimaFotoMs = ahora;
            lote.add(seq -> new Registro.Foto(seq, ahora, Salida.SESION, f));
        }
        perdidas.drenar().ifPresent(n -> lote.add(seq -> new Registro.Perdida(seq, ahora, Salida.SESION, n)));
    }

    private synchronized void escribir(List<LongFunction<Registro>> lote) {
        if (lote.isEmpty()) return;
        try {
            StringBuilder crudo = new StringBuilder();
            StringBuilder legible = new StringBuilder();
            for (LongFunction<Registro> pendiente : lote) {
                Registro r = pendiente.apply(Salida.siguienteSeq());
                crudo.append(r.codificar()).append('\n');
                String linea = Historial.linea(r, zona);
                if (linea != null) legible.append(linea).append('\n');
            }
            byte[] bytes = crudo.toString().getBytes(StandardCharsets.UTF_8);
            if (!Files.exists(vivo)) empezarFichero(false);
            else if (Rotacion.rotarVivo(Files.size(vivo), bytes.length)) empezarFichero(true);
            Files.write(vivo, bytes, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            LocalDate hoy = LocalDate.now(zona);
            if (!legible.isEmpty()) {
                Files.writeString(historial.resolve(Rotacion.nombreDelDia(hoy)), legible, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            }
            if (!hoy.equals(diaPodado)) podar();
        } catch (IOException | RuntimeException e) {
            if (!falloAvisado) {
                falloAvisado = true;
                XploitsAddon.LOG.error("La consola no puede escribir su registro", e);
                Salida.alertar("La consola no puede escribir su registro: " + e.getMessage());
            }
        }
    }

    private void empezarFichero(boolean rotar) throws IOException {
        if (rotar && Files.exists(vivo)) Files.move(vivo, anterior, StandardCopyOption.REPLACE_EXISTING);
        String generacion = Long.toString(ThreadLocalRandom.current().nextLong() & Long.MAX_VALUE, 36);
        Files.writeString(vivo, Registro.cabecera(generacion) + "\n", StandardCharsets.UTF_8);
    }

    private boolean cabeceraValida() {
        try (BufferedReader in = Files.newBufferedReader(vivo, StandardCharsets.UTF_8)) {
            String primera = in.readLine();
            if (primera == null) return false;
            Registro.generacionDe(primera);
            return true;
        } catch (IOException | IllegalArgumentException e) {
            return false;
        }
    }

    private void podar() {
        LocalDate hoy = LocalDate.now(zona);
        diaPodado = hoy;
        try (Stream<Path> ficheros = Files.list(historial)) {
            List<Rotacion.Fichero> lista = new ArrayList<>();
            for (Path p : ficheros.toList()) {
                String nombre = p.getFileName().toString();
                Optional<LocalDate> fecha = Rotacion.fechaDe(nombre);
                if (fecha.isPresent()) lista.add(new Rotacion.Fichero(nombre, Files.size(p), fecha.get()));
            }
            for (String nombre : Rotacion.borrar(lista, hoy)) {
                Files.deleteIfExists(historial.resolve(nombre));
                mensaje(Nivel.INFO, "consola", "Borrado del historial: " + nombre + " (se guardan " + Rotacion.DIAS
                    + " días o 64 MiB).");
            }
        } catch (IOException e) {
            XploitsAddon.LOG.warn("No se pudo podar el historial de la consola", e);
        }
    }
}
