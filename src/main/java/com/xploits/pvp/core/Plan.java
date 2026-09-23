package com.xploits.pvp.core;

import com.xploits.shared.core.i18n.Msg;

import java.util.List;

/**
 * Qué debería estar encendido este tick (spec §4, rediseño §3).
 *
 * @param state    cómo se informa la situación; puede ser SIN_RECURSOS aunque la fase física sea otra
 * @param posture  el eje defensivo de este tick (rediseño §5), independiente de la fase
 * @param enable   la <b>unión</b> de lo que pide cada eje, ya filtrada por lo que llevas encima
 * @param skipped  lo que la situación pedía y no se puede sostener, con el motivo
 * @param warnings avisos que no son omisiones: cosas que se encienden igual pero de las que el
 *                 jugador tiene que enterarse. El caso es {@code crystal-aura} sin cristales
 *                 (rediseño §7): es el único módulo dirigido con una mitad útil a coste cero, el
 *                 autobreak, que es justo lo que te mantiene vivo cuando no tienes con qué
 *                 responder, así que no se apaga por quedarte sin cristales; se avisa
 */
public record Plan(CombatState state, CombatPosture posture, List<ManagedModule> enable,
                   List<Skipped> skipped, List<Msg> warnings) {
    public Plan {
        enable = List.copyOf(enable);
        skipped = List.copyOf(skipped);
        warnings = List.copyOf(warnings);
    }
}
