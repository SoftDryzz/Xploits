package com.xploits.bench.core;

import java.util.List;

/**
 * Task A2 requirement 1: the pacing, delay and stop condition of a scripted crystal attack (an opponent that
 * attacks with crystals, like a real client), plus which of a list of candidate cells to use. Modelled on the
 * existing {@code Attacker} script's cadence, generalised: a crystal is spawned every {@value #ATTACK_EVERY}
 * ticks (the invulnerability pace) starting {@value #FIRST_ATTACK} ticks after T0, then hit
 * {@value #ATTACK_DELAY} ticks after each spawn — like a client with ping. Pure and deterministic: the adapter
 * (a {@code Script}) drives {@link #phaseAt} from its own {@code sinceT0} and whether either combatant is
 * still alive, and supplies each candidate cell's raw damage (computed server-side with vanilla's own damage
 * path) to {@link #chooseCell}.
 */
public final class CrystalAttackPace {
    /** No crystal is spawned before this many ticks after T0. */
    public static final int FIRST_ATTACK = 20;
    /** A crystal is spawned every this many ticks after {@link #FIRST_ATTACK} — the invulnerability pace. */
    public static final int ATTACK_EVERY = 10;
    /** Ticks after a spawn the crystal is hit — like a client with ping. */
    public static final int ATTACK_DELAY = 2;

    private CrystalAttackPace() {
    }

    /** What the script does this tick. */
    public enum Phase {
        NONE, SPAWN, HIT
    }

    /**
     * The phase at {@code sinceT0} ticks after T0: {@link Phase#SPAWN} at {@value #FIRST_ATTACK}, then every
     * {@value #ATTACK_EVERY} ticks after; {@link Phase#HIT} {@value #ATTACK_DELAY} ticks after each spawn;
     * {@link Phase#NONE} at every other tick — including, whatever the tick, once either combatant is no
     * longer alive: the fight is over (task A2 requirement 1, "stops when the sparring is dead or out of
     * totems"). A dead sparring is never ticked by its own {@code Sparring.step} anyway; a dead target (our
     * player) must still stop a cycle that is already in flight (a spawn already sent, its hit still pending)
     * just as surely, so both are checked here rather than left to the caller to remember.
     *
     * @param sparringAlive whether the entity running this script is still alive
     * @param targetAlive   whether the player it is attacking is still alive
     */
    public static Phase phaseAt(int sinceT0, boolean sparringAlive, boolean targetAlive) {
        if (!sparringAlive || !targetAlive || sinceT0 < FIRST_ATTACK) return Phase.NONE;
        int cycle = (sinceT0 - FIRST_ATTACK) % ATTACK_EVERY;
        if (cycle == 0) return Phase.SPAWN;
        if (cycle == ATTACK_DELAY) return Phase.HIT;
        return Phase.NONE;
    }

    /**
     * The index of the candidate cell whose raw damage to us is highest; ties keep the first in the list
     * (task A2 requirement 1, deterministic).
     *
     * @throws IllegalArgumentException {@code rawDamageByCell} is empty: every candidate cell was filtered out
     *                                  (occupied) this cycle, which the caller must check for itself before
     *                                  calling this — there is nothing here to choose between
     */
    public static int chooseCell(List<Double> rawDamageByCell) {
        if (rawDamageByCell.isEmpty()) throw new IllegalArgumentException("no candidate cells to choose from");
        int best = 0;
        for (int i = 1; i < rawDamageByCell.size(); i++) {
            if (rawDamageByCell.get(i) > rawDamageByCell.get(best)) best = i;
        }
        return best;
    }
}
