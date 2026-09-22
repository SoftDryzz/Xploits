package com.xploits.pvp.core;

import java.util.List;

/**
 * El catálogo de los módulos que el director dirige (spec §6, ampliado por el rediseño §5). Los
 * nombres están verificados contra las fuentes de meteor-client 1.21.11. Los módulos "de siempre"
 * (auto-totem, auto-armor, offhand, auto-weapon) NO están aquí a propósito: son del jugador y el
 * director no los toca.
 *
 * <p>Se divide en dos mitades, una por cada eje del rediseño §3: los <b>ofensivos</b>, que se eligen
 * por la fase del objetivo, y los <b>defensivos</b> ({@link #DEFENSIVE}), que se eligen por tu
 * postura. {@code surround} pertenece ya solo a la postura: salió de {@code ACERCAMIENTO}, donde te
 * encerraba en obsidiana mientras corrías, te recolocaba al centro del bloque peleando contra tu
 * input y gastaba la obsidiana que {@code auto-trap} iba a necesitar.
 *
 * <p>La división no es decorativa: hay estructuras -la memoria de recursos del director y la
 * memoria de lo que el jugador soltó a mano del {@link ModuleLedger}- que estaban escritas para un
 * solo eje y se aplicaban a los dos (importantes I4 e I6). Saber de qué eje es cada módulo es lo
 * que permite indexar cada una por el suyo.
 *
 * <p>{@code self-trap}, {@code self-web} y {@code burrow} quedan fuera del catálogo a propósito
 * (§5): te encierran, y si el criterio se equivoca te inmoviliza tu propio cliente en una pelea que
 * ibas ganando.
 */
public final class ManagedModules {
    // El último parámetro es turnsItselfOff (spec §7): crystal-aura y auto-web solo se apagan
    // porque el jugador los apaga a mano; los otros tres ofensivos pueden apagarse solos sin que
    // el jugador los toque -no los tres por el mismo motivo, ni todos de fábrica: ver la tabla
    // de la spec §7-, y ModuleLedger necesita saberlo para no confundir ese apagado con uno a mano.
    public static final ManagedModule CRYSTAL_AURA = new ManagedModule("crystal-aura", Resource.CRYSTALS, 1, false);
    public static final ManagedModule AUTO_TRAP = new ManagedModule("auto-trap", Resource.OBSIDIAN, 8, true);
    public static final ManagedModule AUTO_WEB = new ManagedModule("auto-web", Resource.WEBS, 1, false);
    public static final ManagedModule AUTO_ANVIL = new ManagedModule("auto-anvil", Resource.ANVILS, 1, true);
    public static final ManagedModule AUTO_CITY = new ManagedModule("auto-city", Resource.PICKAXE, 1, true);

    /**
     * {@code surround} va con {@code turnsItselfOff == false} desde el crítico C2, y eso es lo que
     * te devuelve el poder de apagarlo a mano.
     *
     * <p>Con la marca en {@code true} el antirrebote de §8 no se le aplicaba: el ledger soltaba la
     * propiedad y lo retomaba en la segunda pasada del mismo tick, así que cada vez que lo apagabas
     * volvía dentro del mismo tick y la única salida era apagar {@code auto-pvp} entero.
     *
     * <p>La marca solo se sostiene junto con la condición que la postura añadió a la vez (§5): no
     * pedir {@code surround} mientras tu Y esté cambiando. Verificado contra {@code Surround.java}
     * de {@code meteor-client:1.21.11-SNAPSHOT}, sus tres autoapagados son
     * {@code toggle-on-y-change} ({@code defaultValue(true)}), {@code toggle-on-complete}
     * ({@code defaultValue(false)}) y {@code toggle-on-death} ({@code defaultValue(true)}). Con los
     * ajustes de fábrica queda solo el primero, y ese dispara en {@code TickEvent.Pre} con
     * {@code prevY != getY()}, es decir <b>un tick después</b> del movimiento: por eso no bastaba
     * el {@code selfOnGround && selfInHole} de la postura -cuando aterrizas en el agujero ya estás
     * en el suelo y dentro, la postura lo pedía, y el módulo se apagaba solo al tick siguiente-.
     * Con {@code selfYChanged} delante, ese tick queda excluido y un apagado observado solo puede
     * ser tuyo. {@code toggle-on-death} no cuenta: morir apaga {@code auto-pvp} y suelta todo.
     */
    public static final ManagedModule SURROUND = new ManagedModule("surround", Resource.OBSIDIAN, 4, false);

    // Los cuatro del eje defensivo (rediseño §5). Ninguno te inmoviliza: tapan formas concretas de
    // matarte. hole-filler es el único que gasta -coloca bloques-, y le basta con una obsidiana:
    // tapar un solo hueco ya sirve de algo, al contrario que auto-trap, que necesita el trap
    // entero. Los tres anti- no colocan nada, solo escuchan y reaccionan, así que su recurso es
    // NONE con mínimo cero y el filtro de recursos no puede quitártelos nunca.
    //
    // Los cuatro van con turnsItselfOff == false: son pasivos, sin un "ya está, me apago" como el
    // de auto-trap tras colocar el trap ni un toggle-on-* como el de surround. Desde §8 la marca
    // importa poco: el antirrebote de ModuleLedger ya absorbe un apagado suelto, así que un
    // parpadeo que no fuera del jugador tampoco los daría por soltados.
    public static final ManagedModule HOLE_FILLER = new ManagedModule("hole-filler", Resource.OBSIDIAN, 1, false);
    public static final ManagedModule ANTI_ANVIL = new ManagedModule("anti-anvil", Resource.NONE, 0, false);
    public static final ManagedModule ANTI_BED = new ManagedModule("anti-bed", Resource.NONE, 0, false);
    public static final ManagedModule ANTI_ANCHOR = new ManagedModule("anti-anchor", Resource.NONE, 0, false);

    public static final List<ManagedModule> ALL =
        List.of(CRYSTAL_AURA, AUTO_TRAP, AUTO_WEB, SURROUND, AUTO_ANVIL, AUTO_CITY,
            HOLE_FILLER, ANTI_ANVIL, ANTI_BED, ANTI_ANCHOR);

    /**
     * Los del eje defensivo (§5): los cinco que pide la <b>postura</b>, no la fase. Sirve para que
     * las dos memorias del sistema se indexen cada una por su eje (importantes I4 e I6): la de
     * recursos del director, que se congela mientras la fase es {@code SIN_COMBATE} aunque el eje
     * defensivo sí decida ahí, y la de lo soltado a mano del {@link ModuleLedger}, que se olvidaba
     * al cambiar la fase ofensiva aunque lo soltado fuera defensivo y tú no hubieras cambiado nada.
     */
    public static final List<ManagedModule> DEFENSIVE =
        List.of(HOLE_FILLER, SURROUND, ANTI_ANVIL, ANTI_BED, ANTI_ANCHOR);

    /**
     * El orden en el que los módulos se reparten un recurso compartido (importante I3).
     *
     * <p>Tres colocadores viven de la misma pila de obsidiana -{@code hole-filler} (1),
     * {@code surround} (4) y {@code auto-trap} (8)- y el filtro de recursos los comparaba uno a uno
     * contra el total. En un agujero, amenazado y con el enemigo encima, los tres suben a la vez:
     * con ocho obsidianas piden trece entre los tres, los tres hacen swap a la misma pila el mismo
     * tick y <b>ninguno completa su trabajo</b>, sin que {@code skipped} dijera nada.
     *
     * <p>El orden es <b>defensivo antes que ofensivo y barato antes que caro</b>, y las dos reglas
     * coinciden. Manda §10 -sesgar hacia seguir vivo-: con ocho obsidianas, tapar el hueco por el
     * que te van a cristalear (1) y encerrarte los pies (4) te dejan vivo y todavía sobran tres;
     * gastarlas en el trap del otro (8) te deja sin las dos cosas. Y el orden por precio es el que
     * más trabajos completos saca de la misma pila.
     *
     * <p>Los que no aparecen aquí no comparten con nadie -cada uno es el único consumidor de su
     * recurso, y los tres {@code anti-} no consumen- así que el orden no les afecta.
     */
    public static final List<ManagedModule> SHARED_RESOURCE_PRIORITY =
        List.of(HOLE_FILLER, SURROUND, AUTO_TRAP);

    /** Si el módulo es del eje defensivo (§5), es decir si lo pide la postura y no la fase. */
    public static boolean isDefensive(ManagedModule module) {
        return DEFENSIVE.contains(module);
    }

    /** Lo mismo por nombre, para el {@link ModuleLedger}, que solo maneja nombres. */
    public static boolean isDefensive(String name) {
        for (ManagedModule module : DEFENSIVE) {
            if (module.name().equals(name)) return true;
        }
        return false;
    }

    private ManagedModules() {}
}
