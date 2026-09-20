package com.xploits.travel;

import com.xploits.XploitsAddon;
import com.xploits.elytra.ElytraReplace;
import com.xploits.travel.core.Axis;
import com.xploits.travel.core.BaritoneScript;
import com.xploits.travel.core.BorrowedModule;
import com.xploits.travel.core.Destination;
import com.xploits.travel.core.FireworkWatch;
import com.xploits.travel.core.FlightPattern;
import com.xploits.travel.core.PatternParams;
import com.xploits.travel.core.Route;
import com.xploits.travel.core.RoutePlanner;
import com.xploits.travel.core.SafetyNet;
import com.xploits.travel.core.StallWatch;
import com.xploits.travel.core.Waypoint;
import meteordevelopment.meteorclient.MeteorClient;
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
import meteordevelopment.orbit.EventPriority;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
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
 *
 * <p>Esa red vive en {@link ChatNet}, <b>suscrito al bus por su cuenta</b> y no como parte del
 * módulo, porque Meteor desuscribe el módulo justo antes de {@code onDeactivate()} -que es cuando la
 * restauración emite sus seis comandos-. Y la decisión de qué texto es comando nuestro está en
 * {@link SafetyNet}, en el núcleo y con tests.
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
        .description("Lo más que el patrón puede apartarse del eje en modo autopista (spec §4.2). Con ESPIRAL "
            + "necesita al menos 338: una espiral más estrecha deja los pasos tan juntos que Baritone aterrizaría "
            + "en casi todos, y el módulo se niega a volar en vez de dejarte creer que giras.")
        .defaultValue(300)
        .min(0)
        .sliderRange(0, 2_000)
        .decimalPlaces(0)
        .visible(() -> destinationMode.get() == DestinationMode.AUTOPISTA)
        .build()
    );

    private final Setting<Double> waypointMargin = sgDestination.add(new DoubleSetting.Builder()
        .name("waypoint-margin")
        .description("Cuántos bloques antes de un waypoint intermedio se le cambia el objetivo a Baritone, para "
            + "que no le dé tiempo a aterrizar en él. Cuanto más alto, más redondea las esquinas del patrón.")
        .defaultValue(RoutePlanner.DEFAULT_WAYPOINT_MARGIN)
        .min(RoutePlanner.MIN_WAYPOINT_MARGIN)
        .sliderRange(RoutePlanner.MIN_WAYPOINT_MARGIN, 500)
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

    // Vuelo: el prefijo y los cuatro ajustes de Baritone, cada uno con su valor de vuelo y su valor
    // de reposo. Los dos módulos de Meteor NO tienen ajuste de reposo: su estado se lee y se anota al
    // despegar (ver BorrowedModule).
    //
    // Los cuatro valores de reposo vienen de fábrica con el default REAL de Baritone 1.17.0, leído
    // del bytecode de su clase Settings (baritone/e.class, javap -p -c) en el jar instalado:
    // elytraAutoJump FALSE, elytraAllowEmergencyLand TRUE, elytraConserveFireworks FALSE y
    // elytraFireworkSpeed 1.2. Poner otro valor aquí no es "dejarlo como estaba": Baritone persiste
    // sus ajustes a disco, así que el primer viaje reconfiguraría para siempre todos los #elytra que
    // el jugador haga a mano.

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
            + "Baritone se pueden escribir pero no leer (spec §6.1). De fábrica Baritone lo trae en false.")
        .defaultValue(BaritoneScript.baritoneDefaults().autoJump())
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
        .description("A qué valor se devuelve elytraAllowEmergencyLand al aterrizar. De fábrica Baritone lo "
            + "trae en true.")
        .defaultValue(BaritoneScript.baritoneDefaults().allowEmergencyLand())
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
        .description("A qué valor se devuelve elytraConserveFireworks al aterrizar. De fábrica Baritone lo trae "
            + "en false: ponlo en true solo si tú lo tenías así, porque Baritone lo guarda en disco y todos tus "
            + "vuelos a mano con #elytra irían más lentos a partir del primer viaje.")
        .defaultValue(BaritoneScript.baritoneDefaults().conserveFireworks())
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
        .description("A qué valor se devuelve elytraFireworkSpeed al aterrizar. De fábrica Baritone lo trae en "
            + "1.2: ponlo en otra cosa solo si tú lo tenías así, porque Baritone lo guarda en disco y todos tus "
            + "vuelos a mano con #elytra se quedarían con ese valor a partir del primer viaje.")
        .defaultValue(BaritoneScript.baritoneDefaults().fireworkSpeed())
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
     * Si lo que está pasando ahora mismo es el desmontaje de salida del mundo, en el que <b>no se
     * puede encender ni apagar un módulo de Meteor</b> (spec §6.4).
     *
     * <p>Lo pone {@link #onGameLeft(GameLeftEvent)} y lo quita {@link #onActivate()}, que es por
     * donde vuelve el módulo al entrar: {@code Modules.onGameJoined} suscribe cada módulo activo y le
     * llama a {@code onActivate()}.
     *
     * <p><b>Por qué se puede confiar en que el handler llega antes.</b> Verificado en las fuentes de
     * orbit 0.2.4: {@code EventBus.insert()} recorre los oyentes ya suscritos y mete el nuevo delante
     * del primero cuya prioridad sea <b>estrictamente menor</b>. El handler de {@code Modules} -el
     * que desmonta- no declara prioridad, así que es {@code EventPriority.MEDIUM} (0); el nuestro es
     * {@code HIGHEST} (200), así que queda siempre por delante, se suscriba quien se suscriba
     * primero. Y {@code EventBus.post()} recorre esa lista en orden.
     */
    private boolean leavingWorld;

    /**
     * Los dos módulos de Meteor que el viaje toma prestados (spec §6.2, paso 5). La decisión de
     * qué hacerles al despegar y al aterrizar está en {@link BorrowedModule}, en el núcleo y con
     * tests: aquí solo se leen los {@code isActive()} y se ejecuta lo que conteste.
     *
     * <p>No hay ajuste de reposo para ninguno de los dos, y esa es la diferencia con los cuatro
     * ajustes de Baritone: el argumento de spec §6.1 -no se pueden leer, así que hay que declarar a
     * qué se vuelve- vale para Baritone y no vale aquí, porque {@code Module.isActive()} se lee.
     */
    private final BorrowedModule elytraFly = new BorrowedModule("elytra-fly", false);
    private final BorrowedModule elytraReplace = new BorrowedModule("elytra-replace", true);

    /**
     * El oyente de la red de seguridad (spec §7), <b>suscrito al bus por su cuenta</b> y no como
     * parte del módulo. Esto no es un capricho de diseño: es el arreglo de un agujero verificado en
     * las fuentes de Meteor.
     *
     * <p>{@code Module.toggle()} desuscribe el módulo del bus <b>antes</b> de llamar a {@code
     * onDeactivate()}, y {@code Modules.onGameLeft} hace exactamente lo mismo. Si la red viviera en
     * un {@code @EventHandler} del módulo, la restauración -seis comandos con prefijo, {@code
     * cancel} incluido- se emitiría con el módulo ya desuscrito: la red no correría, nada se
     * cancelaría, y las seis líneas saldrían al chat público justo en las dos salidas en las que el
     * jugador sigue conectado y los paquetes salen de verdad. Apagar el módulo es la reacción de
     * pánico natural, y es lo que hace un bind.
     *
     * <p>Un objeto suelto no depende de nada de eso: se suscribe al armar y se desuscribe en {@link
     * #disarmNet()}, después del último comando. Verificado contra las fuentes de orbit 0.2.4:
     * {@code EventBus.subscribe(Object)} reflexiona sobre los métodos anotados de la clase del
     * objeto y construye el oyente con la fábrica de lambdas del paquete del addon -{@code
     * com.xploits}, que {@code MeteorClient} registra por {@code MeteorAddon.getPackage()}-, así que
     * una clase nuestra cualquiera sirve. Y es la <b>misma instancia</b> siempre, porque
     * {@code EventBus} cachea los oyentes por identidad del objeto y {@code unsubscribe} necesita
     * encontrar ahí los mismos.
     */
    private final ChatNet net = new ChatNet();

    /**
     * Si la red de seguridad está armada, que ahora es exactamente lo mismo que decir si {@link
     * #net} está suscrito al bus. El módulo puede estar encendido sin viaje en marcha, y entonces no
     * tiene por qué comerse los comandos que el jugador escriba a mano: la red se arma antes de
     * emitir el primer comando y se desarma cuando ya no queda ninguno por emitir.
     */
    private boolean netArmed;

    /** Si el comando que {@link #send(String)} está emitiendo ahora mismo es nuestro. */
    private boolean emitting;

    /** Si la red mató el último comando nuestro: puesto por el oyente, leído por {@link #send(String)}. */
    private boolean sendCaught;

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
            "Prepara el entorno, lanza el vuelo con elytra de Baritone por una ruta con patrón de despiste, y lo restaura todo al aterrizar. Encenderlo no vuela: el viaje se lanza con .xploits travel go.");
    }

    @Override
    public void onActivate() {
        // Encender no vuela: se espera al comando del jugador.
        //
        // Aquí se vuelve también al entrar al mundo -Modules.onGameJoined suscribe cada módulo activo
        // y le llama a onActivate()-, así que es el sitio donde se olvida que estábamos saliendo. Lo
        // que quedó pendiente de devolver NO se aplica aquí: estamos dentro del reparto de
        // GameJoinedEvent, que es justo cuando Modules está suscribiendo módulos, y encender uno en
        // mitad de eso es el mismo agujero por el que se llega aquí. Se aplica en el primer tick.
        leavingWorld = false;
        resetTrip();

        // Encender este módulo no hace nada visible, y un módulo que al encenderse no hace nada ni
        // lo dice es indistinguible de uno roto: es exactamente la conclusión a la que llegó el
        // jugador la primera vez. Los otros cinco del addon se callan porque empiezan a trabajar
        // solos; éste es el único que espera una segunda orden, así que es el único que tiene que
        // decirlo. Se repite al entrar al mundo a propósito: si el módulo sigue encendido, saber
        // que está armado y con qué destino vale más que ahorrar una línea de chat.
        info("Armado, pero no vuela solo: lanza el viaje con .xploits travel go");
        info("Patrón %s · destino %s", pattern.get(), describeDestination());
    }

    @Override
    public void onDeactivate() {
        // Salida 5: apagado del módulo. Al dejar el mundo también se pasa por aquí -Modules llama a
        // onDeactivate() de todos los módulos activos-, pero para entonces onGameLeft ya ha cerrado
        // el viaje: corre antes por prioridad (spec §6.4), y finish() es idempotente, así que este
        // segundo paso no restaura nada. Eso es justo lo que hace falta, porque aquí ya no se puede
        // distinguir un apagado normal del desmontaje.
        finish("auto-travel se ha apagado", false);
        // Y pase lo que pase, la red no sobrevive al módulo: un oyente suscrito sin viaje en marcha
        // se comería en silencio todo comando con prefijo que el jugador escribiera a mano, y con el
        // módulo apagado no habría ni quién lo desarmara ni quién lo dijera. Es idempotente.
        disarmNet();
    }

    /**
     * Salida 4: desconexión o cambio de mundo. Va en {@code HIGHEST} a propósito, y no es una
     * preferencia de orden: es lo que hace que la restauración sepa que está en el desmontaje.
     *
     * <p>{@code Modules.onGameLeft} recorre los módulos activos haciendo {@code unsubscribe(module);
     * module.onDeactivate();} y <b>no</b> pone {@code active = false}, para que vuelvan solos en la
     * siguiente entrada. Nuestro {@code onDeactivate()} restaura, y restaurar encendía {@code
     * elytra-fly}: {@code Module.toggle()} lo suscribe al bus en mitad del desmontaje y, si a él ya
     * le tocó el bucle, termina suscrito y activo. En la siguiente entrada {@code
     * Modules.onGameJoined} lo suscribe otra vez y {@code EventBus.insert()} de orbit no deduplica,
     * así que sus handlers corrían dos veces por evento el resto de la sesión -y apagarlo no lo
     * arreglaba: {@code unsubscribe} usa {@code List.remove}, que quita una sola copia-.
     *
     * <p>Con prioridad {@code HIGHEST} este handler corre antes que el de {@code Modules}: marca que
     * estamos saliendo y termina el viaje él, con los comandos de Baritone -que sí se pueden mandar,
     * porque {@code GameLeftEvent} se publica con el mundo y el jugador todavía vivos- y sin tocar
     * ningún módulo. El {@code onDeactivate()} que llega después se encuentra el viaje ya cerrado.
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    private void onGameLeft(GameLeftEvent event) {
        leavingWorld = true;
        finish("se ha dejado el mundo", false);
    }

    /**
     * La mitad de la red de seguridad (spec §7) que necesita a Minecraft: sacar del paquete saliente
     * por qué canal va y qué texto lleva. Decidir si ese texto es un comando de Baritone de los que
     * dirigimos es de {@link SafetyNet}, que se prueba sin arrancar el juego.
     *
     * <p>{@code ClientConnectionMixin} de Meteor publica este evento al entrar en {@code
     * ClientConnection.send} y cancela el envío si el evento se cancela, así que esto no es una
     * advertencia: el paquete muere dentro del cliente.
     *
     * <p>Se cancelan también <b>nuestros propios comandos</b>, y eso es deliberado: si la red tiene
     * que actuar es porque Baritone no está interceptando, y entonces nuestro comando tampoco tiene
     * a quién llegar. Lo que no se puede hacer es dar la restauración por buena después, y para eso
     * está {@link #sendCaught}.
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
        // Lo que quedó por devolver al salir del mundo se devuelve aquí, en el primer tick tras
        // volver a entrar: para entonces Modules.onGameJoined ya ha terminado de suscribir a todo el
        // mundo, así que encender un módulo lo suscribe una sola vez.
        applyPendingModules();

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

        // El margen con el que se da por alcanzado un waypoint no es el mismo para todos -el último
        // es el único sitio donde Baritone debe aterrizar-, y esa decisión vive en el núcleo con
        // tests: ver RoutePlanner.reachedMargin.
        if (distance <= RoutePlanner.reachedMargin(index, waypoints.size(), waypointMargin.get())) {
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
        if (!mc.player.isAlive()) {
            // Sin esto, desde la pantalla de muerte pasan todas las demás guardas: se arma la red, se
            // emiten los diez comandos de la preparación, y al tick siguiente onTick ve al muerto y
            // emite los seis de la restauración. Catorce comandos y un "Viaje lanzado" para nada.
            return "Estás muerto: reaparece antes de lanzar un viaje, que desde la pantalla de muerte no se vuela.";
        }
        if (!FabricLoader.getInstance().isModLoaded(BARITONE_MOD_ID)) {
            // A propósito NO se usa BaritoneUtils.IS_AVAILABLE: Meteor lo pone a true tras un
            // Class.forName("baritone.api.BaritoneAPI") sobre una clase que el jar ofuscado no
            // expone, así que ahí vale false aunque Baritone esté perfectamente instalado (spec §2).
            return "Baritone no está cargado: este módulo vuela con sus comandos y sin él no hay nada que dirigir.";
        }
        if (InvUtils.find(Items.FIREWORK_ROCKET).count() == 0) {
            // Y además es lo que hace honesto al aviso de checkFireworks(): despegando siempre con
            // alguno, "te has quedado SIN fuegos a mitad de vuelo" solo puede decirse cuando de
            // verdad se han acabado a mitad de vuelo.
            return "No llevas ningún fuego artificial: Baritone se impulsa con ellos y sin ninguno no despega. "
                + "No se lanza nada. Los que vayan dentro de shulkers no cuentan: sácalos antes.";
        }
        if (!wearsElytra()) {
            // elytra-replace no tapa esto: su política contesta NOT_WEARING cuando la pechera no lleva
            // elytra y no hace nada, a propósito -ponerte una elytra por tu cuenta no es su trabajo-.
            return "No llevas elytra puesta: Baritone vuela con ella, y elytra-replace cambia la que lleves pero "
                + "no te pone ninguna. Ponte una antes de lanzar.";
        }
        String chestSwapRejection = chestSwapRejection();
        if (chestSwapRejection != null) return chestSwapRejection;

        String launchPrefix = prefix.get();
        String prefixRejection = SafetyNet.prefixRejection(launchPrefix);
        if (prefixRejection != null) {
            // Se comprueba aquí, antes de armar la red y antes del primer comando: armarla sobre un
            // prefijo inservible es tener red sin saber qué vigila.
            return "No se vuela: " + prefixRejection + ".";
        }

        Waypoint origin = new Waypoint(mc.player.getX(), mc.player.getZ());
        Route route = RoutePlanner.plan(origin, destination(), pattern.get(), params(), highwayMaxAmplitude.get(),
            waypointMargin.get());
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

        // Y una última comprobación antes de mandar el #elytra, porque preparar mueve armadura: apagar
        // elytra-fly con chest-swap en Always te pone la pechera en el sitio de la elytra. Eso se
        // rechaza arriba, antes de tocar nada, así que aquí ya no debería poder pasar; esto es la red
        // por si algún otro módulo se lleva la elytra entre una línea y la siguiente. Lanzar ahora
        // sería decir "Viaje lanzado" y enterarse a los treinta segundos por el corte de atasco.
        if (!wearsElytra()) return undoLaunch();

        aimAtCurrentWaypoint();

        Waypoint target = waypoints.get(waypoints.size() - 1);
        return String.format("Viaje lanzado con patrón %s: %d waypoints hasta %d, %d, a %d bloques en línea recta.",
            pattern.get(), waypoints.size(), Math.round(target.x()), Math.round(target.z()),
            Math.round(origin.distanceTo(target)));
    }

    /** Si la pechera lleva una elytra puesta, que es lo único con lo que Baritone puede volar. */
    private boolean wearsElytra() {
        return mc.player != null && mc.player.getEquippedStack(EquipmentSlot.CHEST).getItem() == Items.ELYTRA;
    }

    /**
     * El motivo por el que no se puede lanzar con el {@code chest-swap} de {@code elytra-fly}
     * configurado, o {@code null} si no hay conflicto.
     *
     * <p>Verificado en las fuentes de {@code meteor-client:1.21.11-SNAPSHOT}: {@code
     * ElytraFly.onDeactivate()} llama a {@code ChestSwap.swap()} si {@code chest-swap} está en {@code
     * Always} y llevas la elytra puesta -te cambia a la pechera-, y si está en {@code WaitForGround}
     * suscribe un oyente que hace ese mismo cambio <b>en cuanto toques suelo</b>. La preparación
     * apaga {@code elytra-fly} como primer paso y dos líneas después manda {@code #elytra}: con
     * {@code Always} Baritone no puede volar porque ya no llevas elytra, y con {@code WaitForGround}
     * te la quita en el aterrizaje, cuando el módulo cree haberlo restaurado todo.
     *
     * <p>Se rechaza en vez de acotarse, y se rechaza <b>antes</b> de armar la red y de mandar un solo
     * comando (spec §9.2). Acotarlo significaría poner {@code chest-swap} en {@code Never} a espaldas
     * del jugador y devolverlo después, que es un ajuste más que Meteor persiste a disco y que se
     * perdería con el cliente si la sesión se corta: exactamente la clase de rastro que este módulo
     * existe para no dejar.
     *
     * <p>De fábrica {@code chest-swap} es {@code Never}, así que esto solo le pasa a quien lo haya
     * configurado -que es justo el perfil que usa este módulo-.
     */
    private String chestSwapRejection() {
        ElytraFly module = Modules.get().get(ElytraFly.class);
        // Si no está encendido, la preparación no lo apaga, y sin apagarlo no hay cambio de armadura.
        if (module == null || !module.isActive()) return null;

        ElytraFly.ChestSwapMode mode = module.chestSwap.get();
        if (mode == ElytraFly.ChestSwapMode.Never) return null;

        String consequence = mode == ElytraFly.ChestSwapMode.Always
            ? "te pone la pechera en el sitio de la elytra en ese mismo instante, y el \"" + prefix.get()
                + "elytra\" sale dos líneas después: Baritone no despegaría, y te enterarías treinta segundos "
                + "más tarde por el corte de atasco"
            : "deja armado un oyente que te quita la elytra en cuanto toques suelo, que es justo el aterrizaje "
                + "de Baritone: te la quitaría cuando el módulo cree haberlo restaurado todo";

        return "No se vuela: elytra-fly está encendido con chest-swap en " + mode + ", y la preparación tiene "
            + "que apagarlo porque Baritone declara que su vuelo no funciona con impulso no vanilla. Apagarlo "
            + consequence + ". Pon chest-swap en Never dentro de elytra-fly, o apaga elytra-fly a mano antes "
            + "de lanzar.";
    }

    /**
     * Deshace una preparación que ya no puede terminar en vuelo y contesta por qué. No se llama a
     * {@link #finish(String, boolean)} a propósito: aquí no hay ningún viaje que dar por terminado
     * -no se ha mandado ni un {@code goal} ni un {@code elytra}-, y decir "viaje terminado" por algo
     * que no llegó a empezar es la misma confusión que este módulo evita en la restauración.
     */
    private String undoLaunch() {
        // No hace falta mirar si algún módulo se ha quedado pendiente: esto solo se llega a ejecutar
        // desde start(), con el mundo cargado, que es exactamente cuando sí se pueden tocar.
        travelling = false;
        SafetyNet.Restoration undone = restore();
        String pending = undone.warning(activePrefix);

        String why = "No se vuela: preparar el entorno te ha dejado sin elytra puesta, así que Baritone no "
            + "podría despegar. He deshecho la preparación";
        if (pending == null) return why + " y el entorno ha quedado como estaba.";

        loudToast("La preparación se ha deshecho pero no ha llegado a Baritone: sus ajustes se han quedado en "
            + "valores de vuelo. Lee el chat.", Items.BARRIER);
        return why + ", pero " + pending + ".";
    }

    /** Salida 2: cancelación del jugador. Devuelve el mensaje que el comando tiene que enseñar. */
    public String stop() {
        if (!travelling) return "No hay ningún viaje en marcha.";
        // Lo que conteste el comando no puede afirmar más que lo que acaba de pasar: si la
        // restauración no llegó, finish() ya lo ha dicho entero y aquí solo se remata sin repetirlo.
        if (finish("lo has cancelado", false).arrived()) return "Viaje cortado y entorno restaurado.";
        return "Viaje cortado, pero el entorno NO ha quedado restaurado: lee el aviso de arriba.";
    }

    public boolean isTravelling() {
        return travelling;
    }

    /**
     * Preparación de spec §6.2, pasos 5 y 6: los dos módulos y, de una pieza, la secuencia de
     * ajustes de Baritone. Los siete {@code #set} salen juntos a propósito -{@code elytraAutoSwap}
     * incluido-: es la secuencia que el núcleo construye y prueba como una sola cosa, y partirla
     * para meter el encendido de un módulo nuestro en medio no cambia nada observable.
     */
    private void prepare() {
        // Antes de anotar nada, devolver lo que quedara pendiente de un viaje anterior: si no, lo que
        // se anotaría como "reposo del jugador" sería el estado que dejó ese viaje, no el suyo.
        applyPendingModules();
        takeModule(Modules.get().get(ElytraFly.class), elytraFly);
        takeModule(Modules.get().get(ElytraReplace.class), elytraReplace);
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
     *
     * @return qué pasó de verdad con la restauración, para quien tenga que contestar algo después.
     *         Quien llega segundo no restaura nada y contesta {@code ENTREGADA}: el primero ya dijo
     *         lo que hubiera que decir, y repetirlo sería sacar dos veces el mismo aviso.
     */
    private SafetyNet.Restoration finish(String reason, boolean warn) {
        if (!travelling) return SafetyNet.Restoration.ENTREGADA;
        travelling = false;

        SafetyNet.Restoration restoration = restore();
        String pending = restoration.warning(activePrefix);
        warnPendingModules();

        if (pending == null) {
            String message = "Viaje terminado: " + reason + ". Entorno restaurado.";
            if (warn) warning("%s", message);
            else if (notify.get()) info("%s", message);
            return restoration;
        }

        // Emitir no es llegar, y decir "entorno restaurado" sin que haya llegado nada es la mentira
        // más cara del módulo: el jugador cree que ha aterrizado y Baritone sigue volando. Sale
        // siempre, se hayan pedido avisos o no, y fuerte: es el peor estado en que este módulo te
        // puede dejar.
        warning("%s", "Viaje terminado: " + reason + ", pero el entorno NO ha quedado restaurado: " + pending + ".");
        loudToast("El viaje ha terminado pero la restauración no ha llegado a Baritone: puede seguir volando y "
            + "sus ajustes se han quedado en valores de vuelo. Lee el chat.", Items.BARRIER);
        return restoration;
    }

    /**
     * Devuelve los cinco puntos que se tocaron a su estado de reposo (spec §6.3) y dice si de verdad
     * llegaron. La red se desarma la última, cuando ya no queda ni un comando por emitir: desarmarla
     * antes dejaría el {@code cancel} y la restauración sin cubrir, que es exactamente cuando más
     * comandos se mandan de golpe. Y va en un {@code finally} porque una red armada que sobreviviera
     * a una excepción se comería en silencio todo comando que el jugador escribiera a mano.
     */
    private SafetyNet.Restoration restore() {
        try {
            SafetyNet.Restoration outcome;
            if (mc.player == null) {
                outcome = SafetyNet.Restoration.SIN_JUGADOR;
            }
            else {
                // El && va detrás a propósito: primero se manda, siempre, y luego se acumula. Con la
                // condición delante, el primer comando cancelado se llevaría por delante los cinco
                // siguientes.
                boolean delivered = send(BaritoneScript.cancel(activePrefix));
                for (String command : BaritoneScript.restoration(activePrefix, restingSettings())) {
                    delivered = send(command) && delivered;
                }
                outcome = delivered ? SafetyNet.Restoration.ENTREGADA : SafetyNet.Restoration.CANCELADA;
            }

            // Los dos módulos son nuestros y no viajan por el chat: se devuelven haya jugador o no,
            // y lo que les pase no cambia el veredicto de los comandos. Lo único que los frena es el
            // desmontaje de salida del mundo (spec §6.4): ahí la devolución se queda pendiente y la
            // hace el primer tick de la siguiente entrada.
            releaseModule(Modules.get().get(ElytraFly.class), elytraFly);
            releaseModule(Modules.get().get(ElytraReplace.class), elytraReplace);

            return outcome;
        }
        finally {
            disarmNet();
            resetTrip();
        }
    }

    /**
     * El aviso de los módulos que no se han podido devolver todavía (spec §6.4). Sale fuerte y con
     * toast, no como línea de chat corriente, por dos motivos: <i>"aterrizar con ElytraFly apagado
     * sin saberlo es tan malo como el problema original"</i> (spec §6.3), y porque el único momento
     * en que esto pasa es al salir del mundo, donde el chat se va con la desconexión y lo único que
     * el jugador llega a leer es el toast.
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
            + "suscrito dos veces al bus para el resto de la sesión, así que " + names + " se queda como estaba "
            + "en vuelo. Se devuelve solo en el primer tick tras volver a entrar; si cierras el cliente antes, "
            + "repásalo en la ClickGUI.";
        warning("%s", message);
        loudToast(message, Items.ELYTRA);
    }

    private void resetTrip() {
        travelling = false;
        waypoints = List.of();
        index = 0;
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
            // Ni se anota lo que no se puede leer ni se devuelve lo que no se tomó.
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
     * nada pendiente no consulta ni el registro de módulos, que es lo que permite llamarlo en cada
     * tick.
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
     * Manda un comando por el chat del jugador. Camino verificado contra las fuentes de
     * {@code meteor-client:1.21.11-SNAPSHOT}: {@code ChatUtils.sendPlayerMsg} manda el texto "como si
     * el usuario lo hubiera escrito en el chat", que es exactamente lo que Baritone escucha. Se pasa
     * {@code addToHistory = false} para no llenar el historial del chat de comandos con almohadilla,
     * donde la tecla arriba los dejaría a un intro de publicarse.
     *
     * <p>Sin jugador no se manda nada: {@code sendPlayerMsg} lo dereferencia sin comprobarlo.
     *
     * @return si el comando salió del cliente hacia Baritone. {@code false} significa que la red
     *         tuvo que cancelarlo -Baritone no lo interceptó, así que no tenía a quién llegar- o que
     *         no había jugador. Todo el camino es síncrono: {@code sendPlayerMsg} acaba en {@code
     *         ClientConnection.send}, donde el mixin publica el evento y nuestro oyente contesta
     *         antes de que esta llamada vuelva, así que {@link #sendCaught} ya está decidido aquí.
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
