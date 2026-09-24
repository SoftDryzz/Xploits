package com.xploits.pvp.core;

/**
 * A Meteor combat module that the director manages (spec §6).
 *
 * @param name           the exact name it shows in the ClickGUI
 * @param needs          which resource it needs to be of any use
 * @param minimum        how much of it is needed at least
 * @param turnsItselfOff whether this module can turn itself off, by Meteor's design, without the
 *                       player touching anything (spec §7) — the three are not flagged for the same
 *                       reason, nor all out of the box: {@code auto-trap} after placing the trap
 *                       successfully, with {@code self-toggle} on by default; {@code auto-city} out
 *                       of the box and with no setting involved, if it finds no target, block or
 *                       pickaxe (even inside its own {@code onActivate()}) and also after mining
 *                       successfully; and {@code auto-anvil}, flagged as a conservative decision even
 *                       though the {@code toggle()} that turns it off when the target's head is empty
 *                       sits behind {@code toggle-on-break}, which is {@code false} by default — with
 *                       the default settings {@code auto-anvil} does not turn itself off.
 *                       {@link ModuleLedger} uses this flag so as not to mistake that automatic
 *                       shutdown for the player releasing it by hand.
 *                       <p>{@code surround} <b>no longer carries it</b> (critical C2): carrying it
 *                       took away the debounce of §8 and with it the ability to turn it off by hand.
 *                       Its only self-shutdown with the default settings is {@code toggle-on-y-change},
 *                       and that case is excluded upstream, in the posture, which does not ask for it
 *                       while your Y is changing.
 */
public record ManagedModule(String name, Resource needs, int minimum, boolean turnsItselfOff) {}
