package com.xploits.pvp.core;

import java.util.ArrayList;
import java.util.List;

/**
 * The defensive axis of the redesign (§5): it is derived from <b>you</b>, not from the target, and it
 * does not compete with the phase. The modules that end up on are the union of what each axis asks for (§3).
 *
 * <p>It is the decision that did not exist before, and the one §10 asked for: <i>no decision is taken
 * with a proxy if the real data is at hand</i>. Here the real data is twofold and the client always
 * knows it, on every server: {@code PlayerUtils.getTotalHealth()} (health + absorption) and
 * {@code PlayerUtils.possibleHealthReductions()} (the damage <b>already</b> aimed at you: placed
 * crystals, players with a sword at &le;5, beds in the Nether and falling). It is the same pair
 * {@code AutoTotem}, {@code Offhand} and {@code AutoLog} use to decide the same thing.
 */
public final class DefensivePolicy {
    /**
     * Health you must have left, after subtracting what is already aimed at you, to stay {@code CALM}
     * (§5). The spec leaves the number open; it comes from how much a crystal takes and how long you
     * take to respond.
     *
     * <p>A point-blank crystal against enchanted netherite takes around 8 points.
     * {@code possibleHealthReductions()} already counts the <b>placed</b> crystals, so the margin
     * only has to cover the one not yet placed when you look, that is one reaction crystal
     * cycle; §9 measures half a second in 2-5 cycles, that is 4-10 ticks per cycle, and the
     * defensive modules need a few more ticks to place something. A cycle and a half of
     * margin is 12 points: with full health and nothing aimed at you there are 8 of slack left, so the
     * posture does not trip merely because you are fighting, and it trips as soon as there are two
     * crystals already placed on you or you are below 12 with one placed -which is exactly the moment in
     * which a {@code hole-filler} or an {@code anti-anvil} decides whether you die-.
     *
     * <p>A generous threshold is deliberate and cheap: the four {@code THREATENED} modules
     * neither immobilize you nor spend anything except the loose obsidian of {@code hole-filler}, so
     * overshooting costs much less than falling short.
     */
    public static final double THREAT_MARGIN = 12.0;

    private DefensivePolicy() {}

    /** The same with the default margin, {@link #THREAT_MARGIN}. */
    public static CombatPosture postureFor(CombatSnapshot snapshot) {
        return postureFor(snapshot, THREAT_MARGIN);
    }

    /**
     * {@code THREATENED} when the damage already aimed at you would leave you below the margin (§5). The
     * comparison is less-or-equal: right at the threshold it already counts as a threat.
     *
     * <p>The margin comes in as a parameter because the spec leaves it open and as a module setting
     * ({@code threat-margin}); {@link #THREAT_MARGIN} is only its default value. It is the same treatment
     * {@code approach-distance} gets in {@link CombatDirector#tick}: the player sets the number, the
     * comparison still belongs to the core.
     */
    public static CombatPosture postureFor(CombatSnapshot snapshot, double threatMargin) {
        double remaining = snapshot.selfTotalHealth() - snapshot.incomingDamage();
        return remaining <= threatMargin ? CombatPosture.THREATENED : CombatPosture.CALM;
    }

    /**
     * What the posture asks for (§5). {@code CALM} asks for nothing; {@code THREATENED} asks for the
     * three {@code anti-} modules and the {@code hole-filler}, which cover specific ways of killing you
     * without immobilizing you, and also {@code surround} <b>only</b> if you are in a hole, on the ground
     * and with your height still.
     *
     * <p>The three conditions of {@code surround} are its own, not decoration: with
     * {@code toggle-on-y-change} at {@code true} by default and a call to
     * {@code PlayerUtils.centerPlayer()} while the surround is incomplete, enabling it while
     * you move re-centers you and it turns itself off in a loop. It is a defensive hole module and that
     * is its only place.
     *
     * <p>The third -{@code selfYChanged}- came with critical C2 and is what makes the
     * {@code turnsItselfOff == false} of {@link ManagedModules#SURROUND} honest. {@code Surround} checks
     * {@code prevY != getY()} in {@code TickEvent.Pre}, that is <b>one tick after</b> you
     * moved vertically, and being on the ground and inside the hole does not exclude that tick: landing
     * in the hole leaves you exactly there -on the ground, inside and with Y just changed-, the
     * posture asked for it, the ledger turned it on and the module turned itself off on the next tick.
     * That shutdown is indistinguishable from you turning it off, and that is why it had to stop being
     * provoked. Nothing is lost: on those ticks the module would have turned itself off anyway.
     */
    public static List<ManagedModule> modulesFor(CombatPosture posture, CombatSnapshot snapshot) {
        if (posture == CombatPosture.CALM) return List.of();

        List<ManagedModule> modules = new ArrayList<>(List.of(
            ManagedModules.HOLE_FILLER, ManagedModules.ANTI_ANVIL,
            ManagedModules.ANTI_BED, ManagedModules.ANTI_ANCHOR));
        if (snapshot.selfInHole() && snapshot.selfOnGround() && !snapshot.selfYChanged()) {
            modules.add(ManagedModules.SURROUND);
        }
        return List.copyOf(modules);
    }
}
