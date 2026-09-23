package com.xploits.console.ventana;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Lee el teclado en su propio hilo, línea a línea. Con la entrada de línea normal de Windows basta:
 * el menú son números y Enter (spec consola §3.1, verificado con 40 caracteres a 10 fotogramas/s).
 */
final class Teclado {
    private final BlockingQueue<String> lineas = new LinkedBlockingQueue<>();

    private Teclado() {
    }

    static Teclado arrancar() {
        Teclado teclado = new Teclado();
        Thread hilo = new Thread(teclado::leer, "consola-teclado");
        hilo.setDaemon(true);
        hilo.start();
        return teclado;
    }

    private void leer() {
        try (BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            String linea;
            while ((linea = in.readLine()) != null) lineas.add(linea);
        } catch (IOException ignorada) {
            // Sin teclado la ventana sigue enseñando; solo deja de atender el menú.
        }
    }

    /** La siguiente línea tecleada, o null si no hay ninguna. */
    String siguiente() {
        return lineas.poll();
    }

    /** Espera a un Enter, como mucho diez minutos. Solo para la pantalla de error. */
    void esperarLinea() {
        try {
            lineas.poll(10, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
