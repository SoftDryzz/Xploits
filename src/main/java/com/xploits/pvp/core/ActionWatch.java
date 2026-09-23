package com.xploits.pvp.core;

import com.xploits.shared.core.i18n.Msg;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * "Lo tengo encendido y no está haciendo nada", medido en vez de supuesto.
 *
 * <h2>El problema</h2>
 * El director enciende y apaga módulos, pero <b>no controla sus ajustes</b>, y esos ajustes deciden
 * si el módulo hace algo. De fábrica {@code CrystalAura} trae {@code min-damage} en 6 -el daño
 * mínimo que el cristal tiene que hacerle al objetivo para colocarlo- y {@code support} en
 * {@code Disabled}; en un servidor donde todos llevan netherita con Protección IV ese umbral puede
 * rechazar casi todas las posiciones. Entonces {@code auto-pvp} anuncia {@code SUPERFICIE · enemigo},
 * enciende el aura y <b>no pasa nada</b>, y desde fuera es indistinguible de que funcione. Eso es lo
 * que el principio del módulo prohíbe: <i>un fallo no puede parecerse a un resultado normal</i>
 * (rediseño §10).
 *
 * <h2>Por qué no se leen los ajustes</h2>
 * Leer {@code min-damage} y compararlo con algo exigiría adivinar la lista de causas -y siempre
 * faltaría una: el {@code walls-range}, el {@code height} del yunque, la posición ocupada, el
 * servidor que rechaza el swap-. Aquí se mide el <b>efecto</b>, que es uno solo y se ve: si el
 * director quiere el módulo, lo ha encendido, hay objetivo a rango y hay material -y el material
 * <b>no baja</b> durante {@link #IDLE_TICKS} ticks- ese módulo no está actuando, y da igual por qué.
 * Es el mismo razonamiento con el que {@code nether-sweep} mide la anchura de pasada del servidor y
 * el gasto real de cohetes en vez de suponer un número.
 *
 * <h2>El veredicto es de la pila, no del módulo</h2>
 * Lo que se mide es <b>una pila del inventario</b>, y una pila puede tener varios consumidores:
 * {@code auto-trap}, {@code surround} y {@code hole-filler} beben de la misma obsidiana (I3). El
 * inventario dice cuánta obsidiana queda, <b>no quién la colocó</b>, así que con este dato no se
 * puede afirmar nada de uno solo de los tres: la única frase honesta es "no ha colocado ninguno de
 * estos". Por eso la cuenta va <b>por recurso</b> y el aviso sale con los nombres de todos los que
 * estaban en condiciones de gastarlo, diciendo con todas las letras que el veredicto es conjunto.
 * Cuando el recurso tiene un solo consumidor -los cuatro casos habituales- el grupo es de uno y el
 * aviso es del módulo.
 *
 * <p><b>Y eso tiene un precio que hay que decir, no esconder:</b> mientras uno del grupo gaste de
 * verdad, la pila se mueve y del resto <b>no se dice nada</b>. En una guerra de trampa, con el rival
 * rompiendo el trap y {@code auto-trap} reconstruyéndolo una y otra vez, un {@code hole-filler} roto
 * de verdad puede no avisar en toda la pelea. Eso no es un retraso, es silencio, y con este dato no
 * se arregla: se arreglaría viendo quién coloca, que es justo lo que el inventario no cuenta. Lo que
 * sí se puede hacer -y es lo que se hace- es no afirmar una certeza por módulo que no existe.
 *
 * <h2>A quién vigila, y a quién no</h2>
 * El vigilante cubre <b>seis de los diez</b> módulos dirigidos: {@code crystal-aura},
 * {@code auto-trap}, {@code auto-web}, {@code auto-anvil} y los dos de la obsidiana,
 * {@code surround} y {@code hole-filler}. Los cuatro que quedan fuera están fuera por una razón
 * escrita, no fingida:
 *
 * <ul>
 *   <li><b>{@code auto-city} usa pico, y el pico no se consume</b> ({@link Resource#PICKAXE}).
 *       Minar le quita durabilidad, no lo quita del inventario, y la cuenta del snapshot es de
 *       ítems. "El recurso no baja" no significa nada ahí, así que vigilarlo sería avisar de un
 *       fallo cada vez que {@code auto-city} funciona bien.</li>
 *   <li><b>Los tres {@code anti-} declaran {@link Resource#NONE}</b>: no colocan nada, solo escuchan
 *       y reaccionan (rediseño §5). No hay nada que pueda bajar, así que tampoco hay nada que
 *       medir.</li>
 * </ul>
 *
 * <p>La exclusión no es una lista escrita a mano que se quede vieja: sale del recurso que cada
 * módulo declara en {@link ManagedModules} ({@link #watches}), así que un módulo nuevo entra o queda
 * fuera solo.
 *
 * <h2>Qué hace y qué no hace</h2>
 * <b>No corta ni apaga nada.</b> Un módulo que no actúa puede ser perfectamente correcto -puede no
 * haber posición válida ahora mismo, el {@code surround} puede estar ya completo, la casilla que
 * {@code auto-web} telaraña puede tener ya telaraña-, y apagarlo sería peor que avisar: el módulo
 * dejaría de estar listo para el tick en el que sí haya posición. Lo único que hace es decirlo
 * <b>una vez</b>, nombrando los módulos y apuntando a los sospechosos <b>sin afirmar cuál es</b>, y
 * volver a armarse cuando la situación cambia.
 */
public final class ActionWatch {
    /** Ticks por segundo del juego, para decir el margen en segundos en el aviso. */
    private static final int TICKS_PER_SECOND = 20;

    /**
     * Ticks seguidos con alguien en condiciones de gastar la pila y sin que la pila se mueva antes
     * de avisar. Sesenta, tres segundos.
     *
     * <p>El número sale de las dos formas de equivocarse, y son muy asimétricas.
     *
     * <p><b>Por abajo</b> manda la cadencia legítima más lenta de los seis vigilados, que está
     * medida, no supuesta: {@code AutoAnvil} trae {@code delay} en {@code defaultValue(10)}
     * -verificado en las fuentes de {@code meteor-client:1.21.11-SNAPSHOT}: su {@code onTick}
     * coloca solo cuando {@code timer >= delay.get()} y reinicia el contador-, así que un
     * {@code auto-anvil} que funciona perfectamente gasta un yunque cada <b>once</b> ticks. Un
     * margen de veinte ticks le dejaría menos de dos cadencias de holgura: un solo hueco tapado
     * sobre la cabeza del objetivo ya lo cruzaría, y el aviso saldría constantemente con el módulo
     * funcionando. Con sesenta caben cinco colocaciones seguidas falladas antes de decir nada. Para
     * {@code crystal-aura} el mismo margen son de 6 a 15 ciclos de cristal enteros (§9 mide medio
     * segundo en 2-5 ciclos, o sea 4-10 ticks por ciclo): tres segundos con un hostil a menos de
     * 4,5, el aura encendida y cristales en la mano sin colocar <b>ni uno</b> no es un hueco de
     * combate, es un muro.
     *
     * <p><b>Por arriba no hay prisa</b>, y esa es la asimetría: la causa que esto busca -un umbral
     * que rechaza todas las posiciones, un rango corto, una lista de bloques que no incluye lo que
     * llevas- <b>es permanente</b>. No se cura sola, así que esperar de más nunca pierde el aviso;
     * solo lo retrasa tres segundos. Equivocarse por el otro lado sí cuesta: una línea de chat
     * falsa en cada pelea enseña al jugador a ignorar el aviso, y entonces el vigilante no sirve
     * para nada.
     */
    public static final int IDLE_TICKS = 60;

    /**
     * Si un módulo dirigido se puede vigilar así. La pregunta es si su recurso <b>se gasta al
     * usarlo</b>: {@link Resource#PICKAXE} no -minar gasta durabilidad, no ítems- y
     * {@link Resource#NONE} no existe.
     */
    public static boolean watches(ManagedModule module) {
        return module.needs() != Resource.PICKAXE && module.needs() != Resource.NONE;
    }

    /** Los seis vigilados, derivados del catálogo por {@link #watches} y en su mismo orden. */
    public static final List<ManagedModule> WATCHED =
        ManagedModules.ALL.stream().filter(ActionWatch::watches).toList();

    /** Las pilas que hay que mirar, sin repetir y en el orden en que aparecen en {@link #WATCHED}. */
    public static final List<Resource> WATCHED_RESOURCES =
        List.copyOf(new LinkedHashSet<>(WATCHED.stream().map(ManagedModule::needs).toList()));

    /**
     * Un veredicto: la pila que no se mueve, los módulos que estaban en condiciones de gastarla y
     * cuántos ticks llevan así.
     *
     * <p>Con más de un módulo el veredicto es <b>conjunto y no se puede repartir</b> ({@link
     * #joint()}): lo único medido es que la pila no baja, y el inventario no dice quién coloca.
     *
     * @param resource la pila que no se ha movido
     * @param modules  los que se querían, estaban encendidos y tenían material de sobra, en el orden
     *                 del catálogo
     * @param ticks    ticks seguidos que lleva así, ya cumplido el margen
     */
    public record Idle(Resource resource, List<ManagedModule> modules, int ticks) {
        public Idle {
            modules = List.copyOf(modules);
        }

        /** Si el veredicto es de varios módulos a la vez y por tanto no se puede repartir. */
        public boolean joint() {
            return modules.size() > 1;
        }
    }

    /** Ticks seguidos que cada pila lleva quieta con alguien en condiciones de gastarla. */
    private final Map<Resource, Integer> idleTicks = new EnumMap<>(Resource.class);

    /** Quiénes estaban en condiciones de gastar cada pila en el tick anterior. */
    private final Map<Resource, List<ManagedModule>> lastEligible = new EnumMap<>(Resource.class);

    /** Cuánto había de cada pila vigilada en el tick anterior. */
    private final Map<Resource, Integer> lastAmount = new EnumMap<>(Resource.class);

    /** De qué pilas ya se avisó, para decirlo una vez y no en bucle. */
    private final Set<Resource> warned = new LinkedHashSet<>();

    /**
     * Un tick de vigilancia. Devuelve los veredictos de los que <b>hay que avisar ahora</b>: las
     * pilas que acaban de cumplir el margen y de las que todavía no se había avisado.
     *
     * <p>Las cuatro condiciones que ponen a un módulo "en condiciones de gastar" son: el plan lo
     * <b>quiere</b>, está <b>encendido de verdad</b>, hay <b>objetivo</b> y hay <b>recurso
     * suficiente</b> (su {@link ManagedModule#minimum()}). La cuenta de una pila corre mientras haya
     * al menos uno en esas condiciones.
     *
     * <p>Que deje de haberlo no pausa la cuenta: la <b>reinicia</b>, y además rearma el aviso. Es lo
     * conservador y es lo que hace falta -la afirmación que se va a hacer es "lleva tres segundos
     * seguidos sin gastar pudiendo gastar", y un tick en el que no podía gastar la rompe entera-.
     *
     * <p><b>Que cambie quién está en condiciones también reinicia.</b> Si {@code auto-trap} se suma
     * a la obsidiana en el tick 50, el veredicto de los tres no puede apoyarse en los cincuenta
     * ticks en los que él no estaba: esa serie se refería a otro grupo. Se empieza de cero.
     *
     * <p>El objetivo se exige también a los dos defensivos, aunque {@code surround} y
     * {@code hole-filler} no lo necesiten para colocar: sin nadie delante, que no gasten es lo
     * normal, y contar esos ticks solo produciría avisos de nada.
     *
     * <p><b>Cualquier movimiento de la pila reinicia, no solo una bajada.</b> Si baja, alguien del
     * grupo está actuando y no hay nada que decir. Si sube -recoges obsidiana, sacas telarañas de la
     * mochila- la serie deja de comparar lo mismo, y una subida puede además tapar un gasto (gastas
     * una y recoges dos). No se puede afirmar que no se gasta mientras la pila se mueve, así que no
     * se afirma.
     *
     * @param snapshot la situación de este tick, para el objetivo y para las cuentas del inventario
     * @param wanted   los nombres de módulo que el plan de este tick quiere encendidos
     * @param active   los nombres de módulo que están encendidos de verdad ahora mismo
     */
    public List<Idle> update(CombatSnapshot snapshot, Set<String> wanted, Set<String> active) {
        List<Idle> newlyIdle = new ArrayList<>();

        for (Resource resource : WATCHED_RESOURCES) {
            int amount = snapshot.amountOf(resource);
            Integer before = lastAmount.put(resource, amount);
            boolean moved = before != null && before != amount;

            List<ManagedModule> eligible = eligibleFor(resource, snapshot, wanted, active);
            List<ManagedModule> previous = lastEligible.put(resource, eligible);

            if (eligible.isEmpty() || moved) {
                idleTicks.remove(resource);
                warned.remove(resource);
                continue;
            }
            if (!eligible.equals(previous)) {
                idleTicks.remove(resource);
                warned.remove(resource);
            }

            int ticks = idleTicks.merge(resource, 1, Integer::sum);
            if (ticks >= IDLE_TICKS && warned.add(resource)) {
                newlyIdle.add(new Idle(resource, eligible, ticks));
            }
        }
        return List.copyOf(newlyIdle);
    }

    /** Los vigilados de esa pila que este tick estaban en condiciones de gastarla. */
    private static List<ManagedModule> eligibleFor(Resource resource, CombatSnapshot snapshot,
                                                   Set<String> wanted, Set<String> active) {
        if (!snapshot.hasTarget()) return List.of();

        List<ManagedModule> eligible = new ArrayList<>();
        for (ManagedModule module : WATCHED) {
            if (module.needs() != resource) continue;
            if (!wanted.contains(module.name()) || !active.contains(module.name())) continue;
            if (snapshot.amountOf(resource) < module.minimum()) continue;
            eligible.add(module);
        }
        return List.copyOf(eligible);
    }

    /** Ticks que esa pila lleva quieta con alguien en condiciones de gastarla; cero si no es el caso. */
    public int idleTicksOf(Resource resource) {
        return idleTicks.getOrDefault(resource, 0);
    }

    /**
     * Los veredictos que ya han cumplido el margen entero y siguen en pie, en el orden de las pilas.
     * Es lo que enseña {@code .xploits pvp}: el aviso se dice una vez, pero la situación dura, y
     * tiene que poder consultarse mientras dura.
     */
    public List<Idle> idle() {
        List<Idle> result = new ArrayList<>();
        for (Resource resource : WATCHED_RESOURCES) {
            int ticks = idleTicksOf(resource);
            if (ticks >= IDLE_TICKS) result.add(new Idle(resource, lastEligible.get(resource), ticks));
        }
        return List.copyOf(result);
    }

    /** Olvida las cuentas, las pilas y lo ya avisado. Se llama al encender o apagar el módulo. */
    public void reset() {
        idleTicks.clear();
        lastEligible.clear();
        lastAmount.clear();
        warned.clear();
    }

    /**
     * El aviso de un veredicto: nombra los módulos, dice qué se ha medido y <b>apunta a los
     * sospechosos sin afirmar cuál es</b>. Todos los ajustes y valores de fábrica que se nombran
     * están verificados en las fuentes de {@code meteor-client:1.21.11-SNAPSHOT} y, donde son
     * nombres de bloque, contra las mappings de yarn 1.21.11+build.3; ninguno es supuesto.
     *
     * <p>Con un solo módulo se nombra <b>primero la causa inocente</b> ({@link #innocent}) y después
     * los sospechosos. Con varios -solo puede pasar con la obsidiana- se dice además, con todas las
     * letras, que el veredicto es conjunto y por qué no se puede repartir.
     */
    public static Msg reason(Idle idle) {
        PvpText material = material(idle.resource());
        if (!idle.joint()) {
            ManagedModule module = idle.modules().getFirst();
            return Msg.of(PvpText.IDLE_ALONE, "module", module.name(), "seconds", seconds(idle),
                "material", material, "innocent", innocent(module), "suspects", suspects(module));
        }

        List<String> names = new ArrayList<>();
        Object tails = null;
        for (ManagedModule module : idle.modules()) {
            names.add(module.name());
            Msg tail = Msg.of(PvpText.SUSPECTS_OF, "module", module.name(), "suspects", suspects(module));
            tails = tails == null ? tail : Msg.of(PvpText.JOIN_SEMICOLON, "first", tails, "rest", tail);
        }
        return Msg.of(PvpText.IDLE_TOGETHER, "modules", join(names), "seconds", seconds(idle),
            "material", material, "count", idle.modules().size(), "suspects", tails);
    }

    /** Los segundos que lleva el veredicto, como se leen en el aviso. */
    private static int seconds(Idle idle) {
        return idle.ticks() / TICKS_PER_SECOND;
    }

    /** "a, b y c", como se enumera en el idioma del jugador. */
    private static Object join(List<String> names) {
        if (names.size() == 1) return names.getFirst();
        Object head = names.getFirst();
        for (String name : names.subList(1, names.size() - 1)) {
            head = Msg.of(PvpText.JOIN_COMMA, "first", head, "rest", name);
        }
        return Msg.of(PvpText.JOIN_AND, "first", head, "second", names.getLast());
    }

    /**
     * La razón por la que <b>no</b> sería un fallo, que va delante de los sospechosos porque en
     * varios casos es la más probable con diferencia.
     *
     * <p>Las tres que no son "no hay posición" están verificadas en las fuentes: {@code Surround}
     * pone {@code complete = true} y deja de colocar cuando el surround está terminado, y con
     * {@code toggle-on-complete} en {@code false} de fábrica <b>se queda encendido para siempre</b>;
     * {@code HoleFiller} con {@code smart} encendido solo tapa huecos cerca de un objetivo, y puede
     * no haber ninguno; y {@code AutoWeb} solo coloca donde {@code isReplaceable()}, y una telaraña
     * no lo es, así que en cuanto la casilla prevista tiene telaraña deja de colocar ahí -contra
     * alguien arrinconado que sigue contando como "se aleja", la casilla prevista no cambia y no
     * vuelve a gastar ni una-.
     */
    private static PvpText innocent(ManagedModule module) {
        return switch (module.name()) {
            case "surround" -> PvpText.INNOCENT_SURROUND;
            case "hole-filler" -> PvpText.INNOCENT_HOLE_FILLER;
            case "auto-web" -> PvpText.INNOCENT_AUTO_WEB;
            default -> PvpText.INNOCENT_DEFAULT;
        };
    }

    /** A qué ajustes mirar, sin afirmar que el culpable esté entre ellos. */
    private static PvpText suspects(ManagedModule module) {
        return switch (module.name()) {
            case "crystal-aura" -> PvpText.SUSPECTS_CRYSTAL_AURA;
            case "auto-trap" -> PvpText.SUSPECTS_AUTO_TRAP;
            case "auto-web" -> PvpText.SUSPECTS_AUTO_WEB;
            case "auto-anvil" -> PvpText.SUSPECTS_AUTO_ANVIL;
            case "surround" -> PvpText.SUSPECTS_SURROUND;
            case "hole-filler" -> PvpText.SUSPECTS_HOLE_FILLER;
            default -> PvpText.SUSPECTS_DEFAULT;
        };
    }

    /** Cómo se llama lo que sale de esa pila, para el aviso. */
    private static PvpText material(Resource resource) {
        return switch (resource) {
            case CRYSTALS -> PvpText.MATERIAL_CRYSTALS;
            case OBSIDIAN -> PvpText.MATERIAL_OBSIDIAN;
            case WEBS -> PvpText.MATERIAL_WEBS;
            case ANVILS -> PvpText.MATERIAL_ANVILS;
            default -> PvpText.MATERIAL_OTHER;
        };
    }
}
