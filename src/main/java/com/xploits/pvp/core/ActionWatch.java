package com.xploits.pvp.core;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
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
 * haber posición válida ahora mismo, o el {@code surround} puede estar ya completo-, y apagarlo
 * sería peor que avisar: el módulo dejaría de estar listo para el tick en el que sí haya posición.
 * Lo único que hace es decirlo <b>una vez</b>, nombrando al módulo y apuntando a los sospechosos
 * <b>sin afirmar cuál es</b>, y volver a armarse cuando la situación cambia.
 */
public final class ActionWatch {
    /** Ticks por segundo del juego, para decir el margen en segundos en el aviso. */
    private static final int TICKS_PER_SECOND = 20;

    /**
     * Ticks seguidos cumpliendo las cuatro condiciones sin que el recurso baje antes de avisar.
     * Sesenta, tres segundos.
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

    /** Ticks seguidos que cada vigilado lleva queriéndose, encendido, con objetivo y sin gastar. */
    private final Map<ManagedModule, Integer> idleTicks = new LinkedHashMap<>();

    /** Cuánto había de cada recurso vigilado en el tick anterior. */
    private final Map<Resource, Integer> lastAmount = new EnumMap<>(Resource.class);

    /** De qué módulos ya se avisó, para decirlo una vez y no en bucle. */
    private final Set<ManagedModule> warned = new LinkedHashSet<>();

    /**
     * Un tick de vigilancia. Devuelve los módulos de los que <b>hay que avisar ahora</b>, es decir
     * los que acaban de cumplir el margen y de los que todavía no se había avisado.
     *
     * <p>Las cuatro condiciones que tienen que cumplirse a la vez para que el tick cuente son: el
     * plan lo <b>quiere</b>, está <b>encendido de verdad</b>, hay <b>objetivo</b> y hay
     * <b>recurso suficiente</b> (su {@link ManagedModule#minimum()}). Que falle cualquiera de ellas
     * no pausa la cuenta: la <b>reinicia</b>, y además rearma el aviso. Es lo conservador y es lo
     * que hace falta -la afirmación que se va a hacer es "lleva tres segundos seguidos sin gastar
     * pudiendo gastar", y un tick en el que no podía gastar la rompe entera-.
     *
     * <p>El objetivo se exige también a los dos defensivos, aunque {@code surround} y
     * {@code hole-filler} no lo necesiten para colocar: sin nadie delante, que no gasten es lo
     * normal, y contar esos ticks solo produciría avisos de nada.
     *
     * <p><b>Cualquier movimiento del recurso reinicia, no solo una bajada.</b> Si baja, el módulo
     * está actuando y no hay nada que decir. Si sube -recoges obsidiana, sacas telarañas de la
     * mochila- la serie deja de comparar lo mismo, y una subida puede además tapar un gasto (gastas
     * una y recoges dos). No se puede afirmar que no gasta mientras la pila se mueve, así que no se
     * afirma.
     *
     * <p><b>La obsidiana es de tres, y eso obliga a reiniciar a los tres.</b> {@code auto-trap},
     * {@code surround} y {@code hole-filler} beben de la misma pila (I3), y el inventario no dice
     * quién colocó: si la obsidiana baja, la medida no puede atribuir el gasto a ninguno, así que
     * se le concede a todos. Es el lado barato del sesgo -callar de más es un aviso que se retrasa;
     * hablar de más es un aviso falso-, y también el motivo por el que el aviso de esos dos nombra
     * primero la causa inocente.
     *
     * @param snapshot la situación de este tick, para el objetivo y para las cuentas del inventario
     * @param wanted   los nombres de módulo que el plan de este tick quiere encendidos
     * @param active   los nombres de módulo que están encendidos de verdad ahora mismo
     */
    public List<ManagedModule> update(CombatSnapshot snapshot, Set<String> wanted, Set<String> active) {
        Set<Resource> moved = EnumSet.noneOf(Resource.class);
        Set<Resource> watchedResources = EnumSet.noneOf(Resource.class);
        for (ManagedModule module : WATCHED) watchedResources.add(module.needs());
        for (Resource resource : watchedResources) {
            Integer before = lastAmount.put(resource, snapshot.amountOf(resource));
            if (before != null && before != snapshot.amountOf(resource)) moved.add(resource);
        }

        List<ManagedModule> newlyIdle = new ArrayList<>();
        for (ManagedModule module : WATCHED) {
            if (moved.contains(module.needs()) || !couldHaveActed(module, snapshot, wanted, active)) {
                idleTicks.remove(module);
                warned.remove(module);
                continue;
            }

            int ticks = idleTicks.merge(module, 1, Integer::sum);
            if (ticks >= IDLE_TICKS && warned.add(module)) newlyIdle.add(module);
        }
        return List.copyOf(newlyIdle);
    }

    /** Las cuatro condiciones de un tick que cuenta. */
    private static boolean couldHaveActed(ManagedModule module, CombatSnapshot snapshot,
                                          Set<String> wanted, Set<String> active) {
        return snapshot.hasTarget()
            && wanted.contains(module.name())
            && active.contains(module.name())
            && snapshot.amountOf(module.needs()) >= module.minimum();
    }

    /** Ticks que {@code module} lleva sin gastar pudiendo gastar; cero si no está en esa situación. */
    public int idleTicksOf(ManagedModule module) {
        return idleTicks.getOrDefault(module, 0);
    }

    /**
     * Los vigilados que ya han cumplido el margen entero y siguen sin gastar, en el orden del
     * catálogo. Es lo que enseña {@code .xploits pvp}: el aviso se dice una vez, pero la situación
     * dura, y tiene que poder consultarse mientras dura.
     */
    public List<ManagedModule> idle() {
        List<ManagedModule> result = new ArrayList<>();
        for (ManagedModule module : WATCHED) {
            if (idleTicksOf(module) >= IDLE_TICKS) result.add(module);
        }
        return List.copyOf(result);
    }

    /** Olvida las cuentas, las pilas y lo ya avisado. Se llama al encender o apagar el módulo. */
    public void reset() {
        idleTicks.clear();
        lastAmount.clear();
        warned.clear();
    }

    /**
     * El aviso de un módulo: nombra el módulo, dice qué se ha medido y <b>apunta a los sospechosos
     * sin afirmar cuál es</b>. Todos los ajustes y valores de fábrica que se nombran están
     * verificados en las fuentes de {@code meteor-client:1.21.11-SNAPSHOT}, no supuestos.
     *
     * <p>Los dos de la obsidiana nombran primero la causa inocente -el surround ya completo, no
     * haber ningún hueco- porque en su caso es la más probable con diferencia: {@code Surround}
     * sigue encendido cuando termina ({@code toggle-on-complete} es {@code false} de fábrica) y
     * desde ese momento no coloca nada, legítimamente y para siempre.
     */
    public static String reason(ManagedModule module) {
        String head = module.name() + " lleva " + IDLE_TICKS / TICKS_PER_SECOND
            + " s encendido, con enemigo delante y " + material(module) + " de sobra, sin gastar nada. ";
        return head + switch (module.name()) {
            case "crystal-aura" -> "No lo apago -puede que no haya posición válida ahora mismo-, pero si no es "
                + "eso, los sospechosos son min-damage (6 de fábrica: contra netherita con Protección IV casi "
                + "ninguna posición llega a 6 de daño) y support (Disabled de fábrica: sin él no coloca donde "
                + "no haya ya un bloque debajo).";
            case "auto-trap" -> "No lo apago -puede que no haya posición válida ahora mismo-, pero si no es "
                + "eso, los sospechosos son whitelist (de fábrica solo obsidiana y bloque de netherita), "
                + "place-range y walls-range (4 de fábrica, y manda el segundo en cuanto haya algo por medio) "
                + "y top-blocks/bottom-blocks.";
            case "auto-web" -> "No lo apago -puede que no haya posición válida ahora mismo-, pero si no es "
                + "eso, los sospechosos son place-range y walls-range (4 de fábrica) y ticks-to-predict (10 de "
                + "fábrica: telaraña donde estará dentro de medio segundo, no donde está).";
            case "auto-anvil" -> "No lo apago -puede que no haya posición válida ahora mismo-, pero si no es "
                + "eso, los sospechosos son height (2 de fábrica: necesita el hueco libre sobre su cabeza) y "
                + "delay (10 ticks de fábrica entre yunque y yunque).";
            case "surround" -> "Lo más probable es que el surround ya esté completo, y entonces no hay nada "
                + "que colocar y está bien así -no lo apago-; si no es eso, los sospechosos son blocks (su "
                + "lista trae tres bloques y yo solo te cuento la obsidiana) y only-on-ground.";
            case "hole-filler" -> "Lo más probable es que no haya ningún hueco que tapar, y entonces está bien "
                + "así -no lo apago-; si no es eso, los sospechosos son only-moving (encendido de fábrica, y en "
                + "las fuentes descarta al que SE MUEVE, no al que está quieto), feet-range (1,5 de fábrica "
                + "desde los pies del objetivo, ya predichos) e ignore-safe.";
            default -> "No lo apago: mira sus ajustes de rango y de posición.";
        };
    }

    /** Cómo se llama en español lo que ese módulo gasta, para el aviso. */
    private static String material(ManagedModule module) {
        return switch (module.needs()) {
            case CRYSTALS -> "cristales";
            case OBSIDIAN -> "obsidiana";
            case WEBS -> "telarañas";
            case ANVILS -> "yunques";
            default -> "material";
        };
    }
}
