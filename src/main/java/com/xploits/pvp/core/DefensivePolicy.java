package com.xploits.pvp.core;

import java.util.ArrayList;
import java.util.List;

/**
 * El eje defensivo del rediseño (§5): se deriva de <b>ti</b>, no del objetivo, y no compite con la
 * fase. Los módulos que acaban encendidos son la unión de lo que pide cada eje (§3).
 *
 * <p>Es la decisión que antes no existía, y la que §10 pedía: <i>ninguna decisión se toma con un
 * proxy si el dato real está a mano</i>. Aquí el dato real es doble y el cliente lo sabe siempre,
 * en todos los servidores: {@code PlayerUtils.getTotalHealth()} (vida + absorción) y
 * {@code PlayerUtils.possibleHealthReductions()} (el daño que <b>ya</b> te apunta: cristales
 * colocados, jugadores con espada a &le;5, camas en el Nether y caída). Es el mismo par que usan
 * {@code AutoTotem}, {@code Offhand} y {@code AutoLog} para decidir lo mismo.
 */
public final class DefensivePolicy {
    /**
     * Vida que te tiene que quedar, descontando lo que ya te apunta, para seguir {@code TRANQUILO}
     * (§5). La spec deja el número abierto; sale de cuánto quita un cristal y de cuánto tardas en
     * responder.
     *
     * <p>Un cristal a bocajarro contra netherita encantada quita del orden de 8 puntos.
     * {@code possibleHealthReductions()} ya cuenta los cristales <b>colocados</b>, así que el margen
     * solo tiene que cubrir el que todavía no está puesto cuando miras, es decir un ciclo de
     * cristal de reacción; §9 mide medio segundo en 2-5 ciclos, o sea 4-10 ticks por ciclo, y los
     * módulos defensivos necesitan unos cuantos ticks más para colocar algo. Un ciclo y medio de
     * margen son 12 puntos: con la vida llena y nada apuntándote quedan 8 de holgura, así que la
     * postura no salta por el mero hecho de estar peleando, y salta en cuanto hay dos cristales ya
     * colocados sobre ti o estás por debajo de 12 con uno puesto -que es justo el momento en el que
     * un {@code hole-filler} o un {@code anti-anvil} deciden si mueres-.
     *
     * <p>Que el umbral sea generoso es deliberado y barato: los cuatro módulos de {@code AMENAZADO}
     * no te inmovilizan ni gastan nada salvo la obsidiana suelta de {@code hole-filler}, así que
     * pasarse cuesta mucho menos que quedarse corto.
     */
    public static final double THREAT_MARGIN = 12.0;

    private DefensivePolicy() {}

    /**
     * {@code AMENAZADO} cuando el daño que ya te apunta te dejaría por debajo del margen (§5). La
     * comparación es menor-o-igual: justo en el umbral ya cuenta como amenaza.
     */
    public static CombatPosture postureFor(CombatSnapshot snapshot) {
        double remaining = snapshot.selfTotalHealth() - snapshot.incomingDamage();
        return remaining <= THREAT_MARGIN ? CombatPosture.AMENAZADO : CombatPosture.TRANQUILO;
    }

    /**
     * Qué pide la postura (§5). {@code TRANQUILO} no pide nada; {@code AMENAZADO} pide los tres
     * {@code anti-} y el {@code hole-filler}, que tapan formas concretas de matarte sin
     * inmovilizarte, y además {@code surround} <b>solo</b> si estás en un agujero y en el suelo.
     *
     * <p>Las dos condiciones de {@code surround} son suyas, no un adorno: con
     * {@code toggle-on-y-change} en {@code true} de fábrica y una llamada a
     * {@code PlayerUtils.centerPlayer()} mientras el surround esté incompleto, encenderlo mientras
     * te mueves te recoloca y se apaga solo en bucle. Es un módulo defensivo de agujero y ese es su
     * único sitio.
     */
    public static List<ManagedModule> modulesFor(CombatPosture posture, CombatSnapshot snapshot) {
        if (posture == CombatPosture.TRANQUILO) return List.of();

        List<ManagedModule> modules = new ArrayList<>(List.of(
            ManagedModules.HOLE_FILLER, ManagedModules.ANTI_ANVIL,
            ManagedModules.ANTI_BED, ManagedModules.ANTI_ANCHOR));
        if (snapshot.selfInHole() && snapshot.selfOnGround()) modules.add(ManagedModules.SURROUND);
        return List.copyOf(modules);
    }
}
