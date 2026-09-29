package com.xploits.bench.core;

/**
 * Task A1 requirement 3, task A4 requirement 1: how a fight-mode run's outcome and net pops come out of
 * what happened in it. A side has lost once it dies, or once its last totem pops (every one of its fixed
 * supply used, task A1 requirement 1 and 2, {@link #outOfTotems}) — whichever happens first — identical
 * for our own player and the sparring, under either aura: {@code result} is 1 for a win (the sparring lost
 * first), -1 for a loss (we lost first), 0 for a draw (the time limit, neither side lost). Pure and
 * deterministic: the bench decides which of {@code weLost} / {@code sparringLost} to report from the tick
 * order, since a fight-mode run stops the instant either side loses, so the two are never both true in the
 * same judgement.
 */
public final class FightResult {
    private FightResult() {
    }

    /**
     * @throws IllegalArgumentException when both are true: a run that ends must have stopped at the first
     *                                  side to lose, so this pure core never has to pick a winner between them
     */
    public static int result(boolean weLost, boolean sparringLost) {
        if (weLost && sparringLost) {
            throw new IllegalArgumentException("both lost: the run must stop at the first loss, not judge both at once");
        }
        if (weLost) return -1;
        if (sparringLost) return 1;
        return 0;
    }

    /** {@code pops_dealt - pops_taken}: positive when we out-popped the sparring. */
    public static int netPops(int popsDealt, int popsTaken) {
        return popsDealt - popsTaken;
    }

    /**
     * Task A4 requirement 1: whether a side has used every one of its {@code totalTotems} totems, and so
     * has lost even while still alive (ca-exchange run 3: 8 pops dealt, the old, death-only {@code result}
     * still read 0). A fight's totem supply never refills past that point (task A1 requirement 1 and 2: the
     * offhand one plus the spares, for both our player and the sparring), so the side's next lethal hit,
     * whenever it lands, kills it for real; the bench does not wait for that hit before ending the run.
     * Pure: {@code pops} is that side's own running pop count for the run.
     */
    public static boolean outOfTotems(int pops, int totalTotems) {
        return pops >= totalTotems;
    }
}
