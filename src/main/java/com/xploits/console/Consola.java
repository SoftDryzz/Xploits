package com.xploits.console;

import com.xploits.XploitsAddon;
import com.xploits.console.core.Arranque;
import com.xploits.console.core.Ciclo;
import com.xploits.console.core.Nivel;
import com.xploits.shared.XploitsModule;
import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.utils.render.MeteorToast;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.item.Items;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Abre y cierra la ventana de la consola (spec consola §8).
 *
 * <p>Con {@code runInMainMenu} queda fuera del encendido y apagado que Meteor hace al entrar y salir
 * de cada mundo: si se deja encendida, abre al arrancar el juego y sobrevive a desconexiones y
 * muertes. No se lanza en {@code onActivate}, que al arrancar corre antes de que exista nada, sino
 * en el primer tick.
 *
 * <p>Sin mundo, el chat se pierde en silencio (spec §3.2). Por eso los avisos van por el
 * {@link Repartidor}: toast enseguida, y al chat en cuanto hay mundo, esté el módulo encendido o no.
 */
public class Consola extends XploitsModule {
    private static final int TICKS_ENTRE_VISTAZOS = 5;

    private final Ciclo ciclo = new Ciclo(Lanzamiento::nuevoId);
    private final Repartidor repartidor = new Repartidor();
    private Sumidero sumidero;
    private FileChannel canalDelCerrojo;
    private FileLock cerrojo;
    private int ticks;

    public Consola() {
        super(XploitsAddon.CATEGORY, "consola",
            "Abre una ventana de terminal con el logo, el estado del juego y el registro de lo que hace Xploits. "
                + "Sin coordenadas: en la ventana y en su fichero salen sin posición.");
        runInMainMenu = true;
        Salida.instalarGanchoDeApagado();
        MeteorClient.EVENT_BUS.subscribe(repartidor);
    }

    @Override
    public void onActivate() {
        Path carpeta = Lanzamiento.carpeta();
        if (!tomarCerrojo(carpeta)) {
            avisar(Nivel.ERROR, "Otro juego abierto ya usa la consola de esta carpeta (" + carpeta
                + "). Apaga su consola o ciérralo.");
            toggle();
            return;
        }
        Sumidero nuevo = new Sumidero(carpeta);
        try {
            nuevo.arrancar();
        } catch (IOException e) {
            avisar(Nivel.ERROR, "No puedo preparar el registro de la consola en " + carpeta + ": " + e.getMessage());
            soltarCerrojo();
            toggle();
            return;
        }
        sumidero = nuevo;
        Salida.conectar(sumidero);
        sumidero.juego("inicio");
        ticks = 0;
        ejecutar(ciclo.encender());
    }

    @Override
    public void onDeactivate() {
        ejecutar(ciclo.apagar(System.currentTimeMillis()));
        Salida.desconectar();
        if (sumidero != null) {
            sumidero.cerrar();
            sumidero = null;
        }
        soltarCerrojo();
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (++ticks % TICKS_ENTRE_VISTAZOS != 0) return;
        Path carpeta = Lanzamiento.carpeta();
        ejecutar(ciclo.tick(new Ciclo.Observacion(System.currentTimeMillis(),
            Lanzamiento.leerPid(carpeta).orElse(null), Lanzamiento::vivo, Lanzamiento.leerSalida(carpeta).orElse(null))));
        // Puede haberse apagado por lo que acaba de pasar: se vuelve a mirar.
        if (sumidero != null) Salida.instantanea(Colector.tomar());
    }

    private void ejecutar(List<Ciclo.Accion> acciones) {
        for (Ciclo.Accion accion : acciones) {
            switch (accion) {
                case Ciclo.Lanzar l -> lanzar(l.lanzamiento());
                case Ciclo.EscribirFin f -> {
                    if (sumidero != null) sumidero.fin(f.lanzamiento(), "consola apagada");
                }
                case Ciclo.VigilarCierre v -> Lanzamiento.vigilarCierre(Lanzamiento.carpeta(), v);
                case Ciclo.Avisar a -> avisar(a.nivel(), a.texto());
                case Ciclo.ApagarModulo x -> {
                    if (isActive()) toggle();
                }
            }
        }
    }

    private void lanzar(String lanzamiento) {
        Path carpeta = Lanzamiento.carpeta();
        Lanzamiento.borrarRestos(carpeta);
        switch (Lanzamiento.preparar(carpeta, lanzamiento)) {
            case Arranque.Rechazo r -> ejecutar(ciclo.rechazado(r.motivo()));
            case Arranque.Orden o -> {
                try {
                    Lanzamiento.lanzar(o.argv());
                    ejecutar(ciclo.lanzado(o.descripcion(), System.currentTimeMillis()));
                } catch (IOException e) {
                    ejecutar(ciclo.rechazado("Windows no dejó ejecutar la orden (" + e.getMessage() + ")"));
                }
            }
        }
    }

    private boolean tomarCerrojo(Path carpeta) {
        try {
            Files.createDirectories(carpeta);
            canalDelCerrojo = FileChannel.open(carpeta.resolve("consola.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            cerrojo = canalDelCerrojo.tryLock();
        } catch (IOException | OverlappingFileLockException e) {
            cerrojo = null;
        }
        if (cerrojo == null) soltarCerrojo();
        return cerrojo != null;
    }

    private void soltarCerrojo() {
        try {
            if (cerrojo != null) cerrojo.release();
            if (canalDelCerrojo != null) canalDelCerrojo.close();
        } catch (IOException ignorada) {
            // Al cerrarse el juego el sistema lo suelta igualmente.
        }
        cerrojo = null;
        canalDelCerrojo = null;
    }

    private void avisar(Nivel nivel, String texto) {
        switch (nivel) {
            case INFO -> XploitsAddon.LOG.info("[consola] {}", texto);
            case AVISO -> XploitsAddon.LOG.warn("[consola] {}", texto);
            case ERROR -> XploitsAddon.LOG.error("[consola] {}", texto);
        }
        repartidor.encolar(nivel, texto);
    }

    private record Aviso(Nivel nivel, String texto) {
    }

    /**
     * Reparte los avisos de la consola y las alertas de {@link Salida}. Está suscrito siempre, aparte
     * del módulo, porque el aviso más importante -«la ventana no ha arrancado»- llega justo cuando el
     * módulo se apaga, y muchas veces en el menú, donde el chat no existe.
     */
    private final class Repartidor {
        private final List<Aviso> porTostar = new ArrayList<>();
        private final List<Aviso> porChat = new ArrayList<>();

        void encolar(Nivel nivel, String texto) {
            if (nivel != Nivel.INFO) porTostar.add(new Aviso(nivel, texto));
            porChat.add(new Aviso(nivel, texto));
        }

        @EventHandler
        private void onTick(TickEvent.Post event) {
            String alerta;
            while ((alerta = Salida.alertaPendiente()) != null) avisar(Nivel.ERROR, alerta);
            for (Aviso a : porTostar) {
                mc.getToastManager().add(new MeteorToast.Builder("Xploits").text(a.texto()).icon(Items.COMMAND_BLOCK).build());
            }
            porTostar.clear();
            if (mc.world == null || porChat.isEmpty()) return;
            List<Aviso> copia = new ArrayList<>(porChat);
            porChat.clear();
            for (Aviso a : copia) {
                switch (a.nivel()) {
                    case INFO -> info("%s", a.texto());
                    case AVISO -> warning("%s", a.texto());
                    case ERROR -> error("%s", a.texto());
                }
            }
        }
    }
}
