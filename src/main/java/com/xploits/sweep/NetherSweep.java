package com.xploits.sweep;

import com.xploits.XploitsAddon;
import com.xploits.elytra.ElytraReplace;
import com.xploits.sweep.core.ChunkPos;
import com.xploits.sweep.core.Coverage;
import com.xploits.sweep.core.FuelBudget;
import com.xploits.sweep.core.SweepArea;
import com.xploits.sweep.core.SweepPlanner;
import com.xploits.sweep.core.SweepRoute;
import com.xploits.sweep.core.WidthProbe;
import com.xploits.travel.core.BaritoneScript;
import com.xploits.travel.core.BorrowedModule;
import com.xploits.travel.core.FireworkWatch;
import com.xploits.travel.core.RoutePlanner;
import com.xploits.travel.core.SafetyNet;
import com.xploits.travel.core.StallWatch;
import com.xploits.travel.core.Waypoint;
import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.ChunkDataEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
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
import meteordevelopment.orbit.EventPriority;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.ChatCommandSignedC2SPacket;
import net.minecraft.network.packet.c2s.play.ChatMessageC2SPacket;
import net.minecraft.network.packet.c2s.play.CommandExecutionC2SPacket;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.WorldSavePath;
import net.minecraft.world.World;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalDouble;

/**
 * Adaptador del barrido del Nether (spec Nether Sweep §2, §5.1, §6 y §8): traduce el rectángulo que
 * pide el jugador a pasadas, lee la cobertura que {@code NewerNewChunks} lleva meses acumulando para
 * volar <b>solo lo que falta</b>, y dirige el vuelo con elytra de Baritone por comandos de chat.
 *
 * <p>Toda la geometría y toda la contabilidad viven en {@code sweep.core}, que se prueba sin
 * arrancar el juego. Aquí solo queda lo que necesita a Minecraft: leer la posición y el inventario,
 * leer los ficheros del otro mod, emitir los comandos, vigilar el progreso, encender y apagar
 * módulos, y armar la red de seguridad.
 *
 * <p><b>Este módulo vuela; no detecta nada</b> (spec §2, que es normativa). Las bases las encuentra
 * {@code BaseFinder}, los contenedores {@code stash-finder} y la cobertura la registra
 * {@code NewerNewChunks}, los tres ya instalados y mejores que cualquier cosa que se reimplementara
 * aquí. Lo único que ninguno hace es conseguir que el terreno pase por delante del cliente, y eso es
 * exactamente lo que hace este módulo. Por eso {@link #start()} comprueba que los tres estén
 * encendidos <b>antes</b> de despegar: sin ellos el barrido cubre terreno y no registra nada, que es
 * volar tres horas para nada.
 *
 * <p><b>Encender el módulo no vuela.</b> El barrido lo lanza el jugador con {@code .xploits sweep
 * go}, igual que en {@code auto-travel}: un módulo que despega solo al activarse te manda a horas de
 * casa por un clic. Mientras está encendido y no vuela hace una sola cosa útil, y es importante:
 * alimentar {@link WidthProbe} con los chunks que va recibiendo, para que al lanzar la anchura de
 * pasada salga <b>medida</b> y no inventada (spec §5).
 *
 * <p><b>La red de seguridad vive en {@link ChatNet}, suscrito al bus por su cuenta</b> y no como
 * parte del módulo. Meteor desuscribe el módulo justo <i>antes</i> de {@code onDeactivate()}, que es
 * cuando la restauración emite su ráfaga de comandos con prefijo: una red montada sobre un
 * {@code @EventHandler} del propio módulo estaría muerta justo entonces y las seis líneas saldrían
 * al chat público de un servidor anarchy. Es el mismo arreglo que {@code travel/AutoTravel}, y está
 * razonado entero en su javadoc.
 */
public class NetherSweep extends Module {
    /** El id con el que Baritone se registra en el cargador de mods. */
    private static final String BARITONE_MOD_ID = "baritone";

    /** El nombre con el que {@code NewerNewChunks} se registra como módulo de Meteor. */
    private static final String MODULO_NEWER_NEW_CHUNKS = "NewerNewChunks";

    /** El nombre con el que {@code BaseFinder} se registra como módulo de Meteor. */
    private static final String MODULO_BASE_FINDER = "BaseFinder";

    /** El nombre con el que el buscador de contenedores de Meteor se registra. */
    private static final String MODULO_STASH_FINDER = "stash-finder";

    /**
     * Los cinco ficheros que {@code NewerNewChunks} mantiene por servidor y dimensión (spec §5.1).
     * Su unión es exactamente «qué chunks he recibido», y es el registro de cobertura que este
     * módulo consume en vez de llevar uno propio.
     *
     * <p>Nombres leídos del <b>bytecode del jar instalado</b>, {@code trouser-streak-1.6.1}, clase
     * {@code pwn.noobs.trouserstreak.modules.NewerNewChunks}, y confirmados contra los ficheros que
     * ya existen en disco en esta instancia.
     */
    private static final String[] FICHEROS_DE_COBERTURA = {
        "NewChunkData.txt",
        "OldChunkData.txt",
        "OldGenerationChunkData.txt",
        "BeingUpdatedChunkData.txt",
        "BlockExploitChunkData.txt"
    };

    /**
     * Todo lo que {@code NewerNewChunks} mete en un nombre de carpeta pasa por este reemplazo, así
     * que nosotros tenemos que aplicarle <b>exactamente el mismo</b> o buscaríamos en una carpeta
     * que no existe.
     *
     * <p>Es una lista blanca, no una lista negra, y por eso <b>no</b> se usa aquí el
     * {@code Utils.getFileWorldName()} de Meteor: el suyo es {@code [\s\\/:*?"<>|]}, una lista negra
     * distinta, y para cualquier dirección con un carácter que uno limpie y el otro no -un {@code ~}
     * o un {@code !}, por ejemplo- los dos nombres divergen y la cobertura previa se leería vacía.
     * Vacía significa replanificar el rectángulo entero: horas de vuelo repitiendo terreno ya visto.
     */
    private static final String CARACTERES_INVALIDOS = "[^a-zA-Z0-9._\\-]";

    /**
     * Cada cuántos bloques volados se le pasa una muestra a {@link FuelBudget}, y por qué no es cada
     * tick.
     *
     * <p>{@link FuelBudget#sample} solo cuenta como gasto medido los tramos en los que de verdad
     * bajaron los cohetes. Muestreando cada tick, un cohete se quema en un tick y los otros cien
     * ticks del intervalo no cuentan: la tasa saldría de dividir <b>un tick de vuelo</b> -un par de
     * bloques- entre un cohete, o sea unos 2 bloques por cohete en vez de los cien y pico reales.
     * Con esa tasa {@link FuelBudget#willRunOut} cortaría el barrido a los pocos segundos, siempre.
     *
     * <p>Así que el intervalo tiene que ser lo bastante largo como para que en casi todos se queme
     * algún cohete. Mil bloques son unos treinta segundos de crucero con elytra, dentro de los cuales
     * Baritone quema del orden de diez cohetes: suficiente para que el tramo mida gasto real y corto
     * como para que la primera proyección llegue al minuto de vuelo, que es lo que la spec promete
     * (§6: «la proyección llegará a los pocos minutos de vuelo»).
     */
    private static final double BLOQUES_POR_MUESTRA_DE_COHETES = 1_000;

    /** Sin acercarse al waypoint durante este tiempo, el barrido se corta. */
    private static final double SEGUNDOS_DE_ATASCO = 45;

    /** Cuánto tiene que bajar la distancia para contar como avance, en bloques. */
    private static final double EPSILON_DE_AVANCE = 1.0;

    private final SettingGroup sgArea = settings.getDefaultGroup();
    private final SettingGroup sgPasada = settings.createGroup("Pasada");
    private final SettingGroup sgCohetes = settings.createGroup("Cohetes");
    private final SettingGroup sgVuelo = settings.createGroup("Vuelo");
    private final SettingGroup sgAvisos = settings.createGroup("Avisos");

    // El rectángulo, en chunks del Nether (spec §4: el rectángulo lo da el jugador).

    private final Setting<Integer> chunkX1 = sgArea.add(new IntSetting.Builder()
        .name("chunk-x-1")
        .description("Una esquina del rectángulo, coordenada X en CHUNKS del Nether (la de bloque dividida entre 16).")
        .defaultValue(0)
        .sliderRange(-10_000, 10_000)
        .build()
    );

    private final Setting<Integer> chunkZ1 = sgArea.add(new IntSetting.Builder()
        .name("chunk-z-1")
        .description("Una esquina del rectángulo, coordenada Z en CHUNKS del Nether.")
        .defaultValue(0)
        .sliderRange(-10_000, 10_000)
        .build()
    );

    private final Setting<Integer> chunkX2 = sgArea.add(new IntSetting.Builder()
        .name("chunk-x-2")
        .description("La esquina opuesta, coordenada X en CHUNKS del Nether. El orden da igual: se normaliza.")
        .defaultValue(0)
        .sliderRange(-10_000, 10_000)
        .build()
    );

    private final Setting<Integer> chunkZ2 = sgArea.add(new IntSetting.Builder()
        .name("chunk-z-2")
        .description("La esquina opuesta, coordenada Z en CHUNKS del Nether.")
        .defaultValue(0)
        .sliderRange(-10_000, 10_000)
        .build()
    );

    // La anchura de pasada (spec §5). Los identificadores "lane-width" y "lane-width-margin" son los
    // que nombran los motivos de rechazo de SweepPlanner, a propósito y con un test que los fija
    // allí: si divergieran, el mensaje mandaría al jugador a buscar en la ClickGUI algo que no
    // existe con ese nombre. Se mueven juntos o no se mueven.

    private final Setting<Integer> anchuraDePasada = sgPasada.add(new IntSetting.Builder()
        .name("lane-width")
        .description("Separación entre pasadas, en chunks. Déjalo en 0 y se MIDE del flujo de chunks que manda "
            + "el servidor, que es lo correcto (spec §5). Cualquier otro valor la fija a mano y desactiva la "
            + "medida: solo si sabes el alcance real del servidor, porque pasarse deja franjas sin mirar y el "
            + "barrido las marca como peinadas igual.")
        .defaultValue(0)
        .min(0)
        .sliderRange(0, 64)
        .build()
    );

    private final Setting<Double> margenDeAnchura = sgPasada.add(new DoubleSetting.Builder()
        .name("lane-width-margin")
        .description("Qué fracción del radio medido se descuenta como margen. Volar rápido deja huecos aunque "
            + "la distancia nominal sea correcta, porque los chunks tardan en llegar: 0.2 se queda con el 80 % "
            + "del radio observado. No se usa si lane-width está fijada a mano.")
        .defaultValue(0.2)
        .range(0, 0.9)
        .sliderRange(0, 0.9)
        .decimalPlaces(2)
        .build()
    );

    private final Setting<Double> margenDeWaypoint = sgPasada.add(new DoubleSetting.Builder()
        .name("waypoint-margin")
        .description("Cuántos bloques antes de cada vértice se le cambia el objetivo a Baritone, para que no le "
            + "dé tiempo a aterrizar en él. Un barrido tiene dos vértices por pasada, así que sin esto serían "
            + "decenas de aterrizajes.")
        .defaultValue(RoutePlanner.DEFAULT_WAYPOINT_MARGIN)
        .min(RoutePlanner.MIN_WAYPOINT_MARGIN)
        .sliderRange(RoutePlanner.MIN_WAYPOINT_MARGIN, 500)
        .decimalPlaces(0)
        .build()
    );

    // Cohetes (spec §6): el límite real de un barrido no es el tiempo.

    private final Setting<Double> reservaDeCohetes = sgCohetes.add(new DoubleSetting.Builder()
        .name("firework-reserve")
        .description("Margen sobre los cohetes que la proyección dice que hacen falta. 0.2 corta cuando quede un "
            + "20 % menos de lo necesario, es decir con cohetes todavía en la mano y no al quedarse a cero.")
        .defaultValue(0.2)
        .min(0)
        .sliderRange(0, 1)
        .decimalPlaces(2)
        .build()
    );

    private final Setting<Boolean> contarElRegreso = sgCohetes.add(new BoolSetting.Builder()
        .name("count-return-trip")
        .description("Si el presupuesto de cohetes incluye la vuelta desde el final del barrido hasta donde "
            + "despegaste. Apagarlo no te deja más cohetes: solo deja de contarlos, y te enteras de que no "
            + "llegan cuando ya estás lejos.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Double> bloquesPorCohete = sgCohetes.add(new DoubleSetting.Builder()
        .name("blocks-per-firework")
        .description("El gasto medido en barridos anteriores, que es de donde sale la estimación de ANTES de "
            + "despegar (spec §6). Lo escribe el módulo al terminar cada barrido; en 0 significa que todavía no "
            + "hay ninguna medida, y entonces se dice que no la hay en vez de enseñar un número inventado.")
        .defaultValue(0)
        .min(0)
        .sliderRange(0, 400)
        .decimalPlaces(1)
        .build()
    );

    private final Setting<Double> graciaSinProyeccion = sgCohetes.add(new DoubleSetting.Builder()
        .name("no-projection-grace")
        .description("Cuántos bloques se aguanta volando sin poder proyectar los cohetes antes de cortar. Pasa "
            + "al principio -hasta la primera medida- y si repones cohetes más a menudo de lo que se mide, que "
            + "hace caducar el dato. Seguir volando sin proyección es volar sin la protección de cohetes, así "
            + "que se corta en vez de callarse.")
        .defaultValue(5_000)
        .min(1_000)
        .sliderRange(1_000, 50_000)
        .decimalPlaces(0)
        .build()
    );

    // Vuelo: el prefijo y los cuatro ajustes de Baritone, cada uno con su valor de vuelo y su valor
    // de reposo. Los valores de reposo vienen de fábrica con el default REAL de Baritone porque
    // Baritone PERSISTE sus ajustes a disco: un reposo inventado reconfiguraría para siempre todos
    // los #elytra que el jugador haga a mano. El razonamiento entero, con el origen de cada número,
    // está en BaritoneScript.baritoneDefaults().

    private final Setting<String> prefijo = sgVuelo.add(new StringSetting.Builder()
        .name("baritone-prefix")
        .description("El prefijo con el que Baritone lee sus comandos. Cámbialo solo si lo has cambiado en Baritone.")
        .defaultValue("#")
        .build()
    );

    private final Setting<Boolean> autoSalto = sgVuelo.add(new BoolSetting.Builder()
        .name("auto-jump")
        .description("elytraAutoJump durante el barrido: que Baritone despegue solo.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> autoSaltoEnReposo = sgVuelo.add(new BoolSetting.Builder()
        .name("auto-jump-resting")
        .description("A qué valor se devuelve elytraAutoJump al terminar. Hay que declararlo: los ajustes de "
            + "Baritone se pueden escribir pero no leer. De fábrica Baritone lo trae en false.")
        .defaultValue(BaritoneScript.baritoneDefaults().autoJump())
        .build()
    );

    private final Setting<Boolean> aterrizajeDeUrgencia = sgVuelo.add(new BoolSetting.Builder()
        .name("emergency-land")
        .description("elytraAllowEmergencyLand durante el barrido: que aterrice de urgencia antes que estrellarse.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> aterrizajeDeUrgenciaEnReposo = sgVuelo.add(new BoolSetting.Builder()
        .name("emergency-land-resting")
        .description("A qué valor se devuelve elytraAllowEmergencyLand al terminar. De fábrica Baritone lo trae "
            + "en true.")
        .defaultValue(BaritoneScript.baritoneDefaults().allowEmergencyLand())
        .build()
    );

    private final Setting<Boolean> ahorrarCohetes = sgVuelo.add(new BoolSetting.Builder()
        .name("conserve-fireworks")
        .description("elytraConserveFireworks durante el barrido: gastar menos cohetes a cambio de ir más lento. "
            + "En un barrido de horas suele compensar.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> ahorrarCohetesEnReposo = sgVuelo.add(new BoolSetting.Builder()
        .name("conserve-fireworks-resting")
        .description("A qué valor se devuelve elytraConserveFireworks al terminar. De fábrica Baritone lo trae en "
            + "false: ponlo en true solo si tú lo tenías así, porque Baritone lo guarda en disco y todos tus "
            + "vuelos a mano irían más lentos a partir del primer barrido.")
        .defaultValue(BaritoneScript.baritoneDefaults().conserveFireworks())
        .build()
    );

    private final Setting<Double> velocidadDeCohete = sgVuelo.add(new DoubleSetting.Builder()
        .name("firework-speed")
        .description("elytraFireworkSpeed durante el barrido. Volar más rápido que cuando se midió la anchura de "
            + "pasada abre huecos: para eso está lane-width-margin.")
        .defaultValue(1)
        .min(0)
        .sliderRange(0.5, 3)
        .decimalPlaces(2)
        .build()
    );

    private final Setting<Double> velocidadDeCoheteEnReposo = sgVuelo.add(new DoubleSetting.Builder()
        .name("firework-speed-resting")
        .description("A qué valor se devuelve elytraFireworkSpeed al terminar. De fábrica Baritone lo trae en 1.2.")
        .defaultValue(BaritoneScript.baritoneDefaults().fireworkSpeed())
        .min(0)
        .sliderRange(0.5, 3)
        .decimalPlaces(2)
        .build()
    );

    private final Setting<String> semillaDelNether = sgVuelo.add(new StringSetting.Builder()
        .name("nether-seed")
        .description("La semilla del Nether, si se conoce. Vacía se deja en paz: sin semilla Baritone apaga la "
            + "predicción de terreno él solo, que es lo correcto.")
        .defaultValue("")
        .build()
    );

    // Avisos

    private final Setting<Boolean> avisos = sgAvisos.add(new BoolSetting.Builder()
        .name("notify")
        .description("Aviso local al lanzar, al cambiar de pasada y al terminar. Los avisos fuertes -la red de "
            + "seguridad, el atasco, los cohetes y los detectores apagados- salen siempre, lo apagues o no.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> sonidoEnAvisos = sgAvisos.add(new BoolSetting.Builder()
        .name("notify-sound")
        .description("Sonido en los avisos fuertes.")
        .defaultValue(true)
        .build()
    );

    /** Si hay un barrido en marcha: lo único que distingue "encendido" de "dirigiendo". */
    private boolean sweeping;

    /**
     * Si lo que está pasando ahora mismo es el desmontaje de salida del mundo, en el que no se puede
     * encender ni apagar un módulo de Meteor sin dejarlo suscrito dos veces al bus para el resto de
     * la sesión. Lo pone {@link #onGameLeft(GameLeftEvent)} y lo quita {@link #onActivate()}.
     */
    private boolean leavingWorld;

    /**
     * El oyente de la red de seguridad, <b>suscrito al bus por su cuenta</b> y no como parte del
     * módulo, porque Meteor desuscribe el módulo justo antes de {@code onDeactivate()} -que es
     * cuando la restauración emite sus comandos-. El razonamiento entero, con las fuentes de orbit
     * comprobadas, está en {@code travel/AutoTravel}.
     */
    private final ChatNet net = new ChatNet();

    /** Si la red está armada, que es lo mismo que decir si {@link #net} está suscrito al bus. */
    private boolean netArmed;

    /** Si el comando que {@link #send(String)} está emitiendo ahora mismo es nuestro. */
    private boolean emitting;

    /** Si la red mató el último comando nuestro: puesto por el oyente, leído por {@link #send(String)}. */
    private boolean sendCaught;

    /** Si ya se avisó de que la red ha tenido que cancelar algo en este barrido (una sola vez). */
    private boolean netCaughtWarned;

    /**
     * El prefijo con el que arrancó este barrido. La red y la restauración usan este, no el del
     * ajuste: si el jugador lo edita a mitad de vuelo, la restauración tiene que hablarle a Baritone
     * en el mismo idioma en que se le habló al despegar.
     */
    private String activePrefix = "";

    /**
     * Los dos módulos de Meteor que el barrido toma prestados. La decisión de qué hacerles al
     * despegar y al terminar está en {@link BorrowedModule}, en el núcleo y con tests.
     */
    private final BorrowedModule elytraFly = new BorrowedModule("elytra-fly", false);
    private final BorrowedModule elytraReplace = new BorrowedModule("elytra-replace", true);

    /**
     * El viaje que se está volando: los vértices en orden y todas las distancias que hay que
     * presupuestar, aproximación y regreso incluidos. Es {@code null} mientras no hay barrido en
     * marcha, y todo lo que lo lee está detrás de {@link #sweeping}.
     */
    private SweepRoute route;

    /** En qué vértice de {@link #route} va el barrido. */
    private int index;

    /** Cuántas pasadas tiene el plan que se está volando, para el estado y los avisos. */
    private int pasadasDelPlan;

    /** La anchura de pasada con la que se planificó, en chunks. */
    private int anchuraUsada;

    /** Si esa anchura la tecleó el jugador en vez de salir medida del flujo de chunks. */
    private boolean anchuraTecleada;

    /**
     * La anchura a la que el servidor manda chunks, medida del propio flujo (spec §5). Se alimenta
     * <b>con el módulo encendido aunque no se esté volando</b>: así al lanzar ya hay muestras y la
     * anchura de pasada sale medida en vez de tecleada. Se tira al entrar o salir de un mundo,
     * porque el alcance del servidor siguiente no tiene por qué ser el de éste.
     */
    private WidthProbe probe = new WidthProbe();

    /**
     * El techo con el que se tomó la última muestra, en chunks, o 0 si todavía no se ha tomado
     * ninguna. Sirve para notar que la distancia de renderizado efectiva ha cambiado y tirar una
     * medida que ya no se puede comparar con la nueva.
     */
    private int ultimoTecho;

    /** La medición del gasto de cohetes de <b>este</b> vuelo (spec §6). */
    private FuelBudget fuel = new FuelBudget();

    /** Bloques recorridos de verdad desde el despegue, que es lo que se le pasa a {@link FuelBudget}. */
    private double bloquesVolados;

    /** Dónde estaba el jugador en el tick anterior, para medir el desplazamiento real. */
    private Waypoint posicionAnterior = new Waypoint(0, 0);

    /** En qué kilometraje se tomó la última muestra de cohetes. */
    private double bloquesDeLaUltimaMuestra;

    /** Bloques volados sin que {@link FuelBudget#blocksPerRocket()} haya podido dar una tasa. */
    private double bloquesSinProyeccion;

    /** Si ya se avisó una vez de que no hay proyección de cohetes, para no repetirlo por muestra. */
    private boolean avisoSinProyeccionDado;

    /** Cuántos chunks del área ya estaban vistos al planificar, para el estado. */
    private int chunksYaVistos;

    /**
     * La vigilancia del atasco, en el núcleo y con tests. El adaptador solo le da el waypoint al que
     * va y la distancia que queda.
     */
    private final StallWatch stallWatch = StallWatch.ofSeconds(SEGUNDOS_DE_ATASCO, EPSILON_DE_AVANCE);

    /**
     * El aviso de cohetes bajos. El umbral no es un ajuste aquí: en un barrido la protección buena
     * es la proyección de {@link FuelBudget}, y este aviso es solo el recordatorio de que se están
     * acabando. Se arma al despegar con el 10 % de los cohetes de partida, que es un número relativo
     * a lo que el jugador haya decidido cargar en vez de uno fijo que no significa lo mismo con 64
     * cohetes que con 1.500.
     */
    private FireworkWatch fireworkWatch = new FireworkWatch(0);

    public NetherSweep() {
        super(XploitsAddon.CATEGORY, "nether-sweep",
            "Barre un rectángulo del Nether con pasadas de cortacésped para que el terreno pase por delante del "
                + "cliente, volando solo lo que NewerNewChunks todavía no ha registrado. No detecta nada: de eso "
                + "se encargan BaseFinder, stash-finder y NewerNewChunks. Encenderlo no vuela: el barrido se "
                + "lanza con .xploits sweep go.");
    }

    @Override
    public void onActivate() {
        // Encender no vuela: se espera al comando del jugador. Aquí se vuelve también al entrar al
        // mundo -Modules.onGameJoined suscribe cada módulo activo y le llama a onActivate()-, así
        // que es el sitio donde se olvida que estábamos saliendo y donde se tira la medida de
        // anchura del servidor anterior.
        leavingWorld = false;
        probe = new WidthProbe();
        ultimoTecho = 0;
        resetSweep();

        info("Armado, pero no vuela solo: lanza el barrido con .xploits sweep go");
        info("Mientras tanto voy midiendo hasta dónde manda chunks el servidor, que es de donde sale la anchura de pasada.");
    }

    @Override
    public void onDeactivate() {
        // Al dejar el mundo también se pasa por aquí, pero para entonces onGameLeft ya ha cerrado el
        // barrido: corre antes por prioridad y finish() es idempotente.
        finish("nether-sweep se ha apagado", false);
        // Y pase lo que pase, la red no sobrevive al módulo: un oyente suscrito sin barrido en marcha
        // se comería en silencio todo comando con prefijo que el jugador escribiera a mano.
        disarmNet();
    }

    /**
     * Salida: desconexión o cambio de mundo. Va en {@code HIGHEST} a propósito para correr antes que
     * el handler de {@code Modules}, que desmonta los módulos activos: así la restauración sabe que
     * está en el desmontaje y no enciende ni apaga ningún módulo de Meteor -hacerlo ahí los deja
     * suscritos dos veces al bus para el resto de la sesión-.
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    private void onGameLeft(GameLeftEvent event) {
        leavingWorld = true;
        finish("se ha dejado el mundo", false);
        probe = new WidthProbe();
        ultimoTecho = 0;
    }

    /**
     * La medida de la anchura real a la que el servidor manda chunks (spec §5). Llega <b>un evento
     * por chunk que el servidor envía</b>, publicado desde {@code ClientPlayNetworkHandlerMixin} con
     * {@code @At("TAIL")} de {@code onChunkData}, que corre en el hilo principal detrás de
     * {@code forceMainThread}: es la medida honesta de cobertura y se puede tocar el estado del
     * módulo desde aquí sin carreras.
     *
     * <p>Se alimenta siempre que el módulo esté encendido, se esté volando o no, porque la anchura
     * tiene que estar medida <b>antes</b> de lanzar. La distancia de Chebyshev, el máximo sobre la
     * media y el descarte de los artefactos los decide {@link WidthProbe}, en el núcleo y con tests;
     * aquí solo se lee la posición, la del chunk y el techo.
     *
     * <p><b>El techo es lo único que impide que un chunk tardío ensanche las pasadas el resto de la
     * sesión</b> (ver el javadoc de {@link WidthProbe}). Sale de {@code getClampedViewDistance()},
     * cuyo bytecode dice literalmente {@code serverViewDistance > 0 ? min(viewDistance,
     * serverViewDistance) : viewDistance}: es exactamente «el menor de los dos» de spec §3 -lo que
     * el jugador tiene puesto y lo que el servidor ha declarado-, que es el límite por encima del
     * cual una muestra no puede venir del servidor.
     */
    @EventHandler
    private void onChunkData(ChunkDataEvent event) {
        if (mc.player == null || event.chunk() == null) return;

        int techo = mc.options.getClampedViewDistance();
        // Un techo por debajo de 1 no es un estado real del juego -la distancia de renderizado
        // mínima es 2-, pero si alguna vez lo fuera, muestrear con él lanzaría una excepción por
        // cada chunk recibido. Aquí se calla y no se mide, que es el lado seguro: sin muestras el
        // módulo se niega a despegar en vez de volar con una anchura inventada.
        if (techo < 1) return;

        if (techo != ultimoTecho) {
            // La distancia de renderizado ha cambiado -la ha tocado el jugador, o el servidor ha
            // declarado otra-. Las muestras viejas se tomaron contra un techo que ya no vale, y si
            // el techo ha BAJADO el máximo guardado puede estar por encima de lo que el servidor
            // manda ahora: exactamente el hueco que el techo existe para cerrar, entrando por la
            // otra puerta. Se tira la medida y se vuelve a medir.
            if (ultimoTecho > 0 && probe.sampleCount() > 0) {
                info("La distancia de renderizado efectiva ha pasado de %d a %d chunks: tiro la medida de la "
                    + "anchura de pasada y la vuelvo a tomar.", ultimoTecho, techo);
            }
            ultimoTecho = techo;
            probe = new WidthProbe();
        }

        int jugadorX = (int) Math.floor(mc.player.getX()) >> 4;
        int jugadorZ = (int) Math.floor(mc.player.getZ()) >> 4;
        probe.sample(
            new ChunkPos(jugadorX, jugadorZ),
            new ChunkPos(event.chunk().getPos().x, event.chunk().getPos().z),
            techo);
    }

    /**
     * La mitad de la red de seguridad que necesita a Minecraft: sacar del paquete saliente por qué
     * canal va y qué texto lleva. Decidir si ese texto es un comando de Baritone de los que
     * dirigimos es de {@link SafetyNet}, que se prueba sin arrancar el juego.
     */
    private final class ChatNet {
        @EventHandler
        private void onPacketSend(PacketEvent.Send event) {
            if (!netArmed) return;

            SafetyNet.Channel channel;
            String payload;
            if (event.packet instanceof ChatMessageC2SPacket chat) {
                channel = SafetyNet.Channel.CHAT;
                payload = chat.chatMessage();
            }
            else if (event.packet instanceof CommandExecutionC2SPacket command) {
                channel = SafetyNet.Channel.COMANDO;
                payload = command.command();
            }
            else if (event.packet instanceof ChatCommandSignedC2SPacket command) {
                channel = SafetyNet.Channel.COMANDO;
                payload = command.command();
            }
            else return;

            if (!SafetyNet.directs(activePrefix, channel, payload)) return;

            event.cancel();
            if (emitting) sendCaught = true;
            warnNetCaught(SafetyNet.typedText(channel, payload));
        }
    }

    /**
     * Que la red haya tenido que actuar significa que Baritone no está interceptando, y eso el
     * jugador lo quiere saber: cualquier comando que escriba a mano sí se publicaría. Una sola vez
     * por barrido, y fuerte.
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
        // Lo que quedó por devolver al salir del mundo se devuelve aquí, en el primer tick tras
        // volver a entrar: para entonces Modules.onGameJoined ya ha terminado de suscribir a todos.
        applyPendingModules();

        if (!sweeping) return;

        if (mc.player == null || mc.world == null) {
            finish("se ha perdido el mundo", true);
            return;
        }
        if (!mc.player.isAlive()) {
            // No hay evento de muerte en Meteor, así que se observa aquí; el jugador sigue existiendo
            // en la pantalla de muerte, así que la restauración todavía puede hablarle a Baritone.
            finish("has muerto a mitad del barrido", true);
            return;
        }

        Waypoint aqui = new Waypoint(mc.player.getX(), mc.player.getZ());
        bloquesVolados += aqui.distanceTo(posicionAnterior);
        posicionAnterior = aqui;

        checkFireworks();
        if (muestrearCohetes()) return;

        double distancia = aqui.distanceTo(route.waypoints().get(index));

        // El margen con el que se da por alcanzado un waypoint no es el mismo para todos -el último
        // es el único sitio donde Baritone debe aterrizar-, y esa decisión vive en el núcleo.
        if (distancia <= RoutePlanner.reachedMargin(index, route.size(), margenDeWaypoint.get())) {
            index++;
            if (index >= route.size()) {
                finish("el barrido ha terminado", false);
                return;
            }
            if (avisos.get() && index % 2 == 0) {
                info("Pasada %d de %d.", index / 2 + 1, pasadasDelPlan);
            }
            aimAtCurrentWaypoint();
            return;
        }

        // Lo único observable desde fuera es si la distancia baja; Baritone no informa de nada más.
        // El índice va en la llamada a propósito: es lo que hace que el salto de distancia al cambiar
        // de waypoint no se lea como cuarenta y cinco segundos sin avanzar.
        if (stallWatch.tick(index, distancia)) {
            String message = String.format("Sin acercarme al vértice %d en %d s, a %d bloques: corto y restauro.",
                index + 1, stallWatch.limitSeconds(), Math.round(distancia));
            warning("%s", message);
            loudToast(message, Items.ELYTRA);
            finish("atasco", false);
        }
    }

    /**
     * El recordatorio de cohetes bajos. La protección de verdad es {@link #muestrearCohetes()}; esto
     * solo avisa de que se están acabando, y {@link FireworkWatch} decide cuándo y si ya salió.
     *
     * <p>{@code InvUtils.find(Item...)} recorre el inventario del jugador y suma los {@code count}
     * de las pilas que casen: cubre la barra rápida, el inventario principal, la armadura y la mano
     * secundaria, y devuelve {@code count 0} sin jugador en vez de reventar. Lo que vaya dentro de un
     * shulker no se cuenta.
     */
    private void checkFireworks() {
        int cohetes = InvUtils.find(Items.FIREWORK_ROCKET).count();
        if (!fireworkWatch.observe(cohetes)) return;

        String message = cohetes == 0
            ? "Te has quedado SIN cohetes a mitad del barrido: Baritone no puede seguir impulsándose."
            : String.format("Te quedan %d cohetes, el aviso está puesto en %d: repón o corta el barrido.",
                cohetes, fireworkWatch.threshold());
        warning("%s", message);
        loudToast(message, Items.FIREWORK_ROCKET);
    }

    /**
     * La medición del gasto de cohetes y la proyección de si van a llegar (spec §6, segunda mitad:
     * <i>«Durante: se mide el gasto real por bloque recorrido y se proyecta. Si la proyección no
     * llega, corta y lo dice antes de dejarle tirado»</i>).
     *
     * <p><b>La rama que importa es la de "no lo sé", y no puede ser un silencio.</b>
     * {@link FuelBudget#blocksPerRocket()} está vacío en dos situaciones normales: al principio,
     * hasta que dos muestras seguidas midan gasto real, y a mitad de vuelo, si el jugador repone
     * cohetes más a menudo de lo que se muestrea y la tasa medida caduca. En las dos,
     * {@link FuelBudget#willRunOut} <b>lanza</b> a propósito, porque fabricar una medida que no
     * existe es peor que admitirlo. Lo que este método <b>no</b> hace es capturar esa excepción y
     * seguir volando: eso haría desaparecer la protección de cohetes en silencio, que es justo la
     * clase de fallo que el módulo existe para no cometer. Así que ni se llama a {@code willRunOut}
     * sin tasa, ni se sigue indefinidamente sin ella: se avisa, y pasada la gracia se corta.
     *
     * @return si el barrido se ha cortado y quien llame tiene que dejar de tocar su estado
     */
    private boolean muestrearCohetes() {
        double desdeLaUltima = bloquesVolados - bloquesDeLaUltimaMuestra;
        if (desdeLaUltima < BLOQUES_POR_MUESTRA_DE_COHETES) return false;
        bloquesDeLaUltimaMuestra = bloquesVolados;

        int cohetes = InvUtils.find(Items.FIREWORK_ROCKET).count();
        fuel.sample(bloquesVolados, cohetes);

        OptionalDouble tasa = fuel.blocksPerRocket();
        if (tasa.isEmpty()) {
            bloquesSinProyeccion += desdeLaUltima;
            if (bloquesSinProyeccion < graciaSinProyeccion.get()) {
                if (!avisoSinProyeccionDado && bloquesSinProyeccion >= graciaSinProyeccion.get() / 2) {
                    avisoSinProyeccionDado = true;
                    warning("%s", String.format("Llevo %d bloques sin poder medir el gasto de cohetes: o todavía no "
                            + "ha bajado ninguno, o repones tan a menudo que la medida ha caducado. Vuelo SIN "
                            + "proyección de cohetes; si sigo así %d bloques más, corto.",
                        Math.round(bloquesSinProyeccion),
                        Math.round(graciaSinProyeccion.get() - bloquesSinProyeccion)));
                }
                return false;
            }

            String message = String.format("Llevo %d bloques sin poder proyectar los cohetes y no-projection-grace "
                    + "está en %d: corto el barrido. Seguir sería volar sin la única protección que tienes contra "
                    + "quedarte tirado lejos de casa, y callármelo sería peor que pararlo.",
                Math.round(bloquesSinProyeccion), Math.round(graciaSinProyeccion.get()));
            warning("%s", message);
            loudToast(message, Items.FIREWORK_ROCKET);
            finish("no puedo proyectar los cohetes", false);
            return true;
        }

        bloquesSinProyeccion = 0;
        avisoSinProyeccionDado = false;

        double restante = bloquesRestantes();
        if (!fuel.willRunOut(restante, cohetes, reservaDeCohetes.get())) return false;

        long necesarios = (long) Math.ceil(restante / tasa.getAsDouble() * (1 + reservaDeCohetes.get()));
        String message = String.format("Los cohetes NO llegan: al ritmo medido de %d bloques por cohete te quedan "
                + "%d bloques por delante%s, que con la reserva son unos %d cohetes, y llevas %d. Corto el barrido "
                + "aquí en vez de dejarte tirado más lejos.",
            Math.round(tasa.getAsDouble()), Math.round(restante),
            contarElRegreso.get() ? " contando el regreso" : " SIN contar el regreso", necesarios, cohetes);
        warning("%s", message);
        loudToast(message, Items.FIREWORK_ROCKET);
        finish("los cohetes no llegan", false);
        return true;
    }

    /**
     * Lo que le queda por volar al jugador hasta terminar: de donde está al vértice al que va, más el
     * resto de la ruta, más el regreso si se cuenta. Es exactamente la distancia que
     * {@link FuelBudget#willRunOut} pide, y no {@code SweepPlan.totalBlocks()}, que mide solo el
     * barrido.
     */
    private double bloquesRestantes() {
        if (mc.player == null) return route.remainingFrom(index);
        return route.remainingFrom(index, new Waypoint(mc.player.getX(), mc.player.getZ()));
    }

    /**
     * Lanza el barrido. Devuelve el mensaje que el comando tiene que enseñar, sea el del lanzamiento
     * o el motivo por el que no se vuela; ante la duda, no se manda un solo comando.
     *
     * <p>Los avisos de §8 -detectores apagados, anchura tecleada- salen por el chat <b>antes</b> del
     * despegue y no en el valor de vuelta, porque son varios y cada uno merece su línea.
     */
    public String start() {
        if (!isActive()) return "nether-sweep está apagado: enciéndelo antes de lanzar un barrido.";
        if (sweeping) return "Ya hay un barrido en marcha: córtalo antes de lanzar otro.";
        if (mc.player == null || mc.world == null) return "No hay mundo cargado: no se lanza nada.";
        if (!mc.player.isAlive()) {
            return "Estás muerto: reaparece antes de lanzar un barrido, que desde la pantalla de muerte no se vuela.";
        }
        if (!World.NETHER.equals(mc.world.getRegistryKey())) {
            // El módulo entero está construido sobre que un bloque del Nether cubre 64 veces más
            // superficie del Overworld: el área se teclea en chunks del Nether y lo que se anuncia
            // como cobertura equivalente sale de multiplicar por 8. Volarlo en otra dimensión no
            // rompe la geometría, pero convierte ese anuncio en mentira, y es el número por el que
            // el jugador decide si el barrido vale las horas que cuesta.
            return "No estás en el Nether: este módulo barre el Nether porque ahí cada bloque volado cubre 64 "
                + "veces más superficie del Overworld, y todo lo que anuncia -el área equivalente, el porqué de "
                + "volar esto- se apoya en esa cuenta. Cruza un portal y vuelve a lanzarlo.";
        }
        if (!FabricLoader.getInstance().isModLoaded(BARITONE_MOD_ID)) {
            // A propósito NO se usa BaritoneUtils.IS_AVAILABLE: Meteor lo pone a true tras un
            // Class.forName sobre una clase que el jar ofuscado no expone, así que ahí vale false
            // aunque Baritone esté perfectamente instalado.
            return "Baritone no está cargado: este módulo vuela con sus comandos y sin él no hay nada que dirigir.";
        }
        if (InvUtils.find(Items.FIREWORK_ROCKET).count() == 0) {
            return "No llevas ningún cohete: Baritone se impulsa con ellos y sin ninguno no despega. No se lanza "
                + "nada. Los que vayan dentro de shulkers no cuentan: sácalos antes.";
        }
        if (!wearsElytra()) {
            return "No llevas elytra puesta: Baritone vuela con ella, y elytra-replace cambia la que lleves pero "
                + "no te pone ninguna. Ponte una antes de lanzar.";
        }
        String chestSwapRejection = chestSwapRejection();
        if (chestSwapRejection != null) return chestSwapRejection;

        String launchPrefix = prefijo.get();
        String prefixRejection = SafetyNet.prefixRejection(launchPrefix);
        if (prefixRejection != null) {
            // Antes de armar la red y antes del primer comando: armarla sobre un prefijo inservible
            // es tener red sin saber qué vigila.
            return "No se barre: " + prefixRejection + ".";
        }

        SweepArea area = SweepArea.ofChunks(chunkX1.get(), chunkZ1.get(), chunkX2.get(), chunkZ2.get());

        // El tamaño se comprueba aquí, ANTES de leer la cobertura y antes de planificar, porque los
        // dos recorren el rectángulo entero chunk a chunk en el hilo principal -Coverage.seenIn para
        // contar lo ya visto y SweepPlanner para decidir qué bandas saltarse-. Con un área tecleada
        // de más, el cliente se queda colgado dentro de un comando y ni siquiera llega el rechazo.
        // El motivo va como argumento de un "%s", como todos.
        String tamanoRechazo = area.oversizeRejection();
        if (tamanoRechazo != null) return String.format("No se barre: %s", tamanoRechazo);

        String anchuraRechazo = resolverAnchura();
        if (anchuraRechazo != null) return anchuraRechazo;

        LecturaDeCobertura lectura = leerCobertura();
        Coverage vista = lectura.cobertura();
        // Del área, no de la dimensión entera: lo que le dice al jugador cuánto le ahorra su
        // cobertura previa es lo que cae DENTRO del rectángulo que ha pedido. Con size() el mensaje
        // llegaba a anunciar más chunks vistos que chunks tiene el área.
        chunksYaVistos = vista.seenIn(area);

        SweepPlanner.SweepPlan plan = SweepPlanner.plan(area, vista, anchuraUsada);
        // El motivo va como ARGUMENTO de un "%s" y nunca como cadena de formato: estos textos llevan
        // porcentajes y acaban en String.format por el camino del comando.
        if (plan.isRejected()) return String.format("No se barre: %s", plan.rejection());
        if (plan.lanes().isEmpty()) {
            return String.format("No hay nada que barrer: los %d chunks del área ya están vistos enteros según "
                    + "NewerNewChunks (%s). %s",
                area.chunkCount(), lectura.resumen(), area.overworldEquivalent());
        }

        Waypoint aqui = new Waypoint(mc.player.getX(), mc.player.getZ());
        // Todas las distancias del viaje -aproximación incluida- las calcula SweepRoute, en el núcleo
        // y con tests: aquí no se rehace ninguna a mano, que es por donde se coló dos veces un
        // "total" que no incluía la aproximación y que acabó decidiendo si había cohetes.
        SweepRoute ruta = SweepRoute.of(plan.lanes(), aqui, contarElRegreso.get());

        String separacionRechazo = rechazoPorSeparacion(ruta);
        if (separacionRechazo != null) return separacionRechazo;

        avisarDeLosDetectores();
        if (lectura.servidorDesconocido()) {
            // No es "no hay nada registrado": es "ni he mirado". El plan sale igual que si empezara
            // de cero, así que sin decirlo el jugador vuela tres horas repitiendo terreno que lleva
            // meses acumulando sin enterarse de que su cobertura previa no ha entrado en la cuenta.
            String message = "No he podido saber en qué servidor estás, así que NO he leído la cobertura de "
                + "NewerNewChunks: esto NO es que no haya nada registrado, es que ni he mirado. Voy a planificar "
                + "el rectángulo entero, así que si ya habías visto parte de él lo vas a repetir.";
            warning("%s", message);
            loudToast(message, Items.BARRIER);
        }
        if (anchuraTecleada) {
            String message = String.format("lane-width está tecleada a mano en %d chunks, así que el barrido NO "
                    + "mide la anchura de pasada: si el servidor manda menos que eso, quedarán franjas sin ver y "
                    + "este barrido las dará por peinadas igual. Pon lane-width en 0 para que se mida sola.",
                anchuraUsada);
            warning("%s", message);
            loudToast(message, Items.BARRIER);
        }

        activePrefix = launchPrefix;
        route = ruta;
        posicionAnterior = aqui;
        index = 0;
        pasadasDelPlan = plan.lanes().size();
        bloquesVolados = 0;
        bloquesDeLaUltimaMuestra = 0;
        bloquesSinProyeccion = 0;
        avisoSinProyeccionDado = false;
        fuel = new FuelBudget();
        stallWatch.reset();
        fireworkWatch = new FireworkWatch(umbralDeAviso());
        sweeping = true;

        // El orden importa: la red ANTES de emitir el primer comando.
        armNet();
        prepare();

        // Y una última comprobación antes del #elytra, porque preparar mueve armadura: apagar
        // elytra-fly con chest-swap en Always te pone la pechera en el sitio de la elytra. Eso se
        // rechaza arriba, así que aquí ya no debería poder pasar; esto es la red por si otro módulo
        // se lleva la elytra entre una línea y la siguiente.
        if (!wearsElytra()) return undoLaunch();

        aimAtCurrentWaypoint();

        return String.format("Barrido lanzado: %d pasadas de %d chunks de anchura (%s) sobre %d chunks del área, "
                + "de los que %d ya estaban vistos (%s).\n  Vuelo: %d bloques de aproximación + %d de barrido%s "
                + "= %d bloques.\n  Cobertura equivalente: %s.\n  %s",
            plan.lanes().size(), anchuraUsada, anchuraTecleada ? "tecleada" : "medida", area.chunkCount(),
            chunksYaVistos, lectura.resumen(), Math.round(ruta.approachBlocks()),
            Math.round(ruta.sweepBlocks()),
            contarElRegreso.get() ? String.format(" + %d de regreso", Math.round(ruta.returnBlocks())) : "",
            Math.round(ruta.totalBlocks()), area.overworldEquivalent(),
            estimacionDeCohetes(ruta.totalBlocks()));
    }

    /**
     * Resuelve con qué anchura de pasada se planifica y la deja en {@link #anchuraUsada}, o devuelve
     * el motivo por el que todavía no se puede barrer.
     *
     * <p>La medida manda (spec §5 y §9: <i>«ningún número que se pueda medir se supone»</i>). Si el
     * jugador la ha tecleado, se respeta pero se avisa fuerte al lanzar; si no la ha tecleado y la
     * sonda todavía no tiene muestras, <b>no se vuela</b>: inventarse la separación es exactamente la
     * forma de acabar con franjas sin mirar creyendo que la zona está limpia.
     */
    private String resolverAnchura() {
        if (anchuraDePasada.get() > 0) {
            anchuraUsada = anchuraDePasada.get();
            anchuraTecleada = true;
            return null;
        }
        if (!probe.hasEnoughSamples()) {
            return String.format("No se barre todavía: la anchura de pasada sale medida del flujo de chunks que "
                    + "manda el servidor, y solo llevo %d de las %d muestras que hacen falta%s. Deja el módulo "
                    + "encendido y muévete un poco para que el servidor te mande terreno, o si sabes su alcance "
                    + "real ponlo a mano en lane-width -con el aviso de que ahí ya no se mide nada-.",
                probe.sampleCount(), WidthProbe.MUESTRAS_MINIMAS, descartadas());
        }
        anchuraUsada = probe.laneWidthInChunks(margenDeAnchura.get());
        anchuraTecleada = false;
        return null;
    }

    /**
     * El motivo por el que la ruta no se puede volar con el margen de waypoint configurado, o
     * {@code null} si se puede.
     *
     * <p>Dos vértices separados por menos del doble del margen se consumen en el mismo tick: cuando
     * el adaptador suelta el primero ya está a un margen de él, y el segundo cae dentro del otro
     * margen. En {@code auto-travel} eso recorta el patrón; aquí es peor, porque los vértices que se
     * consumen en ráfaga <b>son pasadas enteras que nunca se vuelan</b> y que el barrido da por
     * peinadas igual. Así que se rechaza con el número que hay que tocar, en vez de volar un barrido
     * con agujeros.
     *
     * <p>Cuál es el hueco más corto lo sabe {@link SweepRoute#tightestGap()}, en el núcleo y con
     * tests; cuál es el mínimo admisible, {@link RoutePlanner#minimumSpacing(double)}, también. Aquí
     * solo se comparan y se redacta el motivo.
     */
    private String rechazoPorSeparacion(SweepRoute ruta) {
        double minima = RoutePlanner.minimumSpacing(margenDeWaypoint.get());
        double separacion = ruta.tightestGap();
        if (separacion > minima) return null;

        return String.format("No se barre: hay dos vértices del recorrido a %d bloques, y con waypoint-margin en "
                + "%d hacen falta más de %d. Tan juntos se consumirían en el mismo tick, así que habría pasadas "
                + "que no se volarían nunca y el barrido las daría por peinadas igual. Baja waypoint-margin, "
                + "agranda el área, o sube lane-width si la tienes tecleada.",
            Math.round(separacion), Math.round(margenDeWaypoint.get()), Math.round(minima));
    }

    /**
     * La estimación de cohetes de antes de despegar (spec §6). Sale del gasto medido en barridos
     * anteriores, que el módulo guarda entre sesiones en su propio ajuste; <b>sin ningún barrido
     * previo se dice que no hay dato</b> en vez de enseñar un número inventado con aspecto de medida.
     *
     * <p>La distancia que se le pasa es el viaje entero -aproximación, barrido y regreso-, no
     * {@code SweepPlan.totalBlocks()}: ese mide del arranque de la primera pasada al final de la
     * última, y en un barrido lejos de casa la aproximación es la pata más larga de todas.
     */
    private String estimacionDeCohetes(double bloquesTotales) {
        int llevas = InvUtils.find(Items.FIREWORK_ROCKET).count();
        double tasa = bloquesPorCohete.get();
        if (tasa <= 0) {
            return String.format("Cohetes estimados: no hay dato. Nunca he medido tu gasto por bloque, y "
                    + "enseñarte un número inventado con aspecto de medida sería peor que decírtelo. Llevas %d "
                    + "cohetes; la proyección real llega al minuto de vuelo, cuando la medición tenga con qué.",
                llevas);
        }
        long necesarios = (long) Math.ceil(bloquesTotales / tasa * (1 + reservaDeCohetes.get()));
        if (necesarios <= llevas) {
            return String.format("Cohetes estimados: unos %d con la reserva, a %d bloques por cohete medidos en "
                + "barridos anteriores. Llevas %d, que llegan.", necesarios, Math.round(tasa), llevas);
        }
        return String.format("Cohetes estimados: unos %d con la reserva, a %d bloques por cohete medidos en "
                + "barridos anteriores, y llevas %d: TE FALTAN unos %d. Despego igual porque la estimación es "
                + "vieja y la medición de este vuelo manda sobre ella, pero cortaré en cuanto la proyección real "
                + "diga que no llegan.", necesarios, Math.round(tasa), llevas, necesarios - llevas);
    }

    /**
     * El umbral del aviso de cohetes bajos: el 10 % de los que se lleven al despegar, y nunca menos
     * de uno. Relativo y no fijo porque «te quedan 16» no significa lo mismo saliendo con 64 que
     * saliendo con 1.500.
     */
    private int umbralDeAviso() {
        return Math.max(1, InvUtils.find(Items.FIREWORK_ROCKET).count() / 10);
    }

    /**
     * Los avisos de spec §5.1 y §8, los que hay que dar <b>antes</b> de despegar y no después de tres
     * horas: este módulo vuela y no detecta nada. Con los detectores apagados el barrido cubre
     * terreno y no registra nada, y con {@code NewerNewChunks} apagado además ni se aprovecha lo
     * anterior ni queda rastro para la próxima vez.
     *
     * <p>Los tres módulos se buscan <b>por nombre</b> y no por clase a propósito: dos de ellos son de
     * otro mod, {@code trouser-streak}, que no es dependencia de compilación de este addon. Buscar
     * por nombre significa que si el jugador lo desinstala, esto dice «no está instalado» en vez de
     * reventar con un {@code NoClassDefFoundError} al cargar el módulo.
     */
    private void avisarDeLosDetectores() {
        avisarDeUnDetector(MODULO_NEWER_NEW_CHUNKS,
            "sin él ni aprovecho la cobertura que ya tienes -replanifico el área entera y repites terreno ya "
                + "visto- ni queda rastro de este barrido para la próxima vez");
        avisarDeUnDetector(MODULO_BASE_FINDER,
            "sin él este barrido cubre terreno y no registra ninguna base: son horas de vuelo para nada");
        avisarDeUnDetector(MODULO_STASH_FINDER,
            "sin él este barrido cubre terreno y no registra ningún contenedor: son horas de vuelo para nada");
    }

    private void avisarDeUnDetector(String nombre, String consecuencia) {
        Module module = Modules.get().get(nombre);
        if (module != null && module.isActive()) return;

        String estado = module == null ? "no está instalado" : "está apagado";
        String message = nombre + " " + estado + ", y " + consecuencia + ". Este módulo vuela; no detecta nada.";
        warning("%s", message);
        loudToast(message, Items.BARRIER);
    }

    /** Si la pechera lleva una elytra puesta, que es lo único con lo que Baritone puede volar. */
    private boolean wearsElytra() {
        return mc.player != null && mc.player.getEquippedStack(EquipmentSlot.CHEST).getItem() == Items.ELYTRA;
    }

    /**
     * El motivo por el que no se puede lanzar con el {@code chest-swap} de {@code elytra-fly}
     * configurado, o {@code null} si no hay conflicto. {@code ElytraFly.onDeactivate()} te cambia la
     * elytra por la pechera si {@code chest-swap} está en {@code Always}, y deja un oyente que hace
     * ese cambio al tocar suelo si está en {@code WaitForGround}: la preparación lo apaga como primer
     * paso y manda {@code #elytra} dos líneas después. Se rechaza en vez de acotarse porque acotarlo
     * sería tocar a espaldas del jugador un ajuste que Meteor persiste a disco.
     */
    private String chestSwapRejection() {
        ElytraFly module = Modules.get().get(ElytraFly.class);
        if (module == null || !module.isActive()) return null;

        ElytraFly.ChestSwapMode mode = module.chestSwap.get();
        if (mode == ElytraFly.ChestSwapMode.Never) return null;

        String consequence = mode == ElytraFly.ChestSwapMode.Always
            ? "te pone la pechera en el sitio de la elytra en ese mismo instante, y el \"" + prefijo.get()
                + "elytra\" sale dos líneas después: Baritone no despegaría, y te enterarías por el corte de atasco"
            : "deja armado un oyente que te quita la elytra en cuanto toques suelo, que es justo el aterrizaje de "
                + "Baritone: te la quitaría cuando el módulo cree haberlo restaurado todo";

        return "No se barre: elytra-fly está encendido con chest-swap en " + mode + ", y la preparación tiene que "
            + "apagarlo porque Baritone declara que su vuelo no funciona con impulso no vanilla. Apagarlo "
            + consequence + ". Pon chest-swap en Never dentro de elytra-fly, o apaga elytra-fly a mano antes de "
            + "lanzar.";
    }

    /**
     * Deshace una preparación que ya no puede terminar en vuelo y contesta por qué. No se llama a
     * {@link #finish(String, boolean)} a propósito: aquí no hay ningún barrido que dar por terminado
     * -no se ha mandado ni un {@code goal} ni un {@code elytra}-.
     */
    private String undoLaunch() {
        sweeping = false;
        SafetyNet.Restoration undone = restore();
        String pending = undone.warning(activePrefix);

        String why = "No se barre: preparar el entorno te ha dejado sin elytra puesta, así que Baritone no podría "
            + "despegar. He deshecho la preparación";
        if (pending == null) return why + " y el entorno ha quedado como estaba.";

        loudToast("La preparación se ha deshecho pero no ha llegado a Baritone: sus ajustes se han quedado en "
            + "valores de vuelo. Lee el chat.", Items.BARRIER);
        return why + ", pero " + pending + ".";
    }

    /** Cancelación del jugador. Devuelve el mensaje que el comando tiene que enseñar. */
    public String stop() {
        if (!sweeping) return "No hay ningún barrido en marcha.";
        if (finish("lo has cancelado", false).arrived()) return "Barrido cortado y entorno restaurado.";
        return "Barrido cortado, pero el entorno NO ha quedado restaurado: lee el aviso de arriba.";
    }

    public boolean isSweeping() {
        return sweeping;
    }

    /**
     * Preparación: los dos módulos prestados y, de una pieza, la secuencia de ajustes de Baritone que
     * el núcleo construye y prueba como una sola cosa.
     */
    private void prepare() {
        // Antes de anotar nada, devolver lo que quedara pendiente de un barrido anterior: si no, lo
        // que se anotaría como "reposo del jugador" sería el estado que dejó ese barrido.
        applyPendingModules();
        takeModule(Modules.get().get(ElytraFly.class), elytraFly);
        takeModule(Modules.get().get(ElytraReplace.class), elytraReplace);
        for (String command : BaritoneScript.preparation(activePrefix, flightSettings())) send(command);
    }

    /** Fija el vértice actual y relanza el vuelo: Baritone toma el objetivo al arrancar, no después. */
    private void aimAtCurrentWaypoint() {
        send(BaritoneScript.goTo(activePrefix, route.waypoints().get(index)));
        send(BaritoneScript.launch(activePrefix));
    }

    /**
     * El único final de todos los caminos de salida. Es idempotente: quien llegue segundo no hace
     * nada, que es justo lo que hace falta cuando el apagado del módulo y la salida del mundo se
     * solapan.
     *
     * @return qué pasó de verdad con la restauración, para quien tenga que contestar algo después
     */
    private SafetyNet.Restoration finish(String reason, boolean warn) {
        if (!sweeping) return SafetyNet.Restoration.ENTREGADA;
        sweeping = false;

        // Lo medido en este vuelo se guarda para la estimación previa del siguiente (spec §6), y solo
        // si hay medida: una tasa caducada o inexistente NO pisa la buena que hubiera guardada, que
        // sería cambiar un dato por un "no lo sé".
        fuel.blocksPerRocket().ifPresent(tasa -> bloquesPorCohete.set(tasa));

        SafetyNet.Restoration restoration = restore();
        String pending = restoration.warning(activePrefix);
        warnPendingModules();

        if (pending == null) {
            String message = "Barrido terminado: " + reason + ". Entorno restaurado.";
            if (warn) warning("%s", message);
            else if (avisos.get()) info("%s", message);
            return restoration;
        }

        // Emitir no es llegar, y decir "entorno restaurado" sin que haya llegado nada es la mentira
        // más cara del módulo: el jugador cree que ha aterrizado y Baritone sigue volando.
        warning("%s", "Barrido terminado: " + reason + ", pero el entorno NO ha quedado restaurado: " + pending + ".");
        loudToast("El barrido ha terminado pero la restauración no ha llegado a Baritone: puede seguir volando y "
            + "sus ajustes se han quedado en valores de vuelo. Lee el chat.", Items.BARRIER);
        return restoration;
    }

    /**
     * Devuelve todo lo que se tocó a su estado de reposo y dice si de verdad llegó. La red se desarma
     * la última, cuando ya no queda ni un comando por emitir: desarmarla antes dejaría el
     * {@code cancel} y la restauración sin cubrir, que es exactamente cuando más comandos se mandan
     * de golpe. Y va en un {@code finally} porque una red armada que sobreviviera a una excepción se
     * comería en silencio todo comando que el jugador escribiera a mano.
     */
    private SafetyNet.Restoration restore() {
        try {
            SafetyNet.Restoration outcome;
            if (mc.player == null) {
                outcome = SafetyNet.Restoration.SIN_JUGADOR;
            }
            else {
                // El && va detrás a propósito: primero se manda, siempre, y luego se acumula. Con la
                // condición delante, el primer comando cancelado se llevaría por delante los demás.
                boolean delivered = send(BaritoneScript.cancel(activePrefix));
                for (String command : BaritoneScript.restoration(activePrefix, restingSettings())) {
                    delivered = send(command) && delivered;
                }
                outcome = delivered ? SafetyNet.Restoration.ENTREGADA : SafetyNet.Restoration.CANCELADA;
            }

            releaseModule(Modules.get().get(ElytraFly.class), elytraFly);
            releaseModule(Modules.get().get(ElytraReplace.class), elytraReplace);

            return outcome;
        }
        finally {
            disarmNet();
            resetSweep();
        }
    }

    /**
     * El aviso de los módulos que no se han podido devolver todavía. Sale fuerte y con toast porque
     * el único momento en que esto pasa es al salir del mundo, donde el chat se va con la desconexión
     * y lo único que el jugador llega a leer es el toast.
     */
    private void warnPendingModules() {
        StringBuilder names = new StringBuilder();
        if (elytraFly.hasPending()) names.append(elytraFly.name());
        if (elytraReplace.hasPending()) {
            if (!names.isEmpty()) names.append(" y ");
            names.append(elytraReplace.name());
        }
        if (names.isEmpty()) return;

        String message = "Al salir del mundo no se puede encender ni apagar un módulo de Meteor sin dejarlo "
            + "suscrito dos veces al bus para el resto de la sesión, así que " + names + " se queda como estaba en "
            + "vuelo. Se devuelve solo en el primer tick tras volver a entrar; si cierras el cliente antes, "
            + "repásalo en la ClickGUI.";
        warning("%s", message);
        loudToast(message, Items.ELYTRA);
    }

    private void resetSweep() {
        sweeping = false;
        route = null;
        index = 0;
        pasadasDelPlan = 0;
        bloquesVolados = 0;
        bloquesDeLaUltimaMuestra = 0;
        bloquesSinProyeccion = 0;
        avisoSinProyeccionDado = false;
        stallWatch.reset();
        fireworkWatch.reset();
    }

    /** Suscribe el oyente de la red al bus. Idempotente: armar dos veces no duplica la suscripción. */
    private void armNet() {
        netCaughtWarned = false;
        if (netArmed) return;
        netArmed = true;
        MeteorClient.EVENT_BUS.subscribe(net);
    }

    /** Desuscribe el oyente. Idempotente, que es lo que hace segura la llamada de {@code onDeactivate}. */
    private void disarmNet() {
        if (!netArmed) return;
        netArmed = false;
        MeteorClient.EVENT_BUS.unsubscribe(net);
    }

    /** Anota cómo está el módulo y lo deja en su estado de vuelo. */
    private static void takeModule(Module module, BorrowedModule loan) {
        if (module == null) {
            loan.forget();
            return;
        }
        apply(module, loan.take(module.isActive()));
    }

    /** Devuelve el módulo a donde estaba, o deja la devolución pendiente si no se puede tocar. */
    private void releaseModule(Module module, BorrowedModule loan) {
        if (module == null) {
            loan.forget();
            return;
        }
        apply(module, loan.release(module.isActive(), !leavingWorld));
    }

    /**
     * Hace lo que quedó pendiente del desmontaje de salida del mundo. Es idempotente y barato: sin
     * nada pendiente no consulta ni el registro de módulos.
     */
    private void applyPendingModules() {
        if (elytraFly.hasPending()) applyPending(Modules.get().get(ElytraFly.class), elytraFly);
        if (elytraReplace.hasPending()) applyPending(Modules.get().get(ElytraReplace.class), elytraReplace);
    }

    private static void applyPending(Module module, BorrowedModule loan) {
        if (module == null) {
            loan.forget();
            return;
        }
        apply(module, loan.claimPending());
    }

    private static void apply(Module module, BorrowedModule.Action action) {
        switch (action) {
            case ENCENDER -> {
                if (!module.isActive()) module.enable();
            }
            case APAGAR -> {
                if (module.isActive()) module.disable();
            }
            case NADA -> { }
        }
    }

    /**
     * Manda un comando por el chat del jugador. {@code ChatUtils.sendPlayerMsg} manda el texto "como
     * si el usuario lo hubiera escrito en el chat", que es exactamente lo que Baritone escucha; se
     * pasa {@code addToHistory = false} para no dejar comandos con almohadilla a un intro de
     * publicarse en el historial del chat.
     *
     * @return si el comando salió del cliente hacia Baritone. {@code false} significa que la red tuvo
     *         que cancelarlo -Baritone no lo interceptó, así que no tenía a quién llegar- o que no
     *         había jugador. Todo el camino es síncrono, así que {@link #sendCaught} ya está decidido
     *         cuando esta llamada vuelve.
     */
    private boolean send(String command) {
        if (mc.player == null) return false;

        boolean outer = emitting;
        emitting = true;
        sendCaught = false;
        try {
            ChatUtils.sendPlayerMsg(command, false);
        }
        finally {
            emitting = outer;
        }
        return !sendCaught;
    }

    private BaritoneScript.FlightSettings flightSettings() {
        return new BaritoneScript.FlightSettings(autoSalto.get(), aterrizajeDeUrgencia.get(), ahorrarCohetes.get(),
            velocidadDeCohete.get(), semillaDelNether.get().strip());
    }

    private BaritoneScript.FlightSettings restingSettings() {
        // La semilla no se restaura: nunca fue un cambio nuestro, solo un dato que se le pasó a
        // Baritone si lo teníamos, y el núcleo no la escribe en la restauración.
        return new BaritoneScript.FlightSettings(autoSaltoEnReposo.get(), aterrizajeDeUrgenciaEnReposo.get(),
            ahorrarCohetesEnReposo.get(), velocidadDeCoheteEnReposo.get(), "");
    }

    /** La mitad visual de un aviso fuerte: el toast que acompaña al chat. */
    private void loudToast(String message, Item icon) {
        MeteorToast.Builder toast = new MeteorToast.Builder("Xploits").text(message).icon(icon);
        // MeteorToast.update() llama a play(customSound) sin comprobar el nulo y vanilla lo
        // dereferencia: NPE en el hilo de render. Nunca pasar null; se silencia con volumen cero.
        if (!sonidoEnAvisos.get()) {
            toast.sound(PositionedSoundInstance.master(SoundEvents.BLOCK_NOTE_BLOCK_CHIME.value(), 1.2f, 0f));
        }
        mc.getToastManager().add(toast.build());
    }

    public String status() {
        if (!isActive()) return "nether-sweep está apagado.";

        StringBuilder sb = new StringBuilder();
        if (!sweeping) {
            sb.append("nether-sweep encendido, sin barrido en marcha.");
            sb.append("\n  anchura de pasada: ").append(descripcionDeLaAnchura());
            sb.append("\n  gasto medido en barridos anteriores: ").append(bloquesPorCohete.get() > 0
                ? Math.round(bloquesPorCohete.get()) + " bloques por cohete"
                : "sin dato todavía");
            return sb.toString();
        }

        sb.append("Barriendo: pasada ").append(Math.min(index / 2 + 1, pasadasDelPlan))
            .append(" de ").append(pasadasDelPlan)
            .append(" · anchura ").append(anchuraUsada).append(" chunks (")
            .append(anchuraTecleada ? "tecleada" : "medida").append(")");
        sb.append("\n  chunks del área que ya estaban vistos al planificar: ").append(chunksYaVistos);
        sb.append("\n  volados ").append(Math.round(bloquesVolados)).append(" bloques");
        if (mc.player != null) {
            sb.append(", quedan ").append(Math.round(bloquesRestantes()))
                .append(contarElRegreso.get() ? " contando el regreso" : " sin contar el regreso");
        }
        OptionalDouble tasa = fuel.blocksPerRocket();
        sb.append("\n  gasto de este vuelo: ").append(tasa.isPresent()
            ? Math.round(tasa.getAsDouble()) + " bloques por cohete"
            : "sin medida ahora mismo, llevo " + Math.round(bloquesSinProyeccion) + " bloques sin poder proyectar");
        sb.append("\n  red de seguridad: ").append(netArmed ? "armada" : "DESARMADA");
        if (netCaughtWarned) sb.append(" y ya ha tenido que cancelar un comando: Baritone no está interceptando");
        return sb.toString();
    }

    private String descripcionDeLaAnchura() {
        if (anchuraDePasada.get() > 0) {
            return anchuraDePasada.get() + " chunks, TECLEADA a mano (no se mide nada)";
        }
        if (!probe.hasEnoughSamples()) {
            return String.format("midiéndose todavía, llevo %d de %d muestras del flujo de chunks%s",
                probe.sampleCount(), WidthProbe.MUESTRAS_MINIMAS, descartadas());
        }
        return String.format("%d chunks, medidos (radio observado %d chunks de un techo de %d, margen %.2f)%s",
            probe.laneWidthInChunks(margenDeAnchura.get()), probe.observedRadiusInChunks(), ultimoTecho,
            margenDeAnchura.get(), descartadas());
    }

    /**
     * La coletilla de las muestras descartadas por pasarse del techo. Se enseña porque es la
     * explicación de por qué la medida es la que es: un servidor con mucho retraso descarta muchas, y
     * sin verlo el jugador solo ve que la anchura tarda en salir.
     */
    private String descartadas() {
        int descartadas = probe.discardedSamples();
        if (descartadas == 0) return "";
        return String.format("; he descartado %d chunks que llegaron tarde -por encima de la distancia de "
            + "renderizado, así que no medían el alcance del servidor-", descartadas);
    }

    // --- La cobertura que ya existe (spec §5.1) -----------------------------------------------

    /**
     * Lo que salió de leer los cinco ficheros de {@code NewerNewChunks}: la cobertura unida y de
     * cuántos ficheros salió, para poder decirlo sin fingir que se leyó más de lo que había.
     *
     * <p><b>«No he podido saber dónde mirar» y «no había nada registrado» son cosas distintas</b> y
     * llevan campo propio. Las dos dan la misma cobertura vacía y el mismo plan -el rectángulo
     * entero-, pero significan lo contrario para el jugador: una es «empiezas de cero», que es
     * correcto y no cuesta nada; la otra es «ni he mirado», y entonces las tres horas de vuelo van a
     * repetir terreno que lleva meses acumulando. Contarlas como la misma cosa es dejarle despegar
     * creyendo lo primero cuando pasa lo segundo.
     */
    private record LecturaDeCobertura(Coverage cobertura, int leidos, int rotos, boolean servidorDesconocido) {
        /** La lectura que no llegó a hacerse porque no se supo en qué carpeta mirar. */
        static LecturaDeCobertura sinSaberDondeMirar() {
            return new LecturaDeCobertura(Coverage.empty(), 0, 0, true);
        }

        String resumen() {
            if (servidorDesconocido) {
                return "NO he leído la cobertura: no he podido saber en qué servidor estás";
            }
            if (leidos == 0 && rotos == 0) {
                return "ningún fichero de NewerNewChunks para este servidor y dimensión: se empieza de cero";
            }
            String base = leidos + " de " + FICHEROS_DE_COBERTURA.length + " ficheros de NewerNewChunks";
            return rotos == 0 ? base : base + ", " + rotos + " ilegibles";
        }
    }

    /**
     * Lee los cinco ficheros de {@code NewerNewChunks} del servidor y la dimensión activos y los une
     * (spec §5.1). Con 17.000 chunks ya vistos, empezar de cero sería repetir terreno que el jugador
     * lleva meses acumulando.
     *
     * <p><b>Que falte un fichero no es un error</b>: {@code NewerNewChunks} solo escribe los que tiene
     * algo que escribir, y que no exista ninguno equivale a empezar de cero. Que uno no se pueda leer
     * tampoco tira el resto -se cuenta y se dice-, por el mismo motivo por el que {@link Coverage} se
     * salta una línea rota en vez de abortar: tirar la lectura entera manda al jugador a repetir
     * terreno ya visto.
     *
     * <p>Se lee en {@code ISO-8859-1} y no en UTF-8 a propósito: el contenido son dígitos, comas y
     * saltos de línea, y ese juego de caracteres no puede lanzar {@code MalformedInputException}
     * sobre un fichero que el otro mod haya dejado a medio escribir al cerrarse el cliente de golpe.
     *
     * <p>Y se lee el fichero <b>entero</b> con {@code readString}, no línea a línea, porque
     * {@link Coverage#ofFileContent} necesita ver si termina en salto de línea: es lo único que
     * distingue un fichero cerrado de uno cortado a mitad de escritura, y una línea cortada puede
     * parsear como un chunk perfectamente válido que nunca se vio. El porqué entero está en su
     * javadoc.
     */
    private LecturaDeCobertura leerCobertura() {
        Path carpeta = carpetaDeCobertura();
        if (carpeta == null) return LecturaDeCobertura.sinSaberDondeMirar();

        List<Coverage> partes = new ArrayList<>(FICHEROS_DE_COBERTURA.length);
        int leidos = 0;
        int rotos = 0;
        for (String fichero : FICHEROS_DE_COBERTURA) {
            Path ruta = carpeta.resolve(fichero);
            if (!Files.isRegularFile(ruta)) continue;
            try {
                partes.add(Coverage.ofFileContent(Files.readString(ruta, StandardCharsets.ISO_8859_1)));
                leidos++;
            } catch (IOException | RuntimeException e) {
                rotos++;
                warning("%s", "No he podido leer " + fichero + " de NewerNewChunks (" + e.getClass().getSimpleName()
                    + "): sigo con los demás, pero el terreno que ese fichero registraba se replanificará como si "
                    + "no se hubiera visto.");
            }
        }
        return new LecturaDeCobertura(Coverage.merge(partes), leidos, rotos, false);
    }

    /**
     * La carpeta donde {@code NewerNewChunks} guarda la cobertura del servidor y la dimensión
     * activos, o {@code null} si no se puede saber cuál es.
     *
     * <p><b>Verificado leyendo el bytecode del jar instalado</b> -{@code trouser-streak-1.6.1},
     * {@code pwn.noobs.trouserstreak.modules.NewerNewChunks}- y confirmado contra las carpetas que ya
     * existen en disco, porque una ruta supuesta se leería vacía y «vacío» aquí significa
     * replanificar el rectángulo entero y repetir horas de terreno ya visto:
     *
     * <ul>
     *   <li>La raíz es {@code FabricLoader.getGameDir() / "TrouserStreak" / "NewChunks"}.</li>
     *   <li>El servidor es {@code mc.getCurrentServerEntry().address} -el mismo sitio del que lo saca
     *       {@code Utils.getWorldName()} de Meteor-, o, en un mundo local, el nombre de la carpeta
     *       que contiene la del mundo.</li>
     *   <li>La dimensión es {@code mc.world.getRegistryKey().getValue().toString()}, es decir
     *       {@code "minecraft:the_nether"}.</li>
     *   <li>Los dos pasan por el mismo reemplazo de {@link #CARACTERES_INVALIDOS}, que es lo que
     *       convierte {@code minecraft:the_nether} en {@code minecraft_the_nether}.</li>
     * </ul>
     */
    private Path carpetaDeCobertura() {
        if (mc.world == null) return null;

        String servidor = nombreDelServidor();
        if (servidor == null) return null;

        String dimension = limpiar(mc.world.getRegistryKey().getValue().toString());
        return FabricLoader.getInstance().getGameDir()
            .resolve("TrouserStreak")
            .resolve("NewChunks")
            .resolve(servidor)
            .resolve(dimension);
    }

    /** El nombre de carpeta del servidor, con la misma lógica que {@code NewerNewChunks}. */
    private String nombreDelServidor() {
        if (mc.isInSingleplayer()) {
            if (mc.getServer() == null) return "singleplayer";
            Path padre = mc.getServer().getSavePath(WorldSavePath.ROOT).getParent();
            if (padre == null || padre.getFileName() == null) return "singleplayer";
            return limpiar(padre.getFileName().toString());
        }

        ServerInfo entrada = mc.getCurrentServerEntry();
        if (entrada == null) return null;
        return limpiar(entrada.address);
    }

    /** El mismo saneado de nombres de carpeta que aplica {@code NewerNewChunks}, ni más ni menos. */
    private static String limpiar(String nombre) {
        return nombre.replaceAll(CARACTERES_INVALIDOS, "_");
    }
}
