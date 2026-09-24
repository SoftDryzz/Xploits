package com.xploits.pvp.core;

import com.xploits.shared.core.i18n.Msg;

import java.util.List;

/**
 * What should be enabled this tick (spec §4, redesign §3).
 *
 * @param state    how the situation is reported; it can be OUT_OF_RESOURCES even when the physical phase is another
 * @param posture  the defensive axis of this tick (redesign §5), independent of the phase
 * @param enable   the <b>union</b> of what each axis asks for, already filtered by what you carry
 * @param skipped  what the situation asked for and cannot be sustained, with the reason
 * @param warnings warnings that are not omissions: things that are enabled anyway but that the
 *                 player has to know about. The case is {@code crystal-aura} without crystals
 *                 (redesign §7): it is the only managed module with a useful half at zero cost, the
 *                 autobreak, which is exactly what keeps you alive when you have nothing to answer
 *                 with, so it is not turned off for running out of crystals; a warning is given
 */
public record Plan(CombatState state, CombatPosture posture, List<ManagedModule> enable,
                   List<Skipped> skipped, List<Msg> warnings) {
    public Plan {
        enable = List.copyOf(enable);
        skipped = List.copyOf(skipped);
        warnings = List.copyOf(warnings);
    }
}
