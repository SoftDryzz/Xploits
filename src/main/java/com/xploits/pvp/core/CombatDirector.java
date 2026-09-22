package com.xploits.pvp.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * El criterio de combate (spec §4, rediseñado en {@code 2026-09-22-autopvp-decide-bien}). Decide
 * dos cosas ortogonales a partir del snapshot y devuelve la <b>unión</b> de lo que pide cada una,
 * filtrada por lo que el jugador lleva encima:
 *
 * <ul>
 *   <li>la <b>fase ofensiva</b>, derivada del objetivo (§4), y</li>
 *   <li>la <b>postura defensiva</b>, derivada de ti ({@link DefensivePolicy}, §5).</li>
 * </ul>
 *
 * <p>El error que esto corrige es haberlas metido en un único {@code enum}: "me están cristaleando"
 * y "él está rodeado" son verdaderas a la vez, y un enum obliga a elegir entre atacar y defenderte.
 *
 * <p>No sabe nada de encender ni apagar: eso es del adaptador, que además lleva la cuenta de qué
 * módulos tomó él ({@link ModuleLedger}).
 */
public final class CombatDirector {
    /**
     * Distancia máxima a la que un objetivo cuenta para clasificar (§9: de 16 a 10). Ningún módulo
     * dirigido pasa de 10 -{@code AutoWeb} y {@code CrystalAura} son los más largos y ahí se
     * quedan-, así que la franja 10-16 no servía para nada: producía fases con nombre y sin
     * módulos, y un objetivo "en combate" al que no se le podía hacer nada.
     */
    public static final double CLASSIFY_TARGET_RANGE = 10.0;

    /**
     * Distancia a la que los cristales le entran de verdad (§4.1). No es el {@code target-range} de
     * {@code CrystalAura} (10), que es solo a quién mira, sino el alcance al que el aura coloca y
     * rompe: a menos de esto, alguien con la elytra desplegada es una pelea normal y no una
     * persecución.
     */
    public static final double CRYSTAL_RANGE = 5.5;

    /**
     * {@code target-range} de fábrica de {@code AutoAnvil} (§2). {@code ENTERRADO} lo exige (§4.1):
     * un enterrado a 12 no es una fase, es un obstáculo -lo que toca es acercarse, no apagar el
     * aura y plantarse-.
     */
    public static final double AUTO_ANVIL_TARGET_RANGE = 4.0;

    /**
     * {@code target-range} de fábrica de {@code AutoTrap} (§2). Es la puerta por la que
     * {@code auto-trap} entra en {@code SUPERFICIE} (§4.2) y en {@code ENTERRADO}: encender algo que
     * no llega es el mismo fallo silencioso que ya se corrigió por inventario (§10).
     */
    public static final double AUTO_TRAP_TARGET_RANGE = 3.0;

    /**
     * Distancia a partir de la cual {@code auto-web} se enciende en {@code SUPERFICIE} aunque el
     * objetivo no se esté yendo (§4.3). Por debajo, la telaraña le roba al aura su mejor posición de
     * cristal a cambio de algo que el otro rompe a mano en medio segundo, así que solo se enciende
     * si además se aleja de forma sostenida ({@link RetreatWatch}).
     */
    public static final double AUTO_WEB_MIN_DISTANCE = 3.0;

    /**
     * Media anchura de la banda de distancia entre {@code SUPERFICIE} y {@code ACERCAMIENTO} (§6):
     * se entra en {@code ACERCAMIENTO} por encima de {@code approach + 1} y se vuelve a
     * {@code SUPERFICIE} en {@code approach - 1}. La histéresis de estas dos fases es de distancia,
     * no de tiempo: a velocidad de sprint (5,6 b/s, 0,28 bloques por tick) cruzar los dos bloques de
     * la banda lleva siete ticks, que es la holgura que hace falta, y no cuesta el primer combo como
     * costaría esperar ese tiempo con el objetivo ya dentro.
     */
    public static final double APPROACH_BAND = 1.0;

    /**
     * Ticks seguidos sin objetivo antes de caer a {@code SIN_COMBATE} (§6). Medio segundo.
     *
     * <p>"No hay nadie este tick" y "se acabó la pelea" no son lo mismo: el objetivo desaparece un
     * instante por un tirón del servidor, porque se mete detrás de un bloque o porque el módulo que
     * lo elige no lo ve ese tick. La versión anterior caía a {@code SIN_COMBATE} sin holgura
     * ninguna, y como además daba la permanencia por cumplida siempre que el estado fuera
     * {@code SIN_COMBATE}, <b>todo parpadeo pasaba por ahí y esquivaba la protección entera</b>.
     *
     * <p>Diez ticks cubren de sobra un tirón de medio segundo, que es el mayor que se aguanta
     * peleando, y no cuestan nada cuando la pelea sí ha terminado: lo único que pasa si se espera de
     * más es que los módulos sigan encendidos medio segundo con nadie delante, y todos están
     * limitados por su propio alcance. Mientras dura la gracia se sigue decidiendo con lo último que
     * se vio del objetivo (ver {@link #rememberTarget}), que es lo que evita que el parpadeo apague
     * nada.
     */
    public static final int TARGET_GRACE_TICKS = 10;

    /**
     * Holgura para las transiciones que salen de leer un bloque: {@code ENTERRADO} y
     * {@code RODEADO}, en los dos sentidos (§6). Dos ticks.
     *
     * <p>"Está enterrado" y "tiene surround" se leen del mundo, no de una velocidad ni de una
     * animación: o el bloque está o no está. La única causa de parpadeo es que el bloque cambie de
     * verdad -se lo rompen, lo coloca- o que llegue desactualizado un tick, así que basta con
     * exigir que la lectura se repita una vez. La spec lo fija en §6: "un cambio de bloque
     * (ENTERRADO) es una señal limpia y admite 2 ticks".
     */
    public static final int BLOCK_HOLD_TICKS = 2;

    /**
     * Holgura para <b>entrar</b> en {@code PERSECUCION} (§6). Cuatro ticks, 0,2 s.
     *
     * <p>Un despegue tiene un par de ticks de ambigüedad mientras la elytra se abre, y entrar en
     * {@code PERSECUCION} apaga todo lo ofensivo, así que no conviene hacerlo con un solo tick de
     * evidencia. Cuatro ticks se quedan por debajo del ciclo de cristal más corto que mide §9
     * (medio segundo son 2-5 ciclos, o sea 4-10 ticks por ciclo), de modo que equivocarse no llega a
     * costar un ciclo entero; y el aura, que es lo caro de apagar, ya no depende de la fase (§4.4).
     */
    public static final int GLIDE_ENTER_HOLD_TICKS = 4;

    /**
     * Holgura para <b>salir</b> de {@code PERSECUCION} porque el objetivo ha dejado de planear (§6).
     * Diez ticks, medio segundo: "bastante más para salir, por los rebotes al aterrizar".
     *
     * <p>Aterrizar con elytra no apaga el planeo de una vez: se va rozando el suelo y la marca se
     * enciende y se apaga varias veces seguidas durante unas décimas. Diez ticks seguidos sin planear
     * son más largos que cualquiera de esos rebotes. Esperar es barato porque {@code PERSECUCION} ya
     * no enciende nada -{@code auto-web}, con {@code place-range} 4 y predicción a 10 ticks, nunca
     * coloca a velocidad de elytra (§4.2)- y el aura sigue funcionando por su cuenta.
     *
     * <p>No se aplica cuando lo que cambia es la distancia: si sigue planeando pero se ha metido en
     * rango de cristal, es que se te ha echado encima, y eso es una señal limpia de posición que se
     * trata con {@link #BLOCK_HOLD_TICKS}.
     */
    public static final int GLIDE_EXIT_HOLD_TICKS = 10;

    /**
     * Ticks seguidos por debajo del mínimo que un módulo ya encendido aguanta antes de soltarse
     * (spec §6.2). Verificado como correcto en §9: no se toca.
     */
    public static final int RESOURCE_RELEASE_DWELL_TICKS = 20;

    /**
     * Distancia máxima real al bloque de rodeado para clasificar {@code RODEADO} (spec §4.2.1,
     * segunda corrección). Verificado contra las fuentes de {@code meteor-client:1.21.11-SNAPSHOT}
     * (`AutoCity.java`): el módulo se apaga solo -dentro de su propio
     * {@code onActivate()}/{@code onTick()}, con un error en el chat- si el bloque de rodeado está a
     * más de {@code break-range} (por defecto **4.5**, el valor de fábrica que fija esta constante)
     * de ti, comprobado con {@code PlayerUtils.squaredDistanceTo(targetPos)} sobre la {@code BlockPos}
     * del bloque. El otro límite de {@code auto-city} -{@code target-range}, contra el objetivo, no
     * el bloque- es {@link #AUTO_CITY_TARGET_RANGE}: los dos hacen falta a la vez (tercera
     * corrección), esta constante por sí sola ya no basta.
     *
     * <p><b>4.5 es el ajuste de fábrica de {@code break-range}; el usuario puede cambiarlo en
     * Meteor.</b> Esta constante no lo lee en vivo -el núcleo no importa nada de
     * {@code meteordevelopment}-, así que si alguien sube o baja su {@code break-range} el director
     * sigue comparando contra 4.5, no contra el valor real configurado. Es el mismo trato que recibe
     * {@link #AUTO_CITY_TARGET_RANGE}.
     *
     * <p><b>La comparación es contra la distancia real al bloque, no al objetivo</b>
     * ({@link CombatSnapshot#cityBlockDistance()}). Usar la distancia al objetivo como proxy -lo
     * que hacía la primera corrección- es incorrecto: el bloque de rodeado es un vecino horizontal
     * del objetivo (`EntityUtils.getCityBlock()`) y puede caer al lado contrario de donde estás tú,
     * así que un objetivo cerca no garantiza un bloque cerca. Contraejemplo real: jugador en
     * (0.5, 0, 0.5), objetivo en (4.5, 0, 0.5) -distancia 4.0, dentro del antiguo límite-, bloque de
     * rodeado en (5, 0, 0) -distancia al cuadrado 20.5, por encima de 4.5² = 20.25-: la versión
     * anterior declaraba RODEADO y auto-city se apagaba solo, con error, cada tick. Además
     * {@code PlayerUtils.squaredDistanceTo(BlockPos)} mide a la esquina mínima del bloque, no a su
     * centro, así que ni siquiera una cota "conservadora" basada en el objetivo puede acotar bien la
     * distancia real al bloque.
     */
    public static final double AUTO_CITY_BREAK_RANGE = 4.5;

    /**
     * Distancia máxima real al objetivo (no al bloque) para clasificar {@code RODEADO} (spec
     * §4.2.1, tercera corrección). {@link #AUTO_CITY_BREAK_RANGE} por sí sola no basta: verificado
     * en las fuentes de {@code meteor-client:1.21.11-SNAPSHOT} (`AutoCity.onTick()` llama primero a
     * {@code TargetUtils.isBadTarget(target, targetRange.get())}, que exige
     * {@code PlayerUtils.isWithin(target, targetRange)}, <b>antes</b> de mirar el bloque para nada),
     * {@code auto-city} también se apaga solo -mismo `toggle()` incondicional, mismo error en el
     * chat- si el objetivo mismo está a más de {@code target-range} (por defecto **5.5**, el valor
     * de fábrica que fija esta constante), sin que la distancia al bloque importe en absoluto.
     *
     * <p>El bloque de rodeado es un vecino horizontal del objetivo medido a su esquina mínima
     * ({@code EntityUtils.getCityBlock()} / {@code PlayerUtils.squaredDistanceTo(BlockPos)}), así
     * que un bloque a &le; {@link #AUTO_CITY_BREAK_RANGE} (4.5) admite un objetivo hasta a
     * &asymp;6.4: sin esta cota, ese hueco entre 5.5 y ~6.4 volvía a declarar {@code RODEADO} con el
     * objetivo fuera del alcance real de {@code auto-city}, que se apagaba solo cada tick y el
     * ledger lo volvía a encender -la misma tormenta de veinte encendidos y veinte errores por
     * segundo que la corrección de {@link #AUTO_CITY_BREAK_RANGE} ya había eliminado para el caso
     * contrario (objetivo cerca, bloque lejos)-.
     *
     * <p>5.5 es el ajuste de fábrica de {@code target-range}; mismo trato que
     * {@link #AUTO_CITY_BREAK_RANGE}: no se lee en vivo del ajuste real del usuario.
     */
    public static final double AUTO_CITY_TARGET_RANGE = 5.5;

    private CombatState state = CombatState.SIN_COMBATE;
    private CombatState pending;
    private int pendingTicks;
    private int ticksInState;

    /** La serie de distancias con la que se decide si el objetivo se aleja de verdad (§4.3). */
    private final RetreatWatch retreat = new RetreatWatch();

    /**
     * El último snapshot en el que sí había objetivo, y cuántos ticks seguidos lleva sin haberlo.
     * Juntos son la gracia de §6: mientras dure, se sigue decidiendo con lo último que se vio de él
     * en vez de dar la pelea por terminada.
     */
    private CombatSnapshot lastSeenTarget;
    private int missingTargetTicks;

    /**
     * Los módulos que el {@link Plan} del tick anterior devolvió en {@code enable()}. Es la
     * memoria que hace falta para la histéresis del filtro de recursos (spec §6.2): sin ella,
     * {@code planFor} no podría saber si un módulo ya estaba encendido.
     *
     * <p>Solo se olvida en {@link #reset()}. Antes se sobrescribía también al entrar en
     * {@code SIN_COMBATE}, que se entra sin esperar: un objetivo que sale un tick de rango y vuelve
     * borraba toda la memoria de recursos de la pelea entera. Ahora {@link #tick} deja este campo
     * intacto mientras la fase física es {@code SIN_COMBATE}.
     */
    private Set<ManagedModule> previouslyEnabled = Set.of();

    /**
     * Ticks seguidos que cada módulo lleva por debajo de su mínimo mientras sigue encendido por
     * histéresis (spec §6.2). Solo tiene entrada mientras el módulo está en su ventana de gracia;
     * se borra en cuanto vuelve a tener suficiente o se le acaba la permanencia.
     */
    private final Map<ManagedModule, Integer> belowMinimumTicks = new HashMap<>();

    /**
     * La fase física en la que está el director ahora mismo. Nunca es {@code SIN_RECURSOS}: esa
     * fase solo aparece en el {@link Plan} que devuelve {@link #tick}, no aquí (spec §4.2).
     */
    public CombatState state() {
        return state;
    }

    /** Ticks que lleva el director en la fase actual, contando desde el último cambio. */
    public int ticksInState() {
        return ticksInState;
    }

    /** Si el objetivo se está alejando de forma sostenida ahora mismo (§4.3). */
    public boolean targetRetreating() {
        return retreat.retreating();
    }

    /** Olvida la fase, los contadores y qué módulos tenía encendidos. Se llama al encender el módulo. */
    public void reset() {
        state = CombatState.SIN_COMBATE;
        pending = null;
        pendingTicks = 0;
        ticksInState = 0;
        previouslyEnabled = Set.of();
        belowMinimumTicks.clear();
        retreat.reset();
        lastSeenTarget = null;
        missingTargetTicks = 0;
    }

    /** Un ciclo completo del criterio con el margen defensivo de fábrica. */
    public Plan tick(CombatSnapshot snapshot, int approachDistance) {
        return tick(snapshot, approachDistance, DefensivePolicy.THREAT_MARGIN);
    }

    /**
     * Ejecuta un ciclo completo del criterio. En orden:
     *
     * <ol>
     *   <li><b>Gracia al perder el objetivo</b> (§6): si este tick no hay objetivo -o está más allá
     *       de {@link #CLASSIFY_TARGET_RANGE}- pero lo había hace menos de
     *       {@link #TARGET_GRACE_TICKS}, se clasifica con lo último que se vio de él. La mitad
     *       propia del snapshot (recursos, vida, agujero) es siempre la de este tick: lo que se
     *       recuerda es al enemigo, no a ti.</li>
     *   <li><b>Fase ofensiva</b> con la precedencia de §4.1, adoptada cuando la candidata se
     *       sostiene los ticks que pida <b>esa</b> transición (§6: holgura por transición, no una
     *       global). {@code MIN_DWELL_TICKS} ya no existe: hacía que el director tardara más en
     *       corregir su error que en cometerlo, y nunca debe retrasar una transición que vuelve a
     *       encender el aura.</li>
     *   <li><b>Postura defensiva</b> ({@link DefensivePolicy}), que no depende de la fase.</li>
     *   <li>La <b>unión</b> de lo que piden los dos ejes, filtrada por recursos con histéresis y por
     *       el suelo de tótems, que desde §7.1 solo queda en pie con el {@code anti-suicide} de
     *       {@code crystal-aura} apagado.</li>
     * </ol>
     *
     * @param snapshot         la situación de este tick, ya traducida a valores simples (spec §5)
     * @param approachDistance distancia a partir de la cual el objetivo se considera lejos, no cerca
     * @param threatMargin     vida que te tiene que quedar, descontando lo que ya te apunta, para
     *                         seguir {@code TRANQUILO} (§5). La spec deja el umbral abierto y como
     *                         ajuste del módulo ({@code threat-margin});
     *                         {@link DefensivePolicy#THREAT_MARGIN} es solo su valor de fábrica, el
     *                         que pone la firma corta. Mismo trato que {@code approach-distance}: el
     *                         número lo pone el jugador, la decisión sigue siendo del núcleo
     * @return el plan de este tick: la fase con la que se informa (puede ser {@code SIN_RECURSOS}
     *     aunque la fase física siga siendo otra), la postura, los módulos a encender, los que se
     *     omitieron con su motivo y los avisos
     */
    public Plan tick(CombatSnapshot snapshot, int approachDistance, double threatMargin) {
        CombatSnapshot effective = rememberTarget(snapshot);

        CombatState candidate = classify(effective, approachDistance, state);
        if (candidate != state) {
            pendingTicks = candidate == pending ? pendingTicks + 1 : 1;
            pending = candidate;
            if (pendingTicks >= holdTicksFor(state, candidate, effective)) enter(candidate);
        } else {
            pending = null;
            pendingTicks = 0;
        }

        ticksInState++;
        boolean retreating = retreat.update(effective);
        Plan plan = planFor(state, effective, retreating, threatMargin);
        // Se guarda DESPUÉS de calcular el plan: planFor() necesita ver lo que estaba encendido
        // en el tick anterior, no lo que acaba de decidir este. Mientras la fase física sea
        // SIN_COMBATE no se toca: un blip sin objetivo no debe borrar la memoria de recursos de la
        // pelea que sigue (spec §6.2).
        if (state != CombatState.SIN_COMBATE) {
            previouslyEnabled = Set.copyOf(plan.enable());
        }
        return plan;
    }

    /**
     * La gracia de §6, hecha snapshot: mientras el objetivo lleve perdido menos de
     * {@link #TARGET_GRACE_TICKS} ticks, se devuelve el snapshot de este tick con la mitad del
     * enemigo sustituida por la última que se vio de verdad.
     *
     * <p>Sustituir el snapshot -en vez de congelar la fase- es lo que hace que la gracia proteja de
     * verdad: la fase sale sola de la clasificación normal, y además los módulos que dependen del
     * alcance al objetivo ({@code auto-trap} a 3, {@code auto-anvil} a 4) siguen encendidos durante
     * el parpadeo en vez de caerse un tick y volver.
     */
    private CombatSnapshot rememberTarget(CombatSnapshot now) {
        boolean lost = !now.hasTarget() || now.targetDistance() > CLASSIFY_TARGET_RANGE;
        if (!lost) {
            lastSeenTarget = now;
            missingTargetTicks = 0;
            return now;
        }

        // Al décimo tick seguido sin verlo se acabó la gracia: los nueve anteriores se deciden con
        // lo último que se vio de él.
        missingTargetTicks++;
        if (lastSeenTarget == null || missingTargetTicks >= TARGET_GRACE_TICKS) {
            lastSeenTarget = null;
            return now;
        }

        CombatSnapshot seen = lastSeenTarget;
        return new CombatSnapshot(true, seen.targetDistance(), seen.targetSurrounded(),
            seen.cityBlockDistance(), seen.targetBurrowed(), seen.targetGliding(),
            now.selfGliding(), now.selfTotems(), now.resources(),
            seen.targetId(), now.unprotectedHostilesInCrystalRange(),
            now.selfTotalHealth(), now.incomingDamage(), now.selfInHole(), now.selfOnGround(),
            now.crystalAuraAntiSuicide());
    }

    private void enter(CombatState next) {
        if (next != state) {
            state = next;
            ticksInState = 0;
        }
        pending = null;
        pendingTicks = 0;
    }

    /**
     * La precedencia de §4.1: gana la primera que se cumpla.
     *
     * <p>Tres cambios respecto al criterio anterior. {@code selfGliding()} <b>ya no dispara</b>
     * {@code PERSECUCION}: en este servidor se vuela casi siempre, así que el director pasaba la
     * mayor parte del tiempo en la fase que menos hace, y que vueles tú no dice nada del enemigo.
     * Solo cuenta que vuele él, y solo si además está fuera de rango de cristal: volando y pegado a
     * ti es una pelea normal y los cristales le entran igual. {@code ENTERRADO} exige rango de
     * yunque, porque un enterrado a 12 es un obstáculo, no una fase. Y {@code SUPERFICIE} y
     * {@code ACERCAMIENTO} se separan por una banda de distancia, no por un umbral desnudo, que es
     * de donde salía media oscilación.
     *
     * @param current la fase actual, que solo se usa para saber por qué lado de la banda hay que
     *                salir; el resto de la clasificación no depende de ella
     */
    static CombatState classify(CombatSnapshot s, int approachDistance, CombatState current) {
        if (!s.hasTarget() || s.targetDistance() > CLASSIFY_TARGET_RANGE) return CombatState.SIN_COMBATE;
        if (s.targetGliding() && s.targetDistance() > CRYSTAL_RANGE) return CombatState.PERSECUCION;
        if (s.targetBurrowed() && s.targetDistance() <= AUTO_ANVIL_TARGET_RANGE) return CombatState.ENTERRADO;
        if (s.targetSurrounded() && s.cityBlockDistance() <= AUTO_CITY_BREAK_RANGE
            && s.targetDistance() <= AUTO_CITY_TARGET_RANGE) return CombatState.RODEADO;

        // La banda: para entrar en ACERCAMIENTO hay que pasar de approach + 1; para volver a
        // SUPERFICIE hay que bajar de approach - 1. En medio manda la fase en la que ya estabas,
        // así que un objetivo parado justo en approach no puede hacer oscilar nada.
        double exit = current == CombatState.ACERCAMIENTO
            ? approachDistance - APPROACH_BAND
            : approachDistance + APPROACH_BAND;
        if (s.targetDistance() > exit) return CombatState.ACERCAMIENTO;
        return CombatState.SUPERFICIE;
    }

    /**
     * Cuántos ticks seguidos tiene que sostenerse la candidata para adoptarla (§6): una holgura por
     * transición, no una global. Medio segundo son 2-5 ciclos de cristal, o sea 15-40 de daño, así
     * que cobrarle diez ticks a todas las transiciones por igual era caro justo donde no hacía
     * falta.
     */
    private static int holdTicksFor(CombatState from, CombatState to, CombatSnapshot s) {
        // Soltar tarde no es aceptable y enganchar tarde cuesta el primer combo: los dos extremos
        // van sin esperar. La gracia de TARGET_GRACE_TICKS ya está delante de SIN_COMBATE, así que
        // cuando la candidata llega hasta aquí es que la pelea ha terminado de verdad.
        if (to == CombatState.SIN_COMBATE || from == CombatState.SIN_COMBATE) return 0;

        if (from == CombatState.PERSECUCION) {
            // Si sigue planeando, lo que ha cambiado es la distancia -se te ha echado encima-, y eso
            // es una señal limpia de posición, no un rebote de aterrizaje.
            return s.targetGliding() ? BLOCK_HOLD_TICKS : GLIDE_EXIT_HOLD_TICKS;
        }
        if (to == CombatState.PERSECUCION) return GLIDE_ENTER_HOLD_TICKS;
        if (to == CombatState.ENTERRADO || from == CombatState.ENTERRADO
            || to == CombatState.RODEADO || from == CombatState.RODEADO) return BLOCK_HOLD_TICKS;

        // SUPERFICIE <-> ACERCAMIENTO: la histéresis es la banda de distancia, no el tiempo.
        return 0;
    }

    /**
     * Lo que pide la fase ofensiva (§4.2), ya con el alcance real de cada módulo por delante (§10:
     * ningún módulo se enciende fuera de su alcance).
     *
     * <p>{@code ACERCAMIENTO} y {@code PERSECUCION} no piden nada y son etiquetas de informe: entre
     * 6 y 16 bloques no hay nada útil que encender, y {@code auto-web}, con {@code place-range} 4 y
     * predicción a 10 ticks, nunca coloca a velocidad de elytra -encenderlo era fingir que se hacía
     * algo-. {@code surround} tampoco aparece: es defensivo y lo pide la postura (§5).
     */
    private static List<ManagedModule> offensiveModules(CombatState state, CombatSnapshot s, boolean retreating) {
        if (!s.hasTarget()) return List.of();

        List<ManagedModule> modules = new ArrayList<>();
        switch (state) {
            case SUPERFICIE -> {
                modules.add(ManagedModules.CRYSTAL_AURA);
                if (s.targetDistance() <= AUTO_TRAP_TARGET_RANGE) modules.add(ManagedModules.AUTO_TRAP);
                // §4.3: auto-web no le roba el sitio al aura. Solo se enciende para impedir que se
                // vaya -se aleja de forma sostenida- o cuando ya está lo bastante lejos como para
                // que la casilla que telaraña no fuera a ser la del próximo cristal.
                if (retreating || s.targetDistance() > AUTO_WEB_MIN_DISTANCE) modules.add(ManagedModules.AUTO_WEB);
            }
            case RODEADO -> {
                modules.add(ManagedModules.AUTO_CITY);
                modules.add(ManagedModules.CRYSTAL_AURA);
            }
            case ENTERRADO -> {
                modules.add(ManagedModules.AUTO_ANVIL);
                // El trap es para cuando salga del burrow, pero AutoTrap trabaja a 3 y ENTERRADO
                // llega hasta 4: entre medias no llega, así que no se enciende.
                if (s.targetDistance() <= AUTO_TRAP_TARGET_RANGE) modules.add(ManagedModules.AUTO_TRAP);
            }
            default -> { }
        }
        return modules;
    }

    private Plan planFor(CombatState state, CombatSnapshot snapshot, boolean retreating, double threatMargin) {
        List<ManagedModule> offensive = offensiveModules(state, snapshot, retreating);

        Set<ManagedModule> wanted = new LinkedHashSet<>(offensive);
        // §4.4: el aura no se apaga por la fase si hay a quién cristalear. crystal-aura pelea contra
        // todos a la vez, así que atarla a la fase de un solo jugador abre el cebo obvio -uno se
        // entierra, el otro te cristalea, y el director te apaga el aura contra el segundo-.
        if (snapshot.unprotectedHostilesInCrystalRange() > 0) wanted.add(ManagedModules.CRYSTAL_AURA);

        CombatPosture posture = DefensivePolicy.postureFor(snapshot, threatMargin);
        wanted.addAll(DefensivePolicy.modulesFor(posture, snapshot));

        List<ManagedModule> enable = new ArrayList<>();
        List<Skipped> skipped = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        for (ManagedModule module : wanted) {
            if (module.equals(ManagedModules.CRYSTAL_AURA)) {
                // El suelo de tótems, ya solo como red de emergencia. Es §7 por otra puerta: era una
                // decisión de vida tomada con un contador de ítems, y Meteor ya la toma con el daño
                // exacto. Para la mitad de COLOCAR, anti-suicide (defaultValue(true)) se niega a
                // ponerte un cristal que te mate; para la mitad de ROMPER, el suelo te quitaba el
                // autobreak justo cuando no llevas tótems, que es cuando más falta hace.
                //
                // Pero anti-suicide es solo un valor por defecto: si el jugador lo ha apagado, esa
                // protección no existe, y entonces -y solo entonces- el suelo sigue en pie. El
                // motivo lo dice entero para que el jugador sepa qué apagar o qué encender.
                if (snapshot.selfTotems() <= 0 && !snapshot.crystalAuraAntiSuicide()) {
                    skipped.add(new Skipped(module,
                        "no llevas tótems y el anti-suicide de crystal-aura está apagado"));
                    continue;
                }
                // §7: el aura queda fuera del filtro de recursos. Es la única de las dirigidas con
                // una mitad útil a coste cero -CrystalAura trae break en true y only-own en false,
                // y romper no gasta ningún cristal tuyo-, así que apagarla por quedarte sin
                // cristales te quita justo lo que te mantiene vivo cuando no tienes con qué
                // responder. Se enciende igual y se informa como aviso, no como omisión.
                belowMinimumTicks.remove(module);
                enable.add(module);
                if (snapshot.amountOf(Resource.CRYSTALS) < ManagedModules.CRYSTAL_AURA.minimum()) {
                    warnings.add("crystal-aura sin cristales: solo romperá los que te pongan.");
                }
                continue;
            }
            if (hasEnough(module, snapshot, enable)) continue;
            skipped.add(new Skipped(module, "tienes " + snapshot.amountOf(module.needs()) + ", necesita " + module.minimum()));
        }

        // SIN_RECURSOS es cómo se informa, no un sitio donde se vive (spec §4.2), y se mide solo
        // sobre la mitad ofensiva: que la postura haya levantado un anti-bed no quiere decir que
        // puedas pelear, y que no haya nada que pelear -ACERCAMIENTO, PERSECUCION- tampoco es
        // quedarse sin recursos.
        boolean offensiveUp = enable.stream().anyMatch(offensive::contains);
        CombatState reported = !offensive.isEmpty() && !offensiveUp ? CombatState.SIN_RECURSOS : state;
        return new Plan(reported, posture, enable, skipped, warnings);
    }

    /**
     * La histéresis del filtro de recursos (spec §6.2): sin ella, un recurso que se va gastando
     * durante la pelea (la obsidiana de auto-trap, por ejemplo) cruza el mínimo una y otra vez y el
     * módulo se enciende y se apaga en cada tick.
     *
     * <p>Un módulo que no estaba encendido el tick anterior necesita el mínimo completo, sin
     * gracia. Uno que sí lo estaba se mantiene encendido mientras lleve menos de
     * {@link #RESOURCE_RELEASE_DWELL_TICKS} ticks seguidos por debajo del mínimo; al cumplirlos, se
     * suelta. Es una permanencia en el tiempo, no un umbral partido: con {@code minimum() == 1}
     * -la mayoría de los dirigidos- un umbral a la mitad redondeaba al mismo mínimo y no daba
     * ninguna gracia; contar ticks sirve igual para todos.
     */
    private boolean hasEnough(ManagedModule module, CombatSnapshot snapshot, List<ManagedModule> enable) {
        int have = snapshot.amountOf(module.needs());
        if (have >= module.minimum()) {
            belowMinimumTicks.remove(module);
            enable.add(module);
            return true;
        }

        if (!previouslyEnabled.contains(module)) {
            belowMinimumTicks.remove(module);
            return false;
        }

        int ticksBelow = belowMinimumTicks.merge(module, 1, Integer::sum);
        if (ticksBelow < RESOURCE_RELEASE_DWELL_TICKS) {
            enable.add(module);
            return true;
        }

        belowMinimumTicks.remove(module);
        return false;
    }
}
