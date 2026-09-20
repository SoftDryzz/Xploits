package com.xploits.travel;

import com.xploits.XploitsAddon;
import com.xploits.elytra.ElytraReplace;
import com.xploits.travel.core.Axis;
import com.xploits.travel.core.BaritoneScript;
import com.xploits.travel.core.Destination;
import com.xploits.travel.core.FireworkWatch;
import com.xploits.travel.core.FlightPattern;
import com.xploits.travel.core.PatternParams;
import com.xploits.travel.core.Route;
import com.xploits.travel.core.RoutePlanner;
import com.xploits.travel.core.StallWatch;
import com.xploits.travel.core.Waypoint;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.settings.StringSetting;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.movement.elytrafly.ElytraFly;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.render.MeteorToast;
import meteordevelopment.orbit.EventHandler;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.c2s.play.ChatCommandSignedC2SPacket;
import net.minecraft.network.packet.c2s.play.ChatMessageC2SPacket;
import net.minecraft.network.packet.c2s.play.CommandExecutionC2SPacket;
import net.minecraft.sound.SoundEvents;

import java.util.List;

/**
 * Adaptador de AutoTravel (spec §3): fija un destino, le pide al núcleo la ruta con patrón de
 * despiste, ordena los tres sistemas que se disputan la elytra, lanza el vuelo de Baritone por
 * comandos de chat y lo devuelve todo a su sitio al terminar.
 *
 * <p>Toda la geometría y toda la construcción de comandos viven en {@code travel.core}, que se
 * prueba sin arrancar el juego. Aquí solo queda lo que necesita a Minecraft: leer la posición,
 * emitir los comandos, vigilar el progreso, encender y apagar módulos, y armar la red de seguridad.
 *
 * <p><b>Encender el módulo no vuela.</b> El viaje lo lanza el jugador con {@code .xploits travel
 * go}, porque un módulo que despega solo al activarse te manda a 100 000 bloques por un clic.
 *
 * <p><b>La red de seguridad (spec §7) es la razón de ser del módulo.</b> Mandarle {@code #elytra} a
 * Baritone significa escribir en el chat y confiar en que él lo intercepte antes de que salga el
 * paquete. La guarda que Meteor tiene para eso depende de {@code BaritoneUtils.IS_AVAILABLE}, que
 * en estas instancias vale {@code false} aunque Baritone esté instalado (spec §2), así que la
 * confianza no basta: mientras el módulo dirige, cancela él mismo todo paquete de chat saliente
 * cuyo texto empiece por el prefijo de Baritone. En un servidor anarchy, un {@code #elytra} que se
 * escape es anunciarle al servidor entero que vas volando y hacia dónde.
 */
public class AutoTravel extends Module {
    /** El id con el que Baritone se registra en el cargador de mods. */
    private static final String BARITONE_MOD_ID = "baritone";

    /** Sin acercarse al waypoint durante este tiempo, el viaje se corta (spec §8). */
    private static final double STALL_SECONDS = 30;

    /** Cuánto tiene que bajar la distancia para contar como avance, en bloques. */
    private static final double PROGRESS_EPSILON = 1.0;

    /** Las dos formas de pedir un destino (spec §4), como vocabulario de los ajustes. */
    public enum DestinationMode {
        COORDENADAS,
        AUTOPISTA
    }

    private final SettingGroup sgDestination = settings.getDefaultGroup();
    private final SettingGroup sgPattern = settings.createGroup("Patrón");
    private final SettingGroup sgFlight = settings.createGroup("Vuelo");
    private final SettingGroup sgNotify = settings.createGroup("Avisos");

    // Destino (spec §4)

    private final Setting<DestinationMode> destinationMode = sgDestination.add(new EnumSetting.Builder<DestinationMode>()
        .name("destination-mode")
        .description("COORDENADAS: un punto del mundo. AUTOPISTA: una distancia por un eje, desde donde estés.")
        .defaultValue(DestinationMode.COORDENADAS)
        .build()
    );

    private final Setting<Double> destinationX = sgDestination.add(new DoubleSetting.Builder()
        .name("x")
        .description("Coordenada X del destino.")
        .defaultValue(0)
        .sliderRange(-100_000, 100_000)
        .decimalPlaces(0)
        .visible(() -> destinationMode.get() == DestinationMode.COORDENADAS)
        .build()
    );

    private final Setting<Double> destinationZ = sgDestination.add(new DoubleSetting.Builder()
        .name("z")
        .description("Coordenada Z del destino.")
        .defaultValue(0)
        .sliderRange(-100_000, 100_000)
        .decimalPlaces(0)
        .visible(() -> destinationMode.get() == DestinationMode.COORDENADAS)
        .build()
    );

    private final Setting<Axis> axis = sgDestination.add(new EnumSetting.Builder<Axis>()
        .name("axis")
        .description("El eje de autopista por el que se viaja.")
        .defaultValue(Axis.X_PLUS)
        .visible(() -> destinationMode.get() == DestinationMode.AUTOPISTA)
        .build()
    );

    private final Setting<Double> highwayDistance = sgDestination.add(new DoubleSetting.Builder()
        .name("highway-distance")
        .description("Cuántos bloques recorrer por el eje, contados desde donde arranque el viaje.")
        .defaultValue(10_000)
        .min(0)
        .sliderRange(0, 100_000)
        .decimalPlaces(0)
        .visible(() -> destinationMode.get() == DestinationMode.AUTOPISTA)
        .build()
    );

    private final Setting<Double> highwayMaxAmplitude = sgDestination.add(new DoubleSetting.Builder()
        .name("highway-max-amplitude")
        .description("Lo más que el patrón puede apartarse del eje en modo autopista (spec §4.2).")
        .defaultValue(300)
        .min(0)
        .sliderRange(0, 2_000)
        .decimalPlaces(0)
        .visible(() -> destinationMode.get() == DestinationMode.AUTOPISTA)
        .build()
    );

    private final Setting<Double> waypointMargin = sgDestination.add(new DoubleSetting.Builder()
        .name("waypoint-margin")
        .description("A menos de esta distancia de un waypoint se pasa al siguiente.")
        .defaultValue(30)
        .min(1)
        .sliderRange(5, 500)
        .decimalPlaces(0)
        .build()
    );

    // Patrón y sus parámetros (spec §5)

    private final Setting<FlightPattern> pattern = sgPattern.add(new EnumSetting.Builder<FlightPattern>()
        .name("pattern")
        .description("El patrón de despiste. SENUELO se rechaza en modo autopista, no se degrada (spec §4.2).")
        .defaultValue(FlightPattern.RECTO)
        .build()
    );

    private final Setting<Double> amplitude = sgPattern.add(new DoubleSetting.Builder()
        .name("zigzag-amplitude")
        .description("Cuánto se aparta el ZIGZAG a cada lado del rumbo, en bloques.")
        .defaultValue(PatternParams.defaults().amplitude())
        .min(0)
        .sliderRange(0, 2_000)
        .decimalPlaces(0)
        .visible(() -> pattern.get() == FlightPattern.ZIGZAG)
        .build()
    );

    private final Setting<Double> period = sgPattern.add(new DoubleSetting.Builder()
        .name("zigzag-period")
        .description("Cada cuántos bloques de avance cambia de lado el ZIGZAG.")
        .defaultValue(PatternParams.defaults().period())
        .min(1)
        .sliderRange(100, 20_000)
        .decimalPlaces(0)
        .visible(() -> pattern.get() == FlightPattern.ZIGZAG)
        .build()
    );

    private final Setting<Double> legLength = sgPattern.add(new DoubleSetting.Builder()
        .name("quiebro-leg")
        .description("Cada cuántos bloques de avance quiebra el QUIEBRO.")
        .defaultValue(PatternParams.defaults().legLength())
        .min(1)
        .sliderRange(500, 40_000)
        .decimalPlaces(0)
        .visible(() -> pattern.get() == FlightPattern.QUIEBRO)
        .build()
    );

    private final Setting<Double> lateralOffset = sgPattern.add(new DoubleSetting.Builder()
        .name("quiebro-offset")
        .description("Cuánto se aparta el QUIEBRO a cada lado del rumbo, en bloques.")
        .defaultValue(PatternParams.defaults().lateralOffset())
        .min(0)
        .sliderRange(0, 5_000)
        .decimalPlaces(0)
        .visible(() -> pattern.get() == FlightPattern.QUIEBRO)
        .build()
    );

    private final Setting<Double> spiralRadius = sgPattern.add(new DoubleSetting.Builder()
        .name("spiral-radius")
        .description("A cuántos bloques del destino empieza la espiral final.")
        .defaultValue(PatternParams.defaults().spiralRadius())
        .min(0)
        .sliderRange(100, 10_000)
        .decimalPlaces(0)
        .visible(() -> pattern.get() == FlightPattern.ESPIRAL)
        .build()
    );

    private final Setting<Double> spiralTurns = sgPattern.add(new DoubleSetting.Builder()
        .name("spiral-turns")
        .description("Cuántas vueltas da la espiral al cerrarse sobre el destino.")
        .defaultValue(PatternParams.defaults().spiralTurns())
        .min(0)
        .sliderRange(0.5, 6)
        .decimalPlaces(1)
        .visible(() -> pattern.get() == FlightPattern.ESPIRAL)
        .build()
    );

    private final Setting<Double> decoyAngle = sgPattern.add(new DoubleSetting.Builder()
        .name("decoy-angle")
        .description("Cuántos grados se aparta el señuelo del rumbo real.")
        .defaultValue(PatternParams.defaults().decoyAngleDegrees())
        .range(0, 89)
        .sliderRange(0, 89)
        .decimalPlaces(0)
        .visible(() -> pattern.get() == FlightPattern.SENUELO)
        .build()
    );

    private final Setting<Double> decoyFraction = sgPattern.add(new DoubleSetting.Builder()
        .name("decoy-fraction")
        .description("Qué fracción del tramo hacia el señuelo se recorre antes de corregir.")
        .defaultValue(PatternParams.defaults().decoyFraction())
        .range(0.05, 0.95)
        .sliderRange(0.05, 0.95)
        .decimalPlaces(2)
        .visible(() -> pattern.get() == FlightPattern.SENUELO)
        .build()
    );

    // Vuelo: el prefijo, los cuatro ajustes con su valor de reposo, y el reposo de los dos módulos

    private final Setting<String> prefix = sgFlight.add(new StringSetting.Builder()
        .name("baritone-prefix")
        .description("El prefijo con el que Baritone lee sus comandos. Cámbialo solo si lo has cambiado en Baritone.")
        .defaultValue("#")
        .build()
    );

    private final Setting<Boolean> autoJump = sgFlight.add(new BoolSetting.Builder()
        .name("auto-jump")
        .description("elytraAutoJump durante el vuelo: que Baritone despegue solo.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> autoJumpResting = sgFlight.add(new BoolSetting.Builder()
        .name("auto-jump-resting")
        .description("A qué valor se devuelve elytraAutoJump al aterrizar. Hay que declararlo: los ajustes de "
            + "Baritone se pueden escribir pero no leer (spec §6.1).")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> allowEmergencyLand = sgFlight.add(new BoolSetting.Builder()
        .name("emergency-land")
        .description("elytraAllowEmergencyLand durante el vuelo: que aterrice de urgencia antes que estrellarse.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> allowEmergencyLandResting = sgFlight.add(new BoolSetting.Builder()
        .name("emergency-land-resting")
        .description("A qué valor se devuelve elytraAllowEmergencyLand al aterrizar.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> conserveFireworks = sgFlight.add(new BoolSetting.Builder()
        .name("conserve-fireworks")
        .description("elytraConserveFireworks durante el vuelo: gastar menos fuegos a cambio de ir más lento.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> conserveFireworksResting = sgFlight.add(new BoolSetting.Builder()
        .name("conserve-fireworks-resting")
        .description("A qué valor se devuelve elytraConserveFireworks al aterrizar.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Double> fireworkSpeed = sgFlight.add(new DoubleSetting.Builder()
        .name("firework-speed")
        .description("elytraFireworkSpeed durante el vuelo.")
        .defaultValue(1)
        .min(0)
        .sliderRange(0.5, 3)
        .decimalPlaces(2)
        .build()
    );

    private final Setting<Double> fireworkSpeedResting = sgFlight.add(new DoubleSetting.Builder()
        .name("firework-speed-resting")
        .description("A qué valor se devuelve elytraFireworkSpeed al aterrizar.")
        .defaultValue(1)
        .min(0)
        .sliderRange(0.5, 3)
        .decimalPlaces(2)
        .build()
    );

    private final Setting<String> netherSeed = sgFlight.add(new StringSetting.Builder()
        .name("nether-seed")
        .description("La semilla del Nether, si se conoce. Vacía se deja en paz: sin semilla Baritone apaga la "
            + "predicción de terreno él solo, que es lo correcto (spec §8.1).")
        .defaultValue("")
        .build()
    );

    private final Setting<Boolean> elytraFlyResting = sgFlight.add(new BoolSetting.Builder()
        .name("elytra-fly-resting")
        .description("Si ElytraFly de Meteor debe quedar encendido al aterrizar. Durante el vuelo se apaga siempre: "
            + "Baritone declara que su vuelo no funciona con impulso no vanilla (spec §2). Aterrizar con él apagado "
            + "sin saberlo es tan malo como el problema original, así que aquí se declara a qué se devuelve.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> elytraReplaceResting = sgFlight.add(new BoolSetting.Builder()
        .name("elytra-replace-resting")
        .description("Si elytra-replace debe quedar encendido al aterrizar. Durante el vuelo se enciende siempre: "
            + "el cambio de elytra lo manda el nuestro, no el de Baritone (spec §6.2).")
        .defaultValue(true)
        .build()
    );

    // Avisos

    private final Setting<Boolean> notify = sgNotify.add(new BoolSetting.Builder()
        .name("notify")
        .description("Aviso local al lanzar, al pasar de waypoint y al terminar. Los avisos fuertes -la red de "
            + "seguridad, el atasco y los fuegos que se acaban- salen siempre, lo apagues o no.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> notifySound = sgNotify.add(new BoolSetting.Builder()
        .name("notify-sound")
        .description("Sonido en los avisos fuertes.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> fireworkThreshold = sgNotify.add(new IntSetting.Builder()
        .name("firework-warning")
        .description("Con estos fuegos artificiales o menos sale el aviso fuerte (spec §8). Cero avisa solo al "
            + "quedarse sin ninguno, que a 100 000 bloques suele ser tarde. No cuenta los que vayan dentro de shulkers.")
        .defaultValue(16)
        .min(0)
        .sliderRange(0, 128)
        .build()
    );

    /** Si hay un viaje en marcha: lo único que distingue "encendido" de "dirigiendo". */
    private boolean travelling;

    /**
     * Si la red de seguridad está armada. Los {@code @EventHandler} de un módulo solo reciben
     * eventos mientras el módulo está activo, pero eso no basta: el módulo puede estar encendido sin
     * viaje en marcha, y entonces no tiene por qué comerse los comandos que el jugador escriba a
     * mano. La red se arma antes de emitir el primer comando y se desarma cuando ya no queda
     * ninguno por emitir.
     */
    private boolean netArmed;

    /** Si ya se avisó de que la red ha tenido que cancelar algo en este viaje (spec §7: una sola vez). */
    private boolean netCaughtWarned;

    /**
     * El prefijo con el que arrancó este viaje. La red y la restauración usan este, no el del ajuste:
     * si el jugador edita el prefijo a mitad de vuelo, los comandos que ya circulan siguen siendo los
     * de antes, y la restauración tiene que hablarle a Baritone en el mismo idioma en que se le habló
     * al despegar.
     */
    private String activePrefix = "";

    private List<Waypoint> waypoints = List.of();
    private int index;

    /**
     * La vigilancia del atasco (spec §8), en el núcleo y con tests. El adaptador solo le da el
     * waypoint al que va y la distancia que queda; ella lleva la cuenta y decide si se corta,
     * incluido reiniciarse sola al cambiar de waypoint.
     */
    private final StallWatch stallWatch = StallWatch.ofSeconds(STALL_SECONDS, PROGRESS_EPSILON);

    /**
     * La decisión del aviso de fuegos (spec §8), también en el núcleo. Aquí solo se cuentan los
     * fuegos del inventario y se saca el toast; cuándo avisar y cuándo rearmar el aviso es suyo.
     * Se construye al lanzar cada viaje porque el umbral es un ajuste y puede haber cambiado.
     */
    private FireworkWatch fireworkWatch = new FireworkWatch(0);

    public AutoTravel() {
        super(XploitsAddon.CATEGORY, "auto-travel",
            "Prepara el entorno, lanza el vuelo con elytra de Baritone por una ruta con patrón de despiste, y lo restaura todo al aterrizar.");
    }

    @Override
    public void onActivate() {
        // Encender no vuela: se espera al comando del jugador.
        resetTrip();
    }

    @Override
    public void onDeactivate() {
        // Salida 5 (apagado del módulo) y, de hecho, también la 4: cuando se deja el mundo, Modules
        // llama a onDeactivate() de todos los módulos activos, así que este es el camino que de
        // verdad se recorre al desconectar. El handler de GameLeftEvent está igualmente, porque el
        // orden entre los dos no está garantizado; finish() es idempotente y el segundo no hace nada.
        finish("auto-travel se ha apagado", false);
    }

    /** Salida 4: desconexión o cambio de mundo. */
    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        finish("se ha dejado el mundo", false);
    }

    /**
     * La red de seguridad (spec §7). {@code ClientConnectionMixin} de Meteor publica este evento al
     * entrar en {@code ClientConnection.send} y cancela el envío si el evento se cancela, así que
     * esto no es una advertencia: el paquete muere dentro del cliente.
     */
    @EventHandler
    private void onPacketSend(PacketEvent.Send event) {
        if (!netArmed) return;

        String text = chatTextOf(event.packet);
        if (text == null || !text.startsWith(activePrefix)) return;

        event.cancel();
        warnNetCaught(text);
    }

    /**
     * El texto que un paquete saliente llevaría al chat del servidor, o {@code null} si no es un
     * paquete de chat. Los comandos de Baritone viajan como chat plano -no empiezan por barra-, pero
     * se miran también los dos caminos de comando por si alguien configura un prefijo que empiece por
     * {@code /}: ahí el texto viaja sin la barra, y se le devuelve para compararlo con el prefijo tal
     * y como el jugador lo escribiría.
     */
    private static String chatTextOf(Packet<?> packet) {
        if (packet instanceof ChatMessageC2SPacket chat) return chat.chatMessage();
        if (packet instanceof CommandExecutionC2SPacket command) return "/" + command.command();
        if (packet instanceof ChatCommandSignedC2SPacket command) return "/" + command.command();
        return null;
    }

    /**
     * Que la red haya tenido que actuar significa que Baritone no está interceptando, y eso el
     * jugador lo quiere saber: cualquier comando que escriba a mano sí se publicaría. Una vez por
     * viaje, y fuerte: si se repitiera por cada comando de la preparación serían ocho líneas
     * seguidas y se perdería la única que importa.
     */
    private void warnNetCaught(String text) {
        if (netCaughtWarned) return;
        netCaughtWarned = true;

        String message = "Baritone NO está interceptando sus comandos: he cancelado \"" + text
            + "\" antes de que saliera al servidor. Lo que escribas a mano con ese prefijo SÍ se publicaría.";
        warning("%s", message);
        loudToast(message, Items.BARRIER);
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (!travelling) return;

        if (mc.player == null || mc.world == null) {
            finish("se ha perdido el mundo", true);
            return;
        }
        // Salida 3: muerte. No hay evento de muerte en Meteor, así que se observa aquí; el jugador
        // sigue existiendo en la pantalla de muerte, así que la restauración todavía puede hablarle
        // a Baritone.
        if (!mc.player.isAlive()) {
            finish("has muerto a mitad de viaje", true);
            return;
        }

        checkFireworks();

        Waypoint here = new Waypoint(mc.player.getX(), mc.player.getZ());
        double distance = here.distanceTo(waypoints.get(index));

        if (distance <= waypointMargin.get()) {
            index++;
            // Salida 1: llegada.
            if (index >= waypoints.size()) {
                finish("has llegado al destino", false);
                return;
            }
            if (notify.get()) info("Waypoint %d de %d alcanzado.", index, waypoints.size());
            aimAtCurrentWaypoint();
            return;
        }

        // Salida 6: atasco. Lo único observable desde fuera es si la distancia baja; Baritone no
        // informa de nada más (spec §8). El índice va en la llamada a propósito: es lo que hace que
        // el salto de distancia al cambiar de waypoint no se lea como treinta segundos sin avanzar.
        if (stallWatch.tick(index, distance)) {
            String message = String.format("Sin acercarme al waypoint %d en %d s, a %d bloques: corto y restauro.",
                index + 1, stallWatch.limitSeconds(), Math.round(distance));
            warning("%s", message);
            loudToast(message, Items.ELYTRA);
            finish("atasco", false);
        }
    }

    /**
     * El aviso fuerte de fuegos artificiales (spec §8: <i>"Enterarse a 100k importa"</i>). Lo único
     * que hace el adaptador es contarlos; si el aviso toca o no -y si ya salió- lo decide {@link
     * FireworkWatch}.
     *
     * <p>Camino verificado contra las fuentes remapeadas de {@code meteor-client:1.21.11-SNAPSHOT}:
     * {@code InvUtils.find(Item...)} recorre {@code mc.player.getInventory().getStack(i)} de 0 a
     * {@code size()} y suma {@code getCount()} de cada pila que case, así que cubre la barra rápida,
     * el inventario principal, la armadura y la mano secundaria; devuelve {@code count 0} sin
     * jugador en vez de reventar. Es el mismo camino que usa el propio {@code ElytraFlightMode} de
     * Meteor para sus fuegos. Lo que hay dentro de un shulker no se cuenta, igual que en
     * {@code elytra-replace}.
     */
    private void checkFireworks() {
        int fireworks = InvUtils.find(Items.FIREWORK_ROCKET).count();
        if (!fireworkWatch.observe(fireworks)) return;

        String message = fireworks == 0
            ? "Te has quedado SIN fuegos artificiales a mitad de vuelo: Baritone no puede seguir impulsándose."
            : String.format("Te quedan %d fuegos artificiales, el aviso está puesto en %d: repón o aterriza.",
                fireworks, fireworkWatch.threshold());
        warning("%s", message);
        loudToast(message, Items.FIREWORK_ROCKET);
    }

    /**
     * Lanza el viaje (salida de ninguna: es la entrada). Devuelve el mensaje que el comando tiene que
     * enseñar, sea el del lanzamiento o el motivo por el que no se vuela. Ningún fallo es silencioso
     * y, ante la duda, no se manda un solo comando (spec §9).
     */
    public String start() {
        if (!isActive()) return "auto-travel está apagado: enciéndelo antes de lanzar un viaje.";
        if (travelling) return "Ya hay un viaje en marcha: córtalo antes de lanzar otro.";
        if (mc.player == null || mc.world == null) return "No hay mundo cargado: no se lanza nada.";
        if (!FabricLoader.getInstance().isModLoaded(BARITONE_MOD_ID)) {
            // A propósito NO se usa BaritoneUtils.IS_AVAILABLE: Meteor lo pone a true tras un
            // Class.forName("baritone.api.BaritoneAPI") sobre una clase que el jar ofuscado no
            // expone, así que ahí vale false aunque Baritone esté perfectamente instalado (spec §2).
            return "Baritone no está cargado: este módulo vuela con sus comandos y sin él no hay nada que dirigir.";
        }

        String launchPrefix = prefix.get();
        if (launchPrefix == null || launchPrefix.isEmpty()) {
            // El núcleo también lo rechaza, pero ahí ya sería a mitad de secuencia y con la red
            // armada sobre un prefijo vacío, que no reconocería nada como suyo.
            return "El prefijo de Baritone está vacío: los comandos saldrían como chat plano al servidor. No se lanza nada.";
        }

        Waypoint origin = new Waypoint(mc.player.getX(), mc.player.getZ());
        Route route = RoutePlanner.plan(origin, destination(), pattern.get(), params(), highwayMaxAmplitude.get());
        if (route.isRejected()) return "No se vuela: " + route.rejection() + ".";

        activePrefix = launchPrefix;
        waypoints = route.waypoints();
        index = 0;
        travelling = true;
        stallWatch.reset();
        // El umbral es un ajuste: se toma al despegar, para que no cambie a mitad de vuelo.
        fireworkWatch = new FireworkWatch(fireworkThreshold.get());

        // El orden de la preparación es el de spec §6.2: la red ANTES de emitir el primer comando.
        armNet();
        prepare();
        aimAtCurrentWaypoint();

        Waypoint target = waypoints.get(waypoints.size() - 1);
        return String.format("Viaje lanzado con patrón %s: %d waypoints hasta %d, %d, a %d bloques en línea recta.",
            pattern.get(), waypoints.size(), Math.round(target.x()), Math.round(target.z()),
            Math.round(origin.distanceTo(target)));
    }

    /** Salida 2: cancelación del jugador. Devuelve el mensaje que el comando tiene que enseñar. */
    public String stop() {
        if (!travelling) return "No hay ningún viaje en marcha.";
        finish("lo has cancelado", false);
        return "Viaje cortado y entorno restaurado.";
    }

    public boolean isTravelling() {
        return travelling;
    }

    /** Preparación de spec §6.2, pasos 3 a 6: los dos módulos y la secuencia de ajustes de Baritone. */
    private void prepare() {
        switchModule(Modules.get().get(ElytraFly.class), false);
        switchModule(Modules.get().get(ElytraReplace.class), true);
        for (String command : BaritoneScript.preparation(activePrefix, flightSettings())) send(command);
    }

    /** Fija el waypoint actual y relanza el vuelo: Baritone toma el objetivo al arrancar, no después. */
    private void aimAtCurrentWaypoint() {
        send(BaritoneScript.goTo(activePrefix, waypoints.get(index)));
        send(BaritoneScript.launch(activePrefix));
    }

    /**
     * El único final de los seis caminos de salida (spec §6.3). Es idempotente: quien llegue segundo
     * no hace nada, que es justo lo que hace falta cuando el apagado del módulo y la salida del mundo
     * se solapan.
     */
    private void finish(String reason, boolean warn) {
        if (!travelling) return;
        travelling = false;

        restore();

        String message = "Viaje terminado: " + reason + ". Entorno restaurado.";
        if (warn) warning("%s", message);
        else if (notify.get()) info("%s", message);
    }

    /**
     * Devuelve los cinco puntos que se tocaron a su estado de reposo (spec §6.3). La red se desarma
     * la última, cuando ya no queda ni un comando por emitir: desarmarla antes dejaría el {@code
     * cancel} y la restauración sin cubrir, que es exactamente cuando más comandos se mandan de
     * golpe.
     */
    private void restore() {
        send(BaritoneScript.cancel(activePrefix));
        for (String command : BaritoneScript.restoration(activePrefix, restingSettings())) send(command);

        switchModule(Modules.get().get(ElytraFly.class), elytraFlyResting.get());
        switchModule(Modules.get().get(ElytraReplace.class), elytraReplaceResting.get());

        disarmNet();
        resetTrip();
    }

    private void resetTrip() {
        travelling = false;
        waypoints = List.of();
        index = 0;
        stallWatch.reset();
        fireworkWatch.reset();
    }

    private void armNet() {
        netArmed = true;
        netCaughtWarned = false;
    }

    private void disarmNet() {
        netArmed = false;
    }

    private static void switchModule(Module module, boolean wanted) {
        if (module == null) return;
        if (wanted && !module.isActive()) module.enable();
        else if (!wanted && module.isActive()) module.disable();
    }

    /**
     * Manda un comando por el chat del jugador. Camino verificado contra las fuentes de
     * {@code meteor-client:1.21.11-SNAPSHOT}: {@code ChatUtils.sendPlayerMsg} manda el texto "como si
     * el usuario lo hubiera escrito en el chat", que es exactamente lo que Baritone escucha. Se pasa
     * {@code addToHistory = false} para no llenar el historial del chat de comandos con almohadilla,
     * donde la tecla arriba los dejaría a un intro de publicarse.
     *
     * <p>Sin jugador no se manda nada: {@code sendPlayerMsg} lo dereferencia sin comprobarlo.
     */
    private void send(String command) {
        if (mc.player == null) return;
        ChatUtils.sendPlayerMsg(command, false);
    }

    private Destination destination() {
        if (destinationMode.get() == DestinationMode.AUTOPISTA) {
            return Destination.highway(axis.get(), highwayDistance.get());
        }
        return Destination.coordinates(destinationX.get(), destinationZ.get());
    }

    private PatternParams params() {
        return new PatternParams(amplitude.get(), period.get(), legLength.get(), lateralOffset.get(),
            spiralRadius.get(), spiralTurns.get(), decoyAngle.get(), decoyFraction.get());
    }

    private BaritoneScript.FlightSettings flightSettings() {
        return new BaritoneScript.FlightSettings(autoJump.get(), allowEmergencyLand.get(),
            conserveFireworks.get(), fireworkSpeed.get(), netherSeed.get().strip());
    }

    private BaritoneScript.FlightSettings restingSettings() {
        // La semilla no se restaura: nunca fue un cambio nuestro, solo un dato que se le pasó a
        // Baritone si lo teníamos, y el núcleo no la escribe en la restauración.
        return new BaritoneScript.FlightSettings(autoJumpResting.get(), allowEmergencyLandResting.get(),
            conserveFireworksResting.get(), fireworkSpeedResting.get(), "");
    }

    /** La mitad visual de un aviso fuerte: el toast que acompaña al chat. */
    private void loudToast(String message, Item icon) {
        MeteorToast.Builder toast = new MeteorToast.Builder("Xploits").text(message).icon(icon);
        // MeteorToast.update() llama a play(customSound) sin comprobar el nulo y vanilla lo dereferencia:
        // NPE en el hilo de render. Nunca pasar null; se silencia con volumen cero, igual que ElytraReplace.
        if (!notifySound.get()) {
            toast.sound(PositionedSoundInstance.master(SoundEvents.BLOCK_NOTE_BLOCK_CHIME.value(), 1.2f, 0f));
        }
        mc.getToastManager().add(toast.build());
    }

    public String status() {
        if (!isActive()) return "auto-travel está apagado.";
        if (!travelling) {
            return String.format("auto-travel encendido, sin viaje en marcha. Patrón %s · destino %s.",
                pattern.get(), describeDestination());
        }

        Waypoint target = waypoints.get(index);
        StringBuilder sb = new StringBuilder();
        sb.append("Volando con patrón ").append(pattern.get())
            .append(" · waypoint ").append(index + 1).append(" de ").append(waypoints.size())
            .append(" en ").append(Math.round(target.x())).append(", ").append(Math.round(target.z()));
        if (mc.player != null) {
            Waypoint here = new Waypoint(mc.player.getX(), mc.player.getZ());
            sb.append(" a ").append(Math.round(here.distanceTo(target))).append(" bloques");
            sb.append("\n  quedan ").append(waypoints.size() - index - 1).append(" waypoints después de este");
        }
        sb.append("\n  red de seguridad: ").append(netArmed ? "armada" : "DESARMADA");
        if (netCaughtWarned) sb.append(" y ya ha tenido que cancelar un comando: Baritone no está interceptando");
        return sb.toString();
    }

    private String describeDestination() {
        if (destinationMode.get() == DestinationMode.AUTOPISTA) {
            return String.format("%d bloques por %s", Math.round(highwayDistance.get()), axis.get());
        }
        return String.format("%d, %d", Math.round(destinationX.get()), Math.round(destinationZ.get()));
    }
}
