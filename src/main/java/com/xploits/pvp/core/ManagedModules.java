package com.xploits.pvp.core;

import java.util.List;

/**
 * El catálogo de los módulos que el director dirige (spec §6, ampliado por el rediseño §5). Los
 * nombres están verificados contra las fuentes de meteor-client 1.21.11. Los módulos "de siempre"
 * (auto-totem, auto-armor, offhand, auto-weapon) NO están aquí a propósito: son del jugador y el
 * director no los toca.
 *
 * <p>Se divide en dos mitades, una por cada eje del rediseño §3: los <b>ofensivos</b>, que se eligen
 * por la fase del objetivo, y los <b>defensivos</b>, que se eligen por tu postura. {@code surround}
 * pertenece ya solo a la postura: salió de {@code ACERCAMIENTO}, donde te encerraba en obsidiana
 * mientras corrías, te recolocaba al centro del bloque peleando contra tu input y gastaba la
 * obsidiana que {@code auto-trap} iba a necesitar.
 *
 * <p>{@code self-trap}, {@code self-web} y {@code burrow} quedan fuera del catálogo a propósito
 * (§5): te encierran, y si el criterio se equivoca te inmoviliza tu propio cliente en una pelea que
 * ibas ganando.
 */
public final class ManagedModules {
    // El último parámetro es turnsItselfOff (spec §7): crystal-aura y auto-web solo se apagan
    // porque el jugador los apaga a mano; los otros cuatro ofensivos pueden apagarse solos sin que
    // el jugador los toque -no los cuatro por el mismo motivo, ni todos de fábrica: ver la tabla
    // de la spec §7-, y ModuleLedger necesita saberlo para no confundir ese apagado con uno a mano.
    public static final ManagedModule CRYSTAL_AURA = new ManagedModule("crystal-aura", Resource.CRYSTALS, 1, false);
    public static final ManagedModule AUTO_TRAP = new ManagedModule("auto-trap", Resource.OBSIDIAN, 8, true);
    public static final ManagedModule AUTO_WEB = new ManagedModule("auto-web", Resource.WEBS, 1, false);
    public static final ManagedModule SURROUND = new ManagedModule("surround", Resource.OBSIDIAN, 4, true);
    public static final ManagedModule AUTO_ANVIL = new ManagedModule("auto-anvil", Resource.ANVILS, 1, true);
    public static final ManagedModule AUTO_CITY = new ManagedModule("auto-city", Resource.PICKAXE, 1, true);

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

    private ManagedModules() {}
}
