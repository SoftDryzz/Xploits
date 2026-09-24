package com.xploits.console;

import com.xploits.XploitsAddon;
import com.xploits.console.core.Arranque;
import com.xploits.console.core.Ciclo;
import com.xploits.console.core.ConsoleText;
import com.xploits.console.core.Nivel;
import com.xploits.shared.Texts;
import com.xploits.shared.XploitsModule;
import com.xploits.shared.core.i18n.Msg;
import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
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

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Boolean> hideCoordinates = sgGeneral.add(new BoolSetting.Builder()
        .name("hide-coordinates")
        .description(Texts.startupText(ConsoleText.SETTING_HIDE_COORDINATES))
        .defaultValue(true)
        .onChanged(Salida::ocultarCoordenadas)
        .build());

    public Consola() {
        super(XploitsAddon.CATEGORY, "consola", Texts.startupText(ConsoleText.MODULE_DESC));
        runInMainMenu = true;
        Salida.instalarGanchoDeApagado();
        MeteorClient.EVENT_BUS.subscribe(repartidor);
    }

    @Override
    public void onActivate() {
        Path carpeta = Lanzamiento.carpeta();
        if (!tomarCerrojo(carpeta)) {
            avisar(Nivel.ERROR, Msg.of(ConsoleText.IN_USE, "folder", carpeta.toString()));
            toggle();
            return;
        }
        Sumidero nuevo = new Sumidero(carpeta);
        try {
            nuevo.arrancar();
        } catch (IOException e) {
            avisar(Nivel.ERROR, Msg.of(ConsoleText.CANNOT_PREPARE_LOG, "folder", carpeta.toString(), "error", String.valueOf(e.getMessage())));
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

    /** Orbit no captura: una excepción de aquí subiría al tick del juego. Se dice y la consola se apaga. */
    @EventHandler
    private void onTick(TickEvent.Post event) {
        try {
            if (++ticks % TICKS_ENTRE_VISTAZOS != 0) return;
            Path carpeta = Lanzamiento.carpeta();
            ejecutar(ciclo.tick(new Ciclo.Observacion(System.currentTimeMillis(),
                Lanzamiento.leerPid(carpeta).orElse(null), Lanzamiento::vivo, Lanzamiento.leerSalida(carpeta).orElse(null))));
            // Puede haberse apagado por lo que acaba de pasar: se vuelve a mirar.
            if (sumidero != null) Salida.instantanea(Colector.tomar());
        } catch (RuntimeException e) {
            XploitsAddon.LOG.error("La consola ha fallado", e);
            avisar(Nivel.ERROR, Msg.of(ConsoleText.FAILED, "error", e.getClass().getSimpleName() + ": " + e.getMessage()));
            if (isActive()) toggle();
        }
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

    /** Un fallo inesperado al preparar o lanzar se resuelve como rechazo: el ciclo no puede quedarse en LANZANDO. */
    private void lanzar(String lanzamiento) {
        Path carpeta = Lanzamiento.carpeta();
        Lanzamiento.borrarRestos(carpeta);
        Arranque.Resultado resultado;
        try {
            resultado = Lanzamiento.preparar(carpeta, lanzamiento);
        } catch (RuntimeException e) {
            ejecutar(ciclo.rechazado(falloInesperado(e)));
            return;
        }
        switch (resultado) {
            case Arranque.Rechazo r -> ejecutar(ciclo.rechazado(r.motivo()));
            case Arranque.Orden o -> {
                try {
                    Lanzamiento.lanzar(o.argv());
                } catch (IOException e) {
                    ejecutar(ciclo.rechazado(Msg.of(ConsoleText.WINDOWS_REFUSED, "error", String.valueOf(e.getMessage()))));
                    return;
                } catch (RuntimeException e) {
                    ejecutar(ciclo.rechazado(falloInesperado(e)));
                    return;
                }
                ejecutar(ciclo.lanzado(o.descripcion(), System.currentTimeMillis()));
            }
        }
    }

    private static Msg falloInesperado(RuntimeException e) {
        XploitsAddon.LOG.error("Fallo inesperado al preparar la ventana de la consola", e);
        return Msg.of(ConsoleText.UNEXPECTED_FAILURE, "error", e.getClass().getSimpleName() + ": " + e.getMessage());
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

    private void avisar(Nivel nivel, Msg msg) {
        String texto = Texts.render(msg);
        switch (nivel) {
            case INFO -> XploitsAddon.LOG.info("[consola] {}", texto);
            case AVISO -> XploitsAddon.LOG.warn("[consola] {}", texto);
            case ERROR -> XploitsAddon.LOG.error("[consola] {}", texto);
        }
        repartidor.encolar(nivel, msg);
    }

    private record Aviso(Nivel nivel, Msg texto) {
    }

    /**
     * Reparte los avisos de la consola y las alertas de {@link Salida}. Está suscrito siempre, aparte
     * del módulo, porque el aviso más importante -«la ventana no ha arrancado»- llega justo cuando el
     * módulo se apaga, y muchas veces en el menú, donde el chat no existe.
     */
    private final class Repartidor {
        private final List<Aviso> porTostar = new ArrayList<>();
        private final List<Aviso> porChat = new ArrayList<>();

        void encolar(Nivel nivel, Msg texto) {
            if (nivel != Nivel.INFO) porTostar.add(new Aviso(nivel, texto));
            porChat.add(new Aviso(nivel, texto));
        }

        /**
         * Orbit no captura: si repartir falla, se registra y se descarta lo pendiente, para no repetir
         * el mismo fallo en cada tick. No se avisa desde aquí: el aviso podría fallar por lo mismo.
         */
        @EventHandler
        private void onTick(TickEvent.Post event) {
            try {
                Msg alerta;
                while ((alerta = Salida.alertaPendiente()) != null) avisar(Nivel.ERROR, alerta);
                for (Aviso a : porTostar) {
                    mc.getToastManager().add(new MeteorToast.Builder("Xploits").text(Texts.render(a.texto())).icon(Items.COMMAND_BLOCK).build());
                }
                porTostar.clear();
                if (mc.world == null || porChat.isEmpty()) return;
                List<Aviso> copia = new ArrayList<>(porChat);
                porChat.clear();
                for (Aviso a : copia) {
                    switch (a.nivel()) {
                        case INFO -> info(a.texto());
                        case AVISO -> warning(a.texto());
                        case ERROR -> error(a.texto());
                    }
                }
            } catch (RuntimeException e) {
                XploitsAddon.LOG.error("La consola no ha podido repartir sus avisos; se descartan los pendientes", e);
                porTostar.clear();
                porChat.clear();
            }
        }
    }
}
