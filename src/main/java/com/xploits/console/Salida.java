package com.xploits.console;

import com.xploits.console.core.Centinela;
import com.xploits.console.core.ConsoleText;
import com.xploits.console.core.Instantanea;
import com.xploits.console.core.Nivel;
import com.xploits.shared.Texts;
import com.xploits.shared.core.i18n.Msg;

import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * La puerta de todo lo que va a la consola (spec consola §7 y §10). Con la consola apagada no va a
 * ningún sitio: el histórico solo se escribe con ella encendida, por decisión del usuario.
 *
 * <p>Todo pasa por el centinela antes de llegar al sumidero. Los sitios con coordenadas se marcan
 * en el origen; esto es la red por si alguno se escapa.
 */
public final class Salida {
    /** La sesión de este juego: {@code seq} es monótono dentro de ella. */
    public static final String SESION = Long.toString(ThreadLocalRandom.current().nextLong() & Long.MAX_VALUE, 36);

    private static final AtomicLong SEQ = new AtomicLong();
    private static final Queue<Msg> ALERTAS = new ConcurrentLinkedQueue<>();
    private static final AtomicBoolean CENTINELA_AVISADO = new AtomicBoolean();
    private static final AtomicBoolean GANCHO = new AtomicBoolean();
    private static volatile Sumidero activo;

    private Salida() {
    }

    static void conectar(Sumidero sumidero) {
        activo = sumidero;
    }

    static void desconectar() {
        activo = null;
    }

    static long siguienteSeq() {
        return SEQ.getAndIncrement();
    }

    public static void mensaje(Nivel nivel, String fuente, String texto) {
        Sumidero s = activo;
        if (s == null) return;
        String escrito = texto;
        if (Centinela.sospecha(texto)) {
            avisarCentinela(fuente);
            escrito = Texts.render(Centinela.retenido(fuente));
        }
        s.mensaje(nivel, fuente, escrito);
    }

    public static void instantanea(Instantanea foto) {
        Sumidero s = activo;
        if (s == null) return;
        List<Instantanea.EstadoModulo> modulos = new ArrayList<>();
        for (Instantanea.EstadoModulo m : foto.modulos()) {
            if (Centinela.sospecha(m.ahora())) {
                avisarCentinela(m.nombre());
                modulos.add(new Instantanea.EstadoModulo(m.nombre(), m.activo(), Texts.render(ConsoleText.HELD_SHORT)));
            } else {
                modulos.add(m);
            }
        }
        s.foto(foto.conModulos(modulos));
    }

    private static void avisarCentinela(String fuente) {
        if (CENTINELA_AVISADO.compareAndSet(false, true)) {
            alertar(Msg.of(ConsoleText.UNMARKED_COORDINATES, "source", fuente));
        }
    }

    static void alertar(Msg texto) {
        ALERTAS.add(texto);
    }

    /** La siguiente alerta por repartir, o null. Se reparte desde el hilo del juego. */
    static Msg alertaPendiente() {
        return ALERTAS.poll();
    }

    /** Una sola vez por JVM: al cerrarse el juego, lo pendiente y la despedida. */
    static void instalarGanchoDeApagado() {
        if (!GANCHO.compareAndSet(false, true)) return;
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            Sumidero s = activo;
            if (s != null) s.despedirDelJuego();
        }, "xploits-consola-apagado"));
    }
}
