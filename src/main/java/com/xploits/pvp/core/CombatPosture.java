package com.xploits.pvp.core;

/**
 * The defensive axis of the redesign (§3): what is happening to <b>you</b>, regardless of which
 * phase the target is in.
 *
 * <p>The mistake it fixes is having put everything into a single {@code enum}: "I am being crystalled"
 * and "he is surrounded" are true at the same time, and one enum forces a choice between attacking and
 * defending yourself. The modules that get enabled are the <b>union</b> of what each axis asks for.
 */
public enum CombatPosture {
    /** The damage already aimed at you does not leave you below the margin: there is nothing to cover. */
    CALM,
    /** What is already placed against you would leave you below the margin (§5). */
    THREATENED
}
