package com.xploits.console.core;

import com.xploits.shared.core.i18n.Msg;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * La vida de una ventana de consola, desde que se enciende el módulo hasta que se cierra
 * (spec consola §8). Decide; el adaptador ejecuta las acciones que devuelve.
 *
 * <p>Cada lanzamiento lleva un id: un {@code consola.pid}, una salida o una orden de cierre de otro
 * lanzamiento no cuentan. Y cerrar con la X no deja escribir nada (verificado), así que desde aquí
 * <b>no se distingue</b> de un kill, y el aviso no finge que sí.
 */
public final class Ciclo {
    public static final long ESPERA_PID_MS = 10_000;
    public static final long ESPERA_CIERRE_MS = 3_000;

    public record Pid(long pid, long inicioMs, String lanzamiento) {
        public String escribir() {
            return pid + "\t" + inicioMs + "\t" + lanzamiento;
        }

        public static Optional<Pid> leer(String contenido) {
            String[] c = contenido.lines().findFirst().orElse("").split("\t", -1);
            if (c.length != 3 || c[2].isEmpty()) return Optional.empty();
            try {
                return Optional.of(new Pid(Long.parseLong(c[0]), Long.parseLong(c[1]), c[2]));
            } catch (NumberFormatException e) {
                return Optional.empty();
            }
        }
    }

    /** Lo que la ventana deja escrito al irse: {@code usuario} si salió por el menú, {@code error} si reventó. */
    public record Salida(String tipo, String lanzamiento, String detalle) {
        public static final String USUARIO = "usuario";
        public static final String ERROR = "error";

        public static String usuario(String lanzamiento) {
            return USUARIO + "\t" + lanzamiento;
        }

        public static String error(String lanzamiento, String detalle) {
            return ERROR + "\t" + lanzamiento + "\t" + Escape.escapar(detalle);
        }

        public static Optional<Salida> leer(String contenido) {
            String[] c = contenido.lines().findFirst().orElse("").split("\t", -1);
            if (c.length == 2 && c[0].equals(USUARIO)) return Optional.of(new Salida(USUARIO, c[1], ""));
            if (c.length == 3 && c[0].equals(ERROR)) {
                try {
                    return Optional.of(new Salida(ERROR, c[1], Escape.desescapar(c[2])));
                } catch (IllegalArgumentException e) {
                    return Optional.of(new Salida(ERROR, c[1], c[2]));
                }
            }
            return Optional.empty();
        }
    }

    /** Lo que el adaptador ha visto en este tick. {@code vivo} dice si un pid sigue vivo y es el mismo proceso. */
    public record Observacion(long ahoraMs, Pid leido, Predicate<Pid> vivo, Salida salida) {
    }

    public sealed interface Accion {
    }

    public record Lanzar(String lanzamiento) implements Accion {
    }

    public record EscribirFin(String lanzamiento) implements Accion {
    }

    /** Si a los {@code esperaMs} la ventana sigue viva, se mata. {@code pid} es null si aún no se conocía. */
    public record VigilarCierre(String lanzamiento, Long pid, long esperaMs) implements Accion {
    }

    public record Avisar(Nivel nivel, Msg texto) implements Accion {
    }

    public record ApagarModulo() implements Accion {
    }

    private enum Fase { APAGADO, PENDIENTE, LANZANDO, ESPERANDO_PID, VIVA, CERRANDO }

    private final Supplier<String> ids;
    private Fase fase = Fase.APAGADO;
    private String lanzamiento;
    private String orden;
    private long plazo;
    private Pid viva;
    private Pid cerrando;
    private boolean relanzar;

    public Ciclo(Supplier<String> ids) {
        this.ids = Objects.requireNonNull(ids);
    }

    public boolean activo() {
        return fase != Fase.APAGADO;
    }

    public List<Accion> encender() {
        switch (fase) {
            case APAGADO -> fase = Fase.PENDIENTE;
            case CERRANDO -> relanzar = true;
            default -> {
            }
        }
        return List.of();
    }

    public List<Accion> apagar(long ahoraMs) {
        switch (fase) {
            case PENDIENTE -> {
                olvidar();
                return List.of();
            }
            case LANZANDO, ESPERANDO_PID, VIVA -> {
                String lanz = lanzamiento;
                Long pid = viva == null ? null : viva.pid();
                cerrando = viva;
                lanzamiento = null;
                orden = null;
                viva = null;
                fase = Fase.CERRANDO;
                plazo = ahoraMs + ESPERA_CIERRE_MS;
                relanzar = false;
                return List.of(new EscribirFin(lanz), new VigilarCierre(lanz, pid, ESPERA_CIERRE_MS));
            }
            case CERRANDO -> {
                relanzar = false;
                return List.of();
            }
            default -> {
                return List.of();
            }
        }
    }

    public List<Accion> tick(Observacion o) {
        return switch (fase) {
            case APAGADO, LANZANDO -> List.of();
            case PENDIENTE -> {
                lanzamiento = ids.get();
                fase = Fase.LANZANDO;
                yield List.of(new Lanzar(lanzamiento));
            }
            case ESPERANDO_PID -> esperandoPid(o);
            case VIVA -> o.vivo().test(viva) ? List.of() : muerta(o.salida());
            case CERRANDO -> {
                boolean cerrada = o.ahoraMs() >= plazo || (cerrando != null && !o.vivo().test(cerrando));
                if (cerrada) {
                    boolean otraVez = relanzar;
                    olvidar();
                    if (otraVez) fase = Fase.PENDIENTE;
                }
                yield List.of();
            }
        };
    }

    public List<Accion> lanzado(String orden, long ahoraMs) {
        exigirLanzando();
        this.orden = orden;
        plazo = ahoraMs + ESPERA_PID_MS;
        fase = Fase.ESPERANDO_PID;
        return List.of();
    }

    public List<Accion> rechazado(Msg motivo) {
        exigirLanzando();
        olvidar();
        return List.of(new Avisar(Nivel.ERROR, Msg.of(ConsoleText.CANNOT_OPEN, "reason", motivo)), new ApagarModulo());
    }

    private List<Accion> esperandoPid(Observacion o) {
        Pid leido = o.leido();
        if (leido != null && leido.lanzamiento().equals(lanzamiento)) {
            if (o.vivo().test(leido)) {
                viva = leido;
                fase = Fase.VIVA;
                return List.of();
            }
            return muerta(o.salida());
        }
        if (o.ahoraMs() >= plazo) {
            // El F va igualmente: una ventana que arranque tarde lo encuentra y se cierra, en vez de quedarse huérfana.
            Msg texto = Msg.of(ConsoleText.NOT_STARTED, "seconds", ESPERA_PID_MS / 1000, "command", orden);
            String lanz = lanzamiento;
            olvidar();
            return List.of(new Avisar(Nivel.ERROR, texto), new EscribirFin(lanz), new ApagarModulo());
        }
        return List.of();
    }

    private List<Accion> muerta(Salida s) {
        String lanz = lanzamiento;
        olvidar();
        boolean nuestra = s != null && s.lanzamiento().equals(lanz);
        Avisar aviso;
        if (nuestra && s.tipo().equals(Salida.USUARIO)) {
            aviso = new Avisar(Nivel.INFO, Msg.of(ConsoleText.CLOSED_FROM_MENU));
        } else if (nuestra && s.tipo().equals(Salida.ERROR)) {
            aviso = new Avisar(Nivel.ERROR, Msg.of(ConsoleText.CLOSED_BY_ERROR, "detail", s.detalle()));
        } else {
            aviso = new Avisar(Nivel.INFO, Msg.of(ConsoleText.WINDOW_CLOSED));
        }
        return List.of(aviso, new ApagarModulo());
    }

    private void exigirLanzando() {
        if (fase != Fase.LANZANDO) throw new IllegalStateException("no hay ningún lanzamiento en curso");
    }

    private void olvidar() {
        fase = Fase.APAGADO;
        lanzamiento = null;
        orden = null;
        plazo = 0;
        viva = null;
        cerrando = null;
        relanzar = false;
    }
}
