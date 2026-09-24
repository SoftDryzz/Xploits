package com.xploits.sweep;

import com.xploits.XploitsAddon;
import com.xploits.console.core.Instantanea;
import com.xploits.elytra.ElytraReplace;
import com.xploits.shared.Texts;
import com.xploits.shared.XploitsModule;
import com.xploits.shared.core.i18n.Msg;
import com.xploits.shared.core.PositionedMsg;
import com.xploits.sweep.core.ChunkPos;
import com.xploits.sweep.core.Coverage;
import com.xploits.sweep.core.FuelBudget;
import com.xploits.sweep.core.Odometer;
import com.xploits.sweep.core.SweepArea;
import com.xploits.sweep.core.SweepPlanner;
import com.xploits.sweep.core.SweepRoute;
import com.xploits.sweep.core.SweepTally;
import com.xploits.sweep.core.SweepText;
import com.xploits.sweep.core.WidthProbe;
import com.xploits.travel.AutoTravel;
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
import java.util.Optional;
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
public class NetherSweep extends XploitsModule {
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

    /**
     * El suelo de {@code no-projection-grace}, y por qué no puede ser un intervalo de muestreo.
     *
     * <p>Una tasa de gasto necesita <b>dos</b> muestras: la primera solo fija la referencia de la
     * que se mide la segunda (ver {@link FuelBudget#sample}). Así que en la primera muestra
     * {@link #bloquesSinProyeccion} vale exactamente un intervalo y en la segunda, dos, si para
     * entonces todavía no ha bajado ningún cohete -que es lo normal al principio de un vuelo con
     * {@code elytraConserveFireworks} encendido-.
     *
     * <p>Con la gracia en un solo intervalo, la comparación de la primera muestra era
     * {@code 1000 < 1000}, falsa, y <b>el barrido se cortaba durante la aproximación</b>: sin haber
     * llegado a dar el aviso de media gracia, determinista, y con un valor que el propio deslizador
     * ofrecía. El suelo tiene que dejar pasar las dos muestras que la tasa necesita y alguna más,
     * así que son tres intervalos: en la segunda se avisa -2.000 ya pasa de la media gracia- y solo
     * en la tercera se corta.
     *
     * <p>A quien tuviera guardado el 1.000 de antes esto no le deja el módulo roto:
     * {@code Setting.set} de Meteor devuelve {@code false} sin escribir nada cuando el valor no pasa
     * {@code isValueValid}, y {@code DoubleSetting.load} carga por ahí, así que un valor persistido
     * por debajo del suelo se descarta al cargar y el ajuste se queda en su valor de fábrica.
     */
    private static final double GRACIA_MINIMA_SIN_PROYECCION = 3 * BLOQUES_POR_MUESTRA_DE_COHETES;

    /** Lo que mide un chunk de lado, en bloques: de aquí sale el enlace entre dos pasadas. */
    private static final int BLOQUES_POR_CHUNK = 16;

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
        .description(Texts.startupText(SweepText.SETTING_CHUNK_X_1))
        .defaultValue(0)
        .sliderRange(-10_000, 10_000)
        .build()
    );

    private final Setting<Integer> chunkZ1 = sgArea.add(new IntSetting.Builder()
        .name("chunk-z-1")
        .description(Texts.startupText(SweepText.SETTING_CHUNK_Z_1))
        .defaultValue(0)
        .sliderRange(-10_000, 10_000)
        .build()
    );

    private final Setting<Integer> chunkX2 = sgArea.add(new IntSetting.Builder()
        .name("chunk-x-2")
        .description(Texts.startupText(SweepText.SETTING_CHUNK_X_2))
        .defaultValue(0)
        .sliderRange(-10_000, 10_000)
        .build()
    );

    private final Setting<Integer> chunkZ2 = sgArea.add(new IntSetting.Builder()
        .name("chunk-z-2")
        .description(Texts.startupText(SweepText.SETTING_CHUNK_Z_2))
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
        .description(Texts.startupText(SweepText.SETTING_LANE_WIDTH))
        .defaultValue(0)
        .min(0)
        .sliderRange(0, 64)
        .build()
    );

    private final Setting<Double> margenDeAnchura = sgPasada.add(new DoubleSetting.Builder()
        .name("lane-width-margin")
        .description(Texts.startupText(SweepText.SETTING_LANE_WIDTH_MARGIN))
        .defaultValue(0.2)
        .range(0, 0.9)
        .sliderRange(0, 0.9)
        .decimalPlaces(2)
        .build()
    );

    private final Setting<Double> margenDeWaypoint = sgPasada.add(new DoubleSetting.Builder()
        .name("waypoint-margin")
        .description(Texts.startupText(SweepText.SETTING_WAYPOINT_MARGIN))
        .defaultValue(RoutePlanner.DEFAULT_WAYPOINT_MARGIN)
        .min(RoutePlanner.MIN_WAYPOINT_MARGIN)
        .sliderRange(RoutePlanner.MIN_WAYPOINT_MARGIN, 500)
        .decimalPlaces(0)
        .build()
    );

    // Cohetes (spec §6): el límite real de un barrido no es el tiempo.

    private final Setting<Double> reservaDeCohetes = sgCohetes.add(new DoubleSetting.Builder()
        .name("firework-reserve")
        .description(Texts.startupText(SweepText.SETTING_FIREWORK_RESERVE))
        .defaultValue(0.2)
        .min(0)
        .sliderRange(0, 1)
        .decimalPlaces(2)
        .build()
    );

    private final Setting<Boolean> contarElRegreso = sgCohetes.add(new BoolSetting.Builder()
        .name("count-return-trip")
        .description(Texts.startupText(SweepText.SETTING_COUNT_RETURN_TRIP))
        .defaultValue(true)
        .build()
    );

    private final Setting<Double> bloquesPorCohete = sgCohetes.add(new DoubleSetting.Builder()
        .name("blocks-per-firework")
        .description(Texts.startupText(SweepText.SETTING_BLOCKS_PER_FIREWORK))
        .defaultValue(0)
        .min(0)
        .sliderRange(0, 400)
        .decimalPlaces(1)
        .build()
    );

    private final Setting<Double> graciaSinProyeccion = sgCohetes.add(new DoubleSetting.Builder()
        .name("no-projection-grace")
        .description(Texts.startupText(SweepText.SETTING_NO_PROJECTION_GRACE))
        .defaultValue(5_000)
        .min(GRACIA_MINIMA_SIN_PROYECCION)
        .sliderRange(GRACIA_MINIMA_SIN_PROYECCION, 50_000)
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
        .description(Texts.startupText(SweepText.SETTING_BARITONE_PREFIX))
        .defaultValue("#")
        .build()
    );

    private final Setting<Boolean> autoSalto = sgVuelo.add(new BoolSetting.Builder()
        .name("auto-jump")
        .description(Texts.startupText(SweepText.SETTING_AUTO_JUMP))
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> autoSaltoEnReposo = sgVuelo.add(new BoolSetting.Builder()
        .name("auto-jump-resting")
        .description(Texts.startupText(SweepText.SETTING_AUTO_JUMP_RESTING))
        .defaultValue(BaritoneScript.baritoneDefaults().autoJump())
        .build()
    );

    private final Setting<Boolean> aterrizajeDeUrgencia = sgVuelo.add(new BoolSetting.Builder()
        .name("emergency-land")
        .description(Texts.startupText(SweepText.SETTING_EMERGENCY_LAND))
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> aterrizajeDeUrgenciaEnReposo = sgVuelo.add(new BoolSetting.Builder()
        .name("emergency-land-resting")
        .description(Texts.startupText(SweepText.SETTING_EMERGENCY_LAND_RESTING))
        .defaultValue(BaritoneScript.baritoneDefaults().allowEmergencyLand())
        .build()
    );

    private final Setting<Boolean> ahorrarCohetes = sgVuelo.add(new BoolSetting.Builder()
        .name("conserve-fireworks")
        .description(Texts.startupText(SweepText.SETTING_CONSERVE_FIREWORKS))
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> ahorrarCohetesEnReposo = sgVuelo.add(new BoolSetting.Builder()
        .name("conserve-fireworks-resting")
        .description(Texts.startupText(SweepText.SETTING_CONSERVE_FIREWORKS_RESTING))
        .defaultValue(BaritoneScript.baritoneDefaults().conserveFireworks())
        .build()
    );

    private final Setting<Double> velocidadDeCohete = sgVuelo.add(new DoubleSetting.Builder()
        .name("firework-speed")
        .description(Texts.startupText(SweepText.SETTING_FIREWORK_SPEED))
        .defaultValue(1)
        .min(0)
        .sliderRange(0.5, 3)
        .decimalPlaces(2)
        .build()
    );

    private final Setting<Double> velocidadDeCoheteEnReposo = sgVuelo.add(new DoubleSetting.Builder()
        .name("firework-speed-resting")
        .description(Texts.startupText(SweepText.SETTING_FIREWORK_SPEED_RESTING))
        .defaultValue(BaritoneScript.baritoneDefaults().fireworkSpeed())
        .min(0)
        .sliderRange(0.5, 3)
        .decimalPlaces(2)
        .build()
    );

    private final Setting<String> semillaDelNether = sgVuelo.add(new StringSetting.Builder()
        .name("nether-seed")
        .description(Texts.startupText(SweepText.SETTING_NETHER_SEED))
        .defaultValue("")
        .build()
    );

    // Avisos

    private final Setting<Boolean> avisos = sgAvisos.add(new BoolSetting.Builder()
        .name("notify")
        .description(Texts.startupText(SweepText.SETTING_NOTIFY))
        .defaultValue(true)
        .build()
    );

    private final Setting<Double> sueloDeCobertura = sgAvisos.add(new DoubleSetting.Builder()
        .name("coverage-floor")
        .description(Texts.startupText(SweepText.SETTING_COVERAGE_FLOOR))
        .defaultValue(0.95)
        .range(0, 1)
        .sliderRange(0, 1)
        .decimalPlaces(2)
        .build()
    );

    private final Setting<Boolean> sonidoEnAvisos = sgAvisos.add(new BoolSetting.Builder()
        .name("notify-sound")
        .description(Texts.startupText(SweepText.SETTING_NOTIFY_SOUND))
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

    /**
     * El cuentakilómetros del jugador, <b>de toda la sesión y no de un barrido</b>: se alimenta en
     * cada tick con el módulo encendido, se esté volando o no, porque de él salen dos cosas que hacen
     * falta en los dos estados. Una es la velocidad del último tick, que es lo que decide si una
     * muestra de anchura vale (ver {@link WidthProbe#sample}); la otra son los bloques volados, que
     * se miden por diferencia contra {@link #bloquesAlDespegar} en vez de reiniciando el contador,
     * para que el paso del tick del despegue no salga falseado a cero justo cuando la sonda lo mira.
     */
    private final Odometer odometro = new Odometer();

    /** En qué kilometraje del cuentakilómetros arrancó este barrido. */
    private double bloquesAlDespegar;

    /** Dónde estaba el jugador en el tick anterior, para medir el desplazamiento real. */
    private Waypoint posicionAnterior = new Waypoint(0, 0);

    /**
     * Si {@link #posicionAnterior} es de verdad la del tick pasado. Es falso al entrar al mundo y
     * mientras no hay jugador: sin posición anterior no hay desplazamiento que medir, y suponer uno
     * sería meterle al cuentakilómetros un salto que nadie voló.
     */
    private boolean hayPosicionAnterior;

    /** En qué kilometraje se tomó la última muestra de cohetes. */
    private double bloquesDeLaUltimaMuestra;

    /** Bloques volados sin que {@link FuelBudget#blocksPerRocket()} haya podido dar una tasa. */
    private double bloquesSinProyeccion;

    /** Si ya se avisó una vez de que no hay proyección de cohetes, para no repetirlo por muestra. */
    private boolean avisoSinProyeccionDado;

    /**
     * La cuenta de cuántos chunks del área han llegado de verdad (spec §9). Se arma al planificar
     * -con la cobertura previa ya marcada- y se alimenta con cada chunk que llega mientras se vuela,
     * para que el mensaje final pueda decir <b>cuánto se miró</b> y no solo que el recorrido
     * terminó. Es {@code null} mientras no hay barrido en marcha.
     */
    private SweepTally tally;

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
        super(XploitsAddon.CATEGORY, "nether-sweep", Texts.startupText(SweepText.MODULE_DESC));
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

        info(SweepText.ARMED);
        info(SweepText.MEASURING);
    }

    @Override
    public void onDeactivate() {
        // Al dejar el mundo también se pasa por aquí, pero para entonces onGameLeft ya ha cerrado el
        // barrido: corre antes por prioridad y finish() es idempotente.
        finish(SweepText.REASON_MODULE_OFF, false);
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
        finish(SweepText.REASON_LEFT_WORLD, false);
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
                info(SweepText.RENDER_DISTANCE_CHANGED, "from", ultimoTecho, "to", techo);
            }
            ultimoTecho = techo;
            probe = new WidthProbe();
        }

        int jugadorX = (int) Math.floor(mc.player.getX()) >> 4;
        int jugadorZ = (int) Math.floor(mc.player.getZ()) >> 4;
        int chunkX = event.chunk().getPos().x;
        int chunkZ = event.chunk().getPos().z;

        // Esta es la comprobación de que el terreno ha llegado de verdad (spec §9), y sale de aquí
        // y no de releer los ficheros del otro mod porque aquí es donde está el hecho: un evento por
        // chunk que el servidor manda, sin depender de que NewerNewChunks esté encendido ni de que
        // haya llegado a volcar a disco.
        if (sweeping && tally != null) tally.record(chunkX, chunkZ);

        // La velocidad del último tick decide si esta muestra mide el alcance del servidor o la
        // deriva de su cola: el porqué entero está en el javadoc de WidthProbe. Y mientras no haya
        // una posición anterior con la que compararse no hay velocidad medida, solo un cero de
        // arranque: muestrear con él es declarar quieto a un jugador que puede venir volando -el
        // caso de encender el módulo en pleno vuelo-, y esa muestra entraría inflada. Sin medida no
        // se mide; el tick siguiente ya la habrá.
        if (!hayPosicionAnterior) return;
        probe.sample(
            new ChunkPos(jugadorX, jugadorZ),
            new ChunkPos(chunkX, chunkZ),
            techo,
            odometro.lastStep());
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

        Msg message = Msg.of(SweepText.NET_CAUGHT, "command", text);
        // El comando cancelado puede ser un #goal con coordenadas: a la consola solo va su verbo.
        Msg sinArgumentos = Msg.of(SweepText.NET_CAUGHT_VERB, "verb", SafetyNet.verbo(text));
        warningPrivate(new PositionedMsg(message, sinArgumentos));
        loudToast(message, Items.BARRIER);
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        // Lo que quedó por devolver al salir del mundo se devuelve aquí, en el primer tick tras
        // volver a entrar: para entonces Modules.onGameJoined ya ha terminado de suscribir a todos.
        applyPendingModules();

        // El cuentakilómetros va siempre, se esté volando o no: con el módulo encendido y sin
        // barrido su único trabajo útil es medir la anchura de pasada, y para saber si una muestra
        // vale hace falta saber a qué velocidad iba el jugador cuando llegó.
        medirDesplazamiento();

        if (!sweeping) return;

        if (mc.player == null || mc.world == null) {
            finish(SweepText.REASON_LOST_WORLD, true);
            return;
        }
        if (!mc.player.isAlive()) {
            // No hay evento de muerte en Meteor, así que se observa aquí; el jugador sigue existiendo
            // en la pantalla de muerte, así que la restauración todavía puede hablarle a Baritone.
            finish(SweepText.REASON_DIED, true);
            return;
        }

        Waypoint aqui = new Waypoint(mc.player.getX(), mc.player.getZ());

        checkFireworks();
        if (muestrearCohetes()) return;

        double distancia = aqui.distanceTo(route.waypoints().get(index));

        // El margen con el que se da por alcanzado un waypoint no es el mismo para todos -el último
        // es el único sitio donde Baritone debe aterrizar-, y esa decisión vive en el núcleo.
        if (distancia <= RoutePlanner.reachedMargin(index, route.size(), margenDeWaypoint.get())) {
            index++;
            if (index >= route.size()) {
                finish(SweepText.REASON_DONE, false);
                return;
            }
            if (avisos.get() && index % 2 == 0) {
                info(SweepText.LANE_PROGRESS, "lane", index / 2 + 1, "total", pasadasDelPlan);
            }
            aimAtCurrentWaypoint();
            return;
        }

        // Lo único observable desde fuera es si la distancia baja; Baritone no informa de nada más.
        // El índice va en la llamada a propósito: es lo que hace que el salto de distancia al cambiar
        // de waypoint no se lea como cuarenta y cinco segundos sin avanzar.
        if (stallWatch.tick(index, distancia)) {
            Msg message = Msg.of(SweepText.STALLED, "index", index + 1, "seconds", stallWatch.limitSeconds(),
                "distance", Math.round(distancia));
            warning(message);
            loudToast(message, Items.ELYTRA);
            finish(SweepText.REASON_STALL, false);
        }
    }

    /**
     * Mide lo que el jugador se ha desplazado en este tick y se lo pasa al cuentakilómetros, que es
     * quien decide si eso fue vuelo o fue un salto.
     *
     * <p><b>Un teletransporte no es vuelo</b> -un portal, un {@code /tpa}, reaparecer, un tirón del
     * servidor- y sumarlo a los bloques recorridos infla la tasa de bloques por cohete hacia el lado
     * peligroso: la proyección contesta que los cohetes llegan cuando no llegan. El filtro y su
     * razonamiento viven en {@link Odometer}, en el núcleo y con tests.
     *
     * <p>Sin posición anterior -al entrar al mundo, o tras un tick sin jugador- no hay
     * desplazamiento que medir, así que se registra cero: inventar uno sería justo meterle al
     * cuentakilómetros el salto que existe para no contar.
     */
    private void medirDesplazamiento() {
        if (mc.player == null) {
            hayPosicionAnterior = false;
            odometro.advance(0);
            return;
        }

        Waypoint aqui = new Waypoint(mc.player.getX(), mc.player.getZ());
        odometro.advance(hayPosicionAnterior ? aqui.distanceTo(posicionAnterior) : 0);
        posicionAnterior = aqui;
        hayPosicionAnterior = true;
    }

    /**
     * Los bloques volados desde el despegue de este barrido, que es lo que se le pasa a
     * {@link FuelBudget}. Sale por diferencia contra el cuentakilómetros de la sesión -ver
     * {@link #odometro}- y ya viene sin los tramos que no se volaron.
     */
    private double bloquesVolados() {
        return odometro.blocksFlown() - bloquesAlDespegar;
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

        Msg message = cohetes == 0
            ? Msg.of(SweepText.OUT_OF_FIREWORKS)
            : Msg.of(SweepText.LOW_FIREWORKS, "count", cohetes, "threshold", fireworkWatch.threshold());
        warning(message);
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
        double volados = bloquesVolados();
        double desdeLaUltima = volados - bloquesDeLaUltimaMuestra;
        if (desdeLaUltima < BLOQUES_POR_MUESTRA_DE_COHETES) return false;
        bloquesDeLaUltimaMuestra = volados;

        int cohetes = InvUtils.find(Items.FIREWORK_ROCKET).count();
        fuel.sample(volados, cohetes);

        OptionalDouble tasa = fuel.blocksPerRocket();
        if (tasa.isEmpty()) {
            bloquesSinProyeccion += desdeLaUltima;
            if (bloquesSinProyeccion < graciaSinProyeccion.get()) {
                if (!avisoSinProyeccionDado && bloquesSinProyeccion >= graciaSinProyeccion.get() / 2) {
                    avisoSinProyeccionDado = true;
                    warning(SweepText.NO_PROJECTION_WARNING, "flown", Math.round(bloquesSinProyeccion),
                        "left", Math.round(graciaSinProyeccion.get() - bloquesSinProyeccion));
                }
                return false;
            }

            Msg message = Msg.of(SweepText.NO_PROJECTION_CUT, "flown", Math.round(bloquesSinProyeccion),
                "grace", Math.round(graciaSinProyeccion.get()));
            warning(message);
            loudToast(message, Items.FIREWORK_ROCKET);
            finish(SweepText.REASON_NO_PROJECTION, false);
            return true;
        }

        bloquesSinProyeccion = 0;
        avisoSinProyeccionDado = false;

        double restante = bloquesRestantes();
        if (!fuel.willRunOut(restante, cohetes, reservaDeCohetes.get())) return false;

        long necesarios = (long) Math.ceil(restante / tasa.getAsDouble() * (1 + reservaDeCohetes.get()));
        // Es el único mensaje de corte que llega con el jugador lejos de casa, así que nombra los
        // dos ajustes de los que salen sus números: sin ellos, "unos 420 cohetes" es una cifra que
        // no se sabe de dónde viene y el jugador no tiene qué tocar para la próxima vez.
        Msg message = Msg.of(SweepText.FIREWORKS_SHORT, "rate", Math.round(tasa.getAsDouble()),
            "remaining", Math.round(restante),
            "return", contarElRegreso.get()
                ? SweepText.FIREWORKS_SHORT_WITH_RETURN
                : SweepText.FIREWORKS_SHORT_WITHOUT_RETURN,
            "reserve", Math.round(reservaDeCohetes.get() * 100), "needed", necesarios, "count", cohetes,
            "counts", contarElRegreso.get() ? SweepText.RETURN_COUNTED : SweepText.RETURN_NOT_COUNTED);
        warning(message);
        loudToast(message, Items.FIREWORK_ROCKET);
        finish(SweepText.REASON_OUT_OF_FIREWORKS, false);
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
    public Msg start() {
        if (!isActive()) return Msg.of(SweepText.START_MODULE_OFF);
        if (sweeping) return Msg.of(SweepText.START_ALREADY_SWEEPING);
        Msg viajeEnMarcha = rechazoPorAutoTravel();
        if (viajeEnMarcha != null) return viajeEnMarcha;
        if (mc.player == null || mc.world == null) return Msg.of(SweepText.START_NO_WORLD);
        if (!mc.player.isAlive()) return Msg.of(SweepText.START_DEAD);
        if (!World.NETHER.equals(mc.world.getRegistryKey())) {
            // El módulo entero está construido sobre que un bloque del Nether cubre 64 veces más
            // superficie del Overworld: el área se teclea en chunks del Nether y lo que se anuncia
            // como cobertura equivalente sale de multiplicar por 8. Volarlo en otra dimensión no
            // rompe la geometría, pero convierte ese anuncio en mentira, y es el número por el que
            // el jugador decide si el barrido vale las horas que cuesta.
            return Msg.of(SweepText.START_NOT_NETHER);
        }
        if (!FabricLoader.getInstance().isModLoaded(BARITONE_MOD_ID)) {
            // A propósito NO se usa BaritoneUtils.IS_AVAILABLE: Meteor lo pone a true tras un
            // Class.forName sobre una clase que el jar ofuscado no expone, así que ahí vale false
            // aunque Baritone esté perfectamente instalado.
            return Msg.of(SweepText.START_NO_BARITONE);
        }
        if (InvUtils.find(Items.FIREWORK_ROCKET).count() == 0) return Msg.of(SweepText.START_NO_FIREWORKS);
        if (!wearsElytra()) return Msg.of(SweepText.START_NO_ELYTRA);
        Msg chestSwapRejection = chestSwapRejection();
        if (chestSwapRejection != null) return chestSwapRejection;

        String launchPrefix = prefijo.get();
        Msg prefixRejection = SafetyNet.prefixRejection(launchPrefix);
        if (prefixRejection != null) {
            // Antes de armar la red y antes del primer comando: armarla sobre un prefijo inservible
            // es tener red sin saber qué vigila.
            return Msg.of(SweepText.NOT_SWEEPING_PREFIX, "reason", prefixRejection);
        }

        SweepArea area = SweepArea.ofChunks(chunkX1.get(), chunkZ1.get(), chunkX2.get(), chunkZ2.get());

        // El tamaño se comprueba aquí, ANTES de leer la cobertura y antes de planificar, porque los
        // dos recorren el rectángulo entero chunk a chunk en el hilo principal -Coverage.seenIn para
        // contar lo ya visto y SweepPlanner para decidir qué bandas saltarse-. Con un área tecleada
        // de más, el cliente se queda colgado dentro de un comando y ni siquiera llega el rechazo.
        Msg tamanoRechazo = area.oversizeRejection();
        if (tamanoRechazo != null) return Msg.of(SweepText.NOT_SWEEPING, "reason", tamanoRechazo);

        Msg anchuraRechazo = resolverAnchura();
        if (anchuraRechazo != null) return anchuraRechazo;

        LecturaDeCobertura lectura = leerCobertura();
        Coverage vista = lectura.cobertura();
        // Del área, no de la dimensión entera: lo que le dice al jugador cuánto le ahorra su
        // cobertura previa es lo que cae DENTRO del rectángulo que ha pedido. Con size() el mensaje
        // llegaba a anunciar más chunks vistos que chunks tiene el área.
        //
        // Y la cuenta se arma aquí, no al terminar, porque tiene que arrancar sabiendo qué chunks
        // del área NO hacía falta volver a ver: los de las bandas que el planificador se salta. De
        // ella sale el "cuánto he mirado" del mensaje final (spec §9).
        SweepTally cuenta = SweepTally.of(area, vista);

        SweepPlanner.SweepPlan plan = SweepPlanner.plan(area, vista, anchuraUsada);
        if (plan.isRejected()) return Msg.of(SweepText.NOT_SWEEPING, "reason", plan.rejection());
        if (plan.lanes().isEmpty()) {
            return Msg.of(SweepText.NOTHING_TO_SWEEP, "chunks", area.chunkCount(), "reading", lectura.resumen(),
                "equivalent", area.overworldEquivalent());
        }

        Waypoint aqui = new Waypoint(mc.player.getX(), mc.player.getZ());
        // Todas las distancias del viaje -aproximación incluida- las calcula SweepRoute, en el núcleo
        // y con tests: aquí no se rehace ninguna a mano, que es por donde se coló dos veces un
        // "total" que no incluía la aproximación y que acabó decidiendo si había cohetes.
        SweepRoute ruta = SweepRoute.of(plan.lanes(), aqui, contarElRegreso.get());

        Msg separacionRechazo = rechazoPorSeparacion(ruta);
        if (separacionRechazo != null) return separacionRechazo;

        avisarDeLosDetectores();
        avisarDeEnlacesCortos(ruta);
        if (lectura.servidorDesconocido()) {
            // No es "no hay nada registrado": es "ni he mirado". El plan sale igual que si empezara
            // de cero, así que sin decirlo el jugador vuela tres horas repitiendo terreno que lleva
            // meses acumulando sin enterarse de que su cobertura previa no ha entrado en la cuenta.
            Msg message = Msg.of(SweepText.UNKNOWN_SERVER);
            warning(message);
            loudToast(message, Items.BARRIER);
        }
        if (anchuraTecleada) {
            Msg message = Msg.of(SweepText.TYPED_WIDTH, "width", anchuraUsada);
            warning(message);
            loudToast(message, Items.BARRIER);
        }

        activePrefix = launchPrefix;
        route = ruta;
        tally = cuenta;
        posicionAnterior = aqui;
        hayPosicionAnterior = true;
        index = 0;
        pasadasDelPlan = plan.lanes().size();
        bloquesAlDespegar = odometro.blocksFlown();
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

        // Y la sonda se tira aquí, ya volando. Un barrido no puede planificar con el máximo que
        // quedó del anterior: la anchura tiene que salir de muestras tomadas con el jugador parado
        // -las de un vuelo se descartan, ver WidthProbe-, y arrastrar la medida vieja es planificar
        // sobre un alcance que el servidor tenía hace tres horas. Para relanzar se vuelve a medir,
        // que son unos segundos andando.
        probe = new WidthProbe();

        return Msg.of(SweepText.LAUNCHED, "lanes", plan.lanes().size(), "width", anchuraUsada,
            "how", anchuraTecleada ? SweepText.WIDTH_TYPED : SweepText.WIDTH_MEASURED, "chunks", area.chunkCount(),
            "seen", cuenta.alreadySeen(), "reading", lectura.resumen(), "approach", Math.round(ruta.approachBlocks()),
            "sweep", Math.round(ruta.sweepBlocks()),
            "return", contarElRegreso.get()
                ? Msg.of(SweepText.LAUNCHED_RETURN, "blocks", Math.round(ruta.returnBlocks()))
                : Msg.of(SweepText.NOTHING),
            "total", Math.round(ruta.totalBlocks()), "equivalent", area.overworldEquivalent(),
            "estimate", estimacionDeCohetes(ruta.totalBlocks()));
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
    private Msg resolverAnchura() {
        if (anchuraDePasada.get() > 0) {
            anchuraUsada = anchuraDePasada.get();
            anchuraTecleada = true;
            return null;
        }
        if (!probe.hasEnoughSamples()) {
            return Msg.of(SweepText.NOT_ENOUGH_SAMPLES, "samples", probe.sampleCount(),
                "needed", WidthProbe.MUESTRAS_MINIMAS, "discarded", descartadas());
        }
        anchuraUsada = probe.laneWidthInChunks(margenDeAnchura.get());
        anchuraTecleada = false;
        return null;
    }

    /**
     * El motivo por el que la ruta no se puede volar con el margen de waypoint configurado, o
     * {@code null} si se puede.
     *
     * <p><b>Lo que se mira es la pasada más corta, no el hueco más corto</b>, y ahí está medio
     * arreglo. Dos vértices que caben dentro del margen se consumen casi seguidos: el adaptador
     * suelta el primero y al tick siguiente suelta el segundo, así que Baritone nunca llega a volar
     * hacia el de en medio. Si esos dos vértices son <b>los extremos de una pasada</b>, esa pasada no
     * se vuela nunca y el barrido la da por peinada igual: la mentira de spec §9. Si son el final de
     * una pasada y el arranque de la siguiente, lo que se pierde es la esquina y no el terreno -el
     * objetivo pasa a ser el final de la pasada siguiente y Baritone cruza la banda en diagonal-, así
     * que eso se avisa en {@link #avisarDeEnlacesCortos(SweepRoute)} y no se rechaza. El porqué
     * entero, en {@link SweepRoute#shortestLane()} y {@link SweepRoute#shortestLink()}.
     *
     * <p><b>Lo que costaba no distinguirlos:</b> el hueco más corto de un barrido casi siempre es un
     * enlace, y contra el suelo de las rutas de evasión -{@code RoutePlanner.minimumSpacing}, el
     * doble del margen y nunca menos de 300 bloques- hacía falta una anchura de 19 chunks, o sea un
     * radio observado de 12. Un servidor que entregara 8, 10 u 11 -normal en un anarchy cargado- veía
     * <b>rechazado todo barrido medido, siempre y para cualquier rectángulo</b>, y ninguna de las tres
     * salidas que el mensaje ofrecía servía: por debajo de 150 el margen no movía el suelo, agrandar
     * el área no separa las bandas y subir la anchura a mano no aplica a quien la tiene medida. Ahora
     * lo único que se rechaza es lo que de verdad pierde terreno, y eso solo pasa en un área diminuta
     * por su eje largo, donde «agranda el área» sí es una salida.
     */
    private Msg rechazoPorSeparacion(SweepRoute ruta) {
        double minima = SweepRoute.minimumGap(margenDeWaypoint.get());
        double pasada = ruta.shortestLane();
        if (pasada > minima) return null;

        return Msg.of(SweepText.LANE_TOO_SHORT, "lane", Math.round(pasada),
            "margin", Math.round(margenDeWaypoint.get()), "minimum", Math.round(minima),
            "chunks", (long) Math.ceil(minima / BLOQUES_POR_CHUNK),
            "fix", pasada > RoutePlanner.MIN_WAYPOINT_MARGIN
                ? Msg.of(SweepText.LANE_TOO_SHORT_LOWER_MARGIN, "lane", Math.round(pasada),
                    "min", Math.round(RoutePlanner.MIN_WAYPOINT_MARGIN))
                : Msg.of(SweepText.LANE_TOO_SHORT_MARGIN_NOT_ENOUGH, "min", Math.round(RoutePlanner.MIN_WAYPOINT_MARGIN)));
    }

    /**
     * Los avisos sobre los enlaces entre pasadas: lo que se pierde cuando las bandas quedan juntas.
     * <b>Avisan, no rechazan</b>, y la diferencia es el criterio de siempre: ninguno de los dos casos
     * pierde una pasada, y lo único que este módulo no puede hacer es dar por peinado lo que no miró.
     *
     * <ul>
     *   <li><b>El enlace cabe dentro del margen.</b> El adaptador lo consume sin volarlo, así que
     *       Baritone nunca recibe la esquina: su objetivo pasa a ser el final de la pasada siguiente
     *       y vuela hasta él en diagonal, cruzando la banda igual. Se pierde la esquina limpia, no el
     *       terreno -razonado en {@link SweepRoute#shortestLink()}-, pero el jugador tiene que
     *       saberlo porque los bordes de esa banda pasan más lejos del cliente de lo previsto.</li>
     *   <li><b>El enlace es más corto de lo que una elytra vuela como tramo</b>
     *       ({@link RoutePlanner#MIN_WAYPOINT_SPACING}, unos cuatro radios de giro). Baritone se pasa
     *       de largo y vuelve a por el vértice: más lento y más cohetes, pero la pasada se vuela
     *       entera.</li>
     * </ul>
     *
     * <p>Una ruta de una sola pasada no tiene ningún enlace, y entonces {@code shortestLink()} vale
     * {@code Double.MAX_VALUE}: no entra en ninguno de los dos avisos, que es lo correcto.
     */
    private void avisarDeEnlacesCortos(SweepRoute ruta) {
        double enlace = ruta.shortestLink();
        if (enlace <= SweepRoute.minimumGap(margenDeWaypoint.get())) {
            warning(SweepText.LINK_INSIDE_MARGIN, "link", Math.round(enlace),
                "margin", Math.round(margenDeWaypoint.get()));
            return;
        }
        if (enlace >= RoutePlanner.MIN_WAYPOINT_SPACING) return;

        warning(SweepText.LINK_TOO_SHORT, "link", Math.round(enlace),
            "spacing", Math.round(RoutePlanner.MIN_WAYPOINT_SPACING));
    }

    /**
     * El motivo por el que no se puede barrer con {@code auto-travel} volando, o {@code null} si no
     * lo está.
     *
     * <p><b>Los dos módulos dirigen al mismo Baritone por los mismos comandos</b>, y ninguno
     * preguntaba por el otro pese a que se usan en el mismo viaje -se vuela hasta la zona con
     * {@code auto-travel} y se barre al llegar-. Lo que pasa si se solapan, en orden: {@code #goal}
     * solo admite un objetivo, así que el segundo en lanzar se queda con Baritone; el primero ve
     * crecer su distancia al suyo, a los 45 s salta su vigilancia de atasco y emite su {@code
     * #cancel} y su restauración entera, que <b>para el vuelo del segundo a mitad</b> y además le
     * devuelve {@code elytraFireworkSpeed} a un valor de reposo distinto del que él cree estar
     * usando; el segundo no se entera de nada y 45 s después diagnostica un atasco que no existe.
     * Y como cada uno se presta {@code elytra-fly} y {@code elytra-replace} con su propio
     * {@code BorrowedModule}, el segundo anota como «reposo del jugador» el estado que dejó el
     * primero, y al terminar lo deja ahí.
     *
     * <p>Es simétrico: la misma guarda está en {@code AutoTravel.start()} mirando hacia aquí.
     */
    private Msg rechazoPorAutoTravel() {
        AutoTravel viaje = Modules.get().get(AutoTravel.class);
        if (viaje == null || !viaje.isTravelling()) return null;

        return Msg.of(SweepText.AUTO_TRAVEL_RUNNING);
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
    private Msg estimacionDeCohetes(double bloquesTotales) {
        int llevas = InvUtils.find(Items.FIREWORK_ROCKET).count();
        double tasa = bloquesPorCohete.get();
        if (tasa <= 0) {
            return Msg.of(SweepText.ESTIMATE_NO_DATA, "count", llevas);
        }
        long necesarios = (long) Math.ceil(bloquesTotales / tasa * (1 + reservaDeCohetes.get()));
        if (necesarios <= llevas) {
            return Msg.of(SweepText.ESTIMATE_ENOUGH, "needed", necesarios, "rate", Math.round(tasa), "count", llevas);
        }
        return Msg.of(SweepText.ESTIMATE_SHORT, "needed", necesarios, "rate", Math.round(tasa), "count", llevas,
            "missing", necesarios - llevas);
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
        avisarDeUnDetector(MODULO_NEWER_NEW_CHUNKS, SweepText.DETECTOR_NEWER_NEW_CHUNKS);
        avisarDeUnDetector(MODULO_BASE_FINDER, SweepText.DETECTOR_BASE_FINDER);
        avisarDeUnDetector(MODULO_STASH_FINDER, SweepText.DETECTOR_STASH_FINDER);
    }

    private void avisarDeUnDetector(String nombre, SweepText consecuencia) {
        Module module = Modules.get().get(nombre);
        if (module != null && module.isActive()) return;

        Msg message = Msg.of(SweepText.DETECTOR_WARNING, "name", nombre,
            "state", module == null ? SweepText.DETECTOR_NOT_INSTALLED : SweepText.DETECTOR_OFF,
            "consequence", consecuencia);
        warning(message);
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
    private Msg chestSwapRejection() {
        ElytraFly module = Modules.get().get(ElytraFly.class);
        if (module == null || !module.isActive()) return null;

        ElytraFly.ChestSwapMode mode = module.chestSwap.get();
        if (mode == ElytraFly.ChestSwapMode.Never) return null;

        Msg consequence = mode == ElytraFly.ChestSwapMode.Always
            ? Msg.of(SweepText.CHEST_SWAP_ALWAYS, "prefix", prefijo.get())
            : Msg.of(SweepText.CHEST_SWAP_WAIT_FOR_GROUND);

        return Msg.of(SweepText.CHEST_SWAP_REJECTED, "mode", mode.toString(), "consequence", consequence);
    }

    /**
     * Deshace una preparación que ya no puede terminar en vuelo y contesta por qué. No se llama a
     * {@link #finish(SweepText, boolean)} a propósito: aquí no hay ningún barrido que dar por terminado
     * -no se ha mandado ni un {@code goal} ni un {@code elytra}-.
     */
    private Msg undoLaunch() {
        sweeping = false;
        SafetyNet.Restoration undone = restore();
        Msg pending = undone.warning(activePrefix);
        if (pending == null) return Msg.of(SweepText.UNDONE);

        loudToast(Msg.of(SweepText.TOAST_UNDONE_NOT_RESTORED), Items.BARRIER);
        return Msg.of(SweepText.UNDONE_NOT_RESTORED, "pending", pending);
    }

    /** Cancelación del jugador. Devuelve el mensaje que el comando tiene que enseñar. */
    public Msg stop() {
        if (!sweeping) return Msg.of(SweepText.STOP_NOT_SWEEPING);
        if (finish(SweepText.REASON_CANCELLED, false).arrived()) return Msg.of(SweepText.STOP_RESTORED);
        return Msg.of(SweepText.STOP_NOT_RESTORED);
    }

    public boolean isSweeping() {
        return sweeping;
    }

    /** Por dónde va el barrido: pasada, total y bloques que faltan, regreso incluido si se cuenta. */
    public Optional<Instantanea.Progreso> progreso() {
        if (!sweeping || route == null) return Optional.empty();
        return Optional.of(new Instantanea.Progreso(Math.min(index / 2 + 1, pasadasDelPlan), pasadasDelPlan,
            Math.round(bloquesRestantes())));
    }

    @Override
    public String activity() {
        return sweeping
            ? Texts.render(SweepText.NOW_LANE, "lane", Math.min(index / 2 + 1, pasadasDelPlan), "total", pasadasDelPlan)
            : Texts.render(SweepText.NOW_ARMED);
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
     * <p><b>Aquí es donde se comprueba que el terreno llegó</b>, y no darlo por hecho es lo único que
     * separa este módulo de la única mentira que no puede contar (spec §9). Antes, el barrido
     * recorría los vértices y al llegar al último anunciaba «terminado» sin haber mirado nunca si los
     * chunks del rectángulo habían llegado: con el servidor entregando con retraso, o volando más
     * rápido de lo que entrega, una fracción de cada banda no llega nunca, y el jugador lee «Barrido
     * terminado», tacha la zona y no vuelve. La autocorrección de spec §7 -relanzar replanifica sobre
     * los huecos- solo sirve si el jugador sabe que tiene que relanzar, y el único que puede saberlo
     * es el módulo.
     *
     * <p>Lo que se cuenta y cómo está en {@link SweepTally}; aquí solo se lee antes de restaurar
     * -{@link #restore()} deja el estado del barrido a cero- y se decide el tono. <b>Por debajo de
     * {@code coverage-floor} el aviso sale fuerte y con toast</b>, no en un {@code info} que además
     * se puede apagar: un barrido que cubrió la mitad no puede parecerse a uno que cubrió todo,
     * porque los dos terminan y solo uno hay que repetirlo.
     *
     * @return qué pasó de verdad con la restauración, para quien tenga que contestar algo después
     */
    private SafetyNet.Restoration finish(SweepText reason, boolean warn) {
        if (!sweeping) return SafetyNet.Restoration.ENTREGADA;
        sweeping = false;

        // Lo medido en este vuelo se guarda para la estimación previa del siguiente (spec §6), y solo
        // si hay medida: una tasa caducada o inexistente NO pisa la buena que hubiera guardada, que
        // sería cambiar un dato por un "no lo sé".
        fuel.blocksPerRocket().ifPresent(tasa -> bloquesPorCohete.set(tasa));

        // Antes de restaurar: restore() llama a resetSweep() en un finally y ahí la cuenta se tira.
        Msg cobertura = tally == null ? null : tally.summary();
        boolean seQuedoCorto = tally != null && tally.shortOfCoverage(sueloDeCobertura.get());
        int faltan = tally == null ? 0 : tally.missing();

        SafetyNet.Restoration restoration = restore();
        Msg pending = restoration.warning(activePrefix);
        warnPendingModules();

        Msg coverage = cobertura == null
            ? Msg.of(SweepText.NOTHING)
            : Msg.of(SweepText.FINISHED_COVERAGE, "summary", cobertura);
        SweepText relaunch = seQuedoCorto ? SweepText.FINISHED_RELAUNCH : SweepText.NOTHING;
        // Emitir no es llegar, y decir "entorno restaurado" sin que haya llegado nada es la mentira
        // más cara del módulo: el jugador cree que ha aterrizado y Baritone sigue volando.
        Msg message = pending == null
            ? Msg.of(SweepText.FINISHED, "reason", reason, "coverage", coverage, "relaunch", relaunch)
            : Msg.of(SweepText.FINISHED_NOT_RESTORED, "reason", reason, "pending", pending, "coverage", coverage,
                "relaunch", relaunch);

        if (pending != null) {
            warning(message);
            loudToast(Msg.of(SweepText.TOAST_FINISHED_NOT_RESTORED), Items.BARRIER);
            return restoration;
        }
        if (seQuedoCorto) {
            warning(message);
            loudToast(Msg.of(SweepText.TOAST_SHORT_OF_COVERAGE, "missing", faltan), Items.BARRIER);
            return restoration;
        }
        if (warn) warning(message);
        else if (avisos.get()) info(message);
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
        Object names;
        if (elytraFly.hasPending() && elytraReplace.hasPending()) {
            names = Msg.of(SweepText.MODULES_BOTH, "first", elytraFly.name(), "second", elytraReplace.name());
        }
        else if (elytraFly.hasPending()) names = elytraFly.name();
        else if (elytraReplace.hasPending()) names = elytraReplace.name();
        else return;

        Msg message = Msg.of(SweepText.MODULES_LEFT_AS_IN_FLIGHT, "modules", names);
        warning(message);
        loudToast(message, Items.ELYTRA);
    }

    private void resetSweep() {
        sweeping = false;
        route = null;
        tally = null;
        index = 0;
        pasadasDelPlan = 0;
        bloquesAlDespegar = odometro.blocksFlown();
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
    private void loudToast(Msg message, Item icon) {
        MeteorToast.Builder toast = new MeteorToast.Builder("Xploits").text(Texts.render(message)).icon(icon);
        // MeteorToast.update() llama a play(customSound) sin comprobar el nulo y vanilla lo
        // dereferencia: NPE en el hilo de render. Nunca pasar null; se silencia con volumen cero.
        if (!sonidoEnAvisos.get()) {
            toast.sound(PositionedSoundInstance.master(SoundEvents.BLOCK_NOTE_BLOCK_CHIME.value(), 1.2f, 0f));
        }
        mc.getToastManager().add(toast.build());
    }

    public Msg status() {
        if (!isActive()) return Msg.of(SweepText.STATUS_OFF);

        if (!sweeping) {
            return Msg.of(SweepText.STATUS_IDLE, "width", descripcionDeLaAnchura(),
                "rate", bloquesPorCohete.get() > 0
                    ? Msg.of(SweepText.STATUS_RATE, "rate", Math.round(bloquesPorCohete.get()))
                    : Msg.of(SweepText.STATUS_NO_RATE));
        }

        Msg coverage = tally == null
            ? Msg.of(SweepText.NOTHING)
            : Msg.of(SweepText.STATUS_COVERAGE, "summary", tally.summary());
        Msg left = mc.player == null
            ? Msg.of(SweepText.NOTHING)
            : Msg.of(SweepText.STATUS_LEFT, "blocks", Math.round(bloquesRestantes()),
                "return", contarElRegreso.get() ? SweepText.STATUS_WITH_RETURN : SweepText.STATUS_WITHOUT_RETURN);
        OptionalDouble tasa = fuel.blocksPerRocket();
        Msg rate = tasa.isPresent()
            ? Msg.of(SweepText.STATUS_RATE, "rate", Math.round(tasa.getAsDouble()))
            : Msg.of(SweepText.STATUS_NO_FLIGHT_RATE, "blocks", Math.round(bloquesSinProyeccion));
        return Msg.of(SweepText.STATUS_SWEEPING, "lane", Math.min(index / 2 + 1, pasadasDelPlan),
            "total", pasadasDelPlan, "width", anchuraUsada,
            "how", anchuraTecleada ? SweepText.WIDTH_TYPED : SweepText.WIDTH_MEASURED,
            "coverage", coverage, "flown", Math.round(bloquesVolados()), "left", left, "rate", rate,
            "net", netArmed ? SweepText.NET_ARMED : SweepText.NET_DISARMED,
            "caught", netCaughtWarned ? SweepText.STATUS_NET_CAUGHT : SweepText.NOTHING);
    }

    private Msg descripcionDeLaAnchura() {
        if (anchuraDePasada.get() > 0) {
            return Msg.of(SweepText.WIDTH_TYPED_DESC, "width", anchuraDePasada.get());
        }
        if (!probe.hasEnoughSamples()) {
            return Msg.of(SweepText.WIDTH_MEASURING, "samples", probe.sampleCount(),
                "needed", WidthProbe.MUESTRAS_MINIMAS, "discarded", descartadas());
        }
        return Msg.of(SweepText.WIDTH_MEASURED_DESC, "width", probe.laneWidthInChunks(margenDeAnchura.get()),
            "radius", probe.observedRadiusInChunks(), "ceiling", ultimoTecho, "margin", margenDeAnchura.get(),
            "discarded", descartadas());
    }

    /**
     * La coletilla de las muestras descartadas. Se enseña porque es la explicación de por qué la
     * medida es la que es, y <b>los dos descartes significan cosas distintas</b>: uno es un servidor
     * con retraso y el otro es que el jugador está volando, que se arregla parando. Sin verlos, lo
     * único que se ve es que la anchura no sale.
     */
    private Msg descartadas() {
        Msg tarde = probe.discardedSamples() > 0
            ? Msg.of(SweepText.DISCARDED_LATE, "count", probe.discardedSamples())
            : Msg.of(SweepText.NOTHING);
        Msg enMovimiento = probe.movingSamples() > 0
            ? Msg.of(SweepText.DISCARDED_MOVING, "count", probe.movingSamples())
            : Msg.of(SweepText.NOTHING);
        return Msg.of(SweepText.DISCARDED, "late", tarde, "moving", enMovimiento);
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

        Msg resumen() {
            if (servidorDesconocido) return Msg.of(SweepText.READING_UNKNOWN_SERVER);
            if (leidos == 0 && rotos == 0) return Msg.of(SweepText.READING_NONE);
            return rotos == 0
                ? Msg.of(SweepText.READING_FILES, "read", leidos, "total", FICHEROS_DE_COBERTURA.length)
                : Msg.of(SweepText.READING_FILES_BROKEN, "read", leidos, "total", FICHEROS_DE_COBERTURA.length,
                    "broken", rotos);
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
                warning(SweepText.UNREADABLE_FILE, "file", fichero, "error", e.getClass().getSimpleName());
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
