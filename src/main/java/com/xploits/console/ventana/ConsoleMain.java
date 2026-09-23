package com.xploits.console.ventana;

import com.xploits.console.core.Anillo;
import com.xploits.console.core.Ansi;
import com.xploits.console.core.Arranque;
import com.xploits.console.core.Banner;
import com.xploits.console.core.Ciclo;
import com.xploits.console.core.Filtro;
import com.xploits.console.core.Glifos;
import com.xploits.console.core.Instantanea;
import com.xploits.console.core.Latido;
import com.xploits.console.core.Marco;
import com.xploits.console.core.Menu;
import com.xploits.console.core.Registro;
import com.xploits.console.core.Secuencia;
import com.xploits.console.core.Tamano;
import com.xploits.console.core.Texto;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

/**
 * La ventana de la consola (spec consola §4 y §6). Se ejecuta fuera del juego con
 * {@code java -cp <jar del mod> com.xploits.console.ventana.ConsoleMain <carpeta> <pid del juego> <lanzamiento>},
 * así que solo toca el JDK y {@code console/core}.
 *
 * <p>Si revienta no desaparece: escribe por qué en {@code consola.salida} -para que el juego lo diga
 * en el chat- y en {@code consola-errores.log}, lo enseña en rojo y espera a que el jugador lo lea.
 */
public final class ConsoleMain {
    private static final long SONDEO_MS = 100;
    private static final long FOTOGRAMA_MIN_MS = 100;
    private static final long REPINTAR_SIEMPRE_MS = 1_000;
    private static final long MEDIR_MS = 2_000;
    private static final int CAPACIDAD = 2_000;

    private final Path carpeta;
    private final long pidJuego;
    private final String lanzamiento;
    private final PrintStream out;
    private final String codificacion;
    private final Teclado teclado;
    private final Seguidor seguidor;
    private final Medidor medidor;
    private final Anillo<Registro.Mensaje> anillo = new Anillo<>(CAPACIDAD);
    private final Secuencia secuencia = new Secuencia();

    private Instantanea foto;
    private Long latidoMs;
    private boolean vistoFin;
    private Filtro filtro = Filtro.TODO;
    private boolean pausa;
    private List<Registro.Mensaje> congelado = List.of();
    private int nuevas;
    private String aviso;
    private Tamano tamano = Tamano.PEDIDO;
    private Latido.EstadoJuego juego;
    private boolean sucio = true;
    private boolean reponerPrompt;

    private ConsoleMain(Path carpeta, long pidJuego, String lanzamiento, PrintStream out, String codificacion, Teclado teclado) {
        this.carpeta = carpeta;
        this.pidJuego = pidJuego;
        this.lanzamiento = lanzamiento;
        this.out = out;
        this.codificacion = codificacion;
        this.teclado = teclado;
        this.seguidor = new Seguidor(carpeta);
        this.medidor = new Medidor(carpeta);
    }

    public static void main(String[] args) {
        String codificacion = System.getProperty("stdout.encoding", "UTF-8");
        PrintStream out = new PrintStream(new FileOutputStream(FileDescriptor.out), false, Charset.forName(codificacion));
        if (args.length != 3) {
            out.println("uso: ConsoleMain <carpeta> <pid del juego> <lanzamiento>");
            out.flush();
            System.exit(2);
            return;
        }
        Path carpeta = Path.of(args[0]);
        String lanzamiento = args[2];
        Teclado teclado = Teclado.arrancar();
        try {
            new ConsoleMain(carpeta, Long.parseLong(args[1]), lanzamiento, out, codificacion, teclado).correr();
        } catch (Throwable t) {
            reventar(carpeta, lanzamiento, out, teclado, t);
        }
    }

    private void correr() throws IOException, InterruptedException {
        Files.createDirectories(carpeta);
        ProcessHandle yo = ProcessHandle.current();
        long inicio = yo.info().startInstant().map(Instant::toEpochMilli).orElse(System.currentTimeMillis());
        Files.writeString(carpeta.resolve("consola.pid"), new Ciclo.Pid(yo.pid(), inicio, lanzamiento).escribir(),
            StandardCharsets.UTF_8);

        // chcp 65001 pone la salida en UTF-8 (verificado). Si no llegó a aplicarse, se dibuja en ASCII y se dice.
        Glifos glifos = "UTF-8".equalsIgnoreCase(codificacion) ? Glifos.UNICODE : Glifos.ASCII;
        if (glifos == Glifos.ASCII) aviso = "la consola no está en UTF-8: dibujo el marco en ASCII";
        List<String> arte = Banner.cargar();

        out.print(Ansi.titulo(Arranque.TITULO) + Ansi.tamano(Tamano.PEDIDO.filas(), Tamano.PEDIDO.cols()));
        out.flush();
        Thread.sleep(300);
        tamano = medidor.medir().orElse(Tamano.PEDIDO);
        prepararPantalla();

        long ultimoPintado = 0;
        long ultimaMedida = System.currentTimeMillis();
        while (true) {
            long ahora = System.currentTimeMillis();
            if (!leerFlujo()) return;
            if (!atenderTeclado()) return;
            if (ahora - ultimaMedida >= MEDIR_MS) {
                ultimaMedida = ahora;
                medidor.medir().filter(t -> !t.equals(tamano)).ifPresent(t -> {
                    tamano = t;
                    prepararPantalla();
                });
            }
            Latido.EstadoJuego estado = Latido.evaluar(latidoMs, ahora, juegoVivo(), vistoFin);
            if (!estado.equals(juego)) {
                juego = estado;
                sucio = true;
            }
            boolean toca = sucio || ahora - ultimoPintado >= REPINTAR_SIEMPRE_MS;
            if (toca && ahora - ultimoPintado >= FOTOGRAMA_MIN_MS) {
                pintar(arte, glifos);
                ultimoPintado = ahora;
            }
            Thread.sleep(SONDEO_MS);
        }
    }

    /** Lee lo nuevo de vivo.log. Devuelve false si ha llegado la orden de cerrar este lanzamiento. */
    private boolean leerFlujo() throws IOException {
        List<String> lineas;
        try {
            lineas = seguidor.leer();
        } catch (IllegalArgumentException e) {
            avisar("no puedo leer vivo.log: " + e.getMessage());
            return true;
        }
        for (String linea : lineas) {
            Registro r;
            try {
                r = Registro.decodificar(linea);
            } catch (IllegalArgumentException e) {
                avisar("línea ilegible en vivo.log: " + e.getMessage());
                continue;
            }
            try {
                long perdidos = secuencia.hueco(r.sesion(), r.seq());
                if (perdidos > 0) avisar("se perdieron " + perdidos + " registros");
            } catch (IllegalStateException e) {
                avisar(e.getMessage());
            }
            switch (r) {
                case Registro.Mensaje m -> {
                    anillo.agregar(m);
                    if (pausa && filtro.acepta(m)) nuevas++;
                }
                case Registro.Foto f -> {
                    foto = f.instantanea();
                    latidoMs = f.epochMs();
                }
                case Registro.Juego j -> {
                    vistoFin = j.motivo().equals("fin");
                    latidoMs = j.epochMs();
                }
                case Registro.Fin f -> {
                    if (f.lanzamiento().equals(lanzamiento)) {
                        despedirse();
                        return false;
                    }
                }
                case Registro.Perdida p -> avisar("el juego perdió " + p.cuantos() + " mensajes: su cola se llenó");
            }
            sucio = true;
        }
        return true;
    }

    /** Atiende el menú. Devuelve false si el jugador ha pedido salir. */
    private boolean atenderTeclado() throws IOException {
        String linea;
        while ((linea = teclado.siguiente()) != null) {
            reponerPrompt = true;
            sucio = true;
            switch (Menu.interpretar(linea)) {
                case Menu.CambiarFiltro c -> {
                    filtro = c.filtro();
                    aviso = null;
                }
                case Menu.AlternarPausa p -> {
                    pausa = !pausa;
                    nuevas = 0;
                    congelado = pausa ? anillo.ultimos(CAPACIDAD, m -> true) : List.of();
                    aviso = null;
                }
                case Menu.Salir s -> {
                    Files.writeString(carpeta.resolve("consola.salida"), Ciclo.Salida.usuario(lanzamiento), StandardCharsets.UTF_8);
                    despedirse();
                    return false;
                }
                case Menu.Desconocida d -> aviso = d.motivo();
            }
        }
        return true;
    }

    private void pintar(List<String> arte, Glifos glifos) {
        List<Registro.Mensaje> mensajes = pausa ? congelado : anillo.ultimos(CAPACIDAD, m -> true);
        Latido.EstadoJuego estado = juego == null ? new Latido.EstadoJuego.SinDatos() : juego;
        List<String> filas = Marco.componer(new Marco.Entrada(tamano, arte, foto, estado, mensajes, filtro, pausa, nuevas,
            aviso, glifos, ZoneId.systemDefault()));
        out.print(Ansi.fotograma(filas, tamano.filas(), reponerPrompt));
        out.flush();
        reponerPrompt = false;
        sucio = false;
    }

    /** Pantalla limpia, y la última fila como región de scroll propia para la entrada (verificado en la sonda). */
    private void prepararPantalla() {
        out.print(Ansi.REGION_TODA + Ansi.BORRAR_PANTALLA + Ansi.region(tamano.filas(), tamano.filas())
            + Ansi.irA(tamano.filas(), 1) + "> ");
        out.flush();
        sucio = true;
        reponerPrompt = false;
    }

    private void despedirse() {
        out.print(Ansi.REGION_TODA + Ansi.RESET + "\n");
        out.flush();
    }

    private boolean juegoVivo() {
        return ProcessHandle.of(pidJuego).map(ProcessHandle::isAlive).orElse(false);
    }

    private void avisar(String texto) {
        aviso = texto;
        sucio = true;
    }

    private static void reventar(Path carpeta, String lanzamiento, PrintStream out, Teclado teclado, Throwable t) {
        String detalle = t.getClass().getSimpleName() + ": " + t.getMessage();
        try {
            Files.createDirectories(carpeta);
            StringWriter traza = new StringWriter();
            t.printStackTrace(new PrintWriter(traza));
            Files.writeString(carpeta.resolve("consola-errores.log"), Instant.now() + "  " + traza + "\n",
                StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            Files.writeString(carpeta.resolve("consola.salida"), Ciclo.Salida.error(lanzamiento, detalle), StandardCharsets.UTF_8);
        } catch (IOException ignorada) {
            // Ya no queda dónde dejarlo escrito: se enseña en pantalla, que es lo que sí se puede.
        }
        out.print(Ansi.REGION_TODA + Ansi.RESET + Ansi.BORRAR_PANTALLA + Ansi.irA(1, 1));
        out.println(Ansi.color(Ansi.ROJO) + "La consola de Xploits se ha caído: " + Texto.limpiar(detalle) + Ansi.RESET);
        out.println("El detalle está en " + carpeta.resolve("consola-errores.log"));
        out.println("Pulsa Enter para cerrar.");
        out.flush();
        teclado.esperarLinea();
        System.exit(1);
    }
}
