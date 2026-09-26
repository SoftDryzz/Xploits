package com.xploits.pvp.crystal.core;

import java.util.Objects;

/**
 * One thing for the adapter to do now, as Meteor would do it (lines 824-892, 1032-1086). With
 * {@code rotate} on, the adapter goes through {@code Rotations.rotate(..., 50, callback)} and acts in the
 * callback; with it off, it acts at once.
 *
 * <ul>
 *   <li>{@link Decision.Kind#BREAK}: attack the crystal {@link Decision#ref}, swing {@link #hand} as
 *   {@code swing-mode} says, then call {@link CrystalBrain#attackSent()}.</li>
 *   <li>{@link Decision.Kind#PLACE}: if {@link #switchToCrystals}, swap to the end crystals in the hotbar
 *   (no swap back); place on the base {@link Decision#ref} with {@link #hand}, swing it, then call
 *   {@link CrystalBrain#placed(long, int)}. If there are no crystals to place with by then, do nothing.</li>
 *   <li>{@link Decision.Kind#SWAP_WEAPON}: swap to the first item in the hands or hotbar that hurts the
 *   crystal ({@code InvUtils.findInHotbar}), and do not attack.</li>
 * </ul>
 *
 * @param decision         what and why
 * @param hand             the hand holding the end crystals: the offhand if it holds them, the main hand
 *                         otherwise (Meteor, lines 885-886 and 1043); the main hand for a swap
 * @param switchToCrystals whether to swap to the crystals first: {@code auto-switch} Normal and neither hand
 *                         holds them (line 1041)
 */
public record Action(Decision decision, Hand hand, boolean switchToCrystals) {
    public enum Hand { MAIN, OFF }

    public Action {
        Objects.requireNonNull(decision, "decision");
        Objects.requireNonNull(hand, "hand");
        if (decision.kind() == Decision.Kind.NONE) throw new IllegalArgumentException("an action does something");
        if (switchToCrystals && decision.kind() != Decision.Kind.PLACE) {
            throw new IllegalArgumentException("only a placement swaps to crystals");
        }
    }
}
