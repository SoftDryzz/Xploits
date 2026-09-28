package com.xploits.bench.core;

/**
 * Task A1 requirement 3: how a fight-mode run's outcome and net pops come out of what happened in it.
 * {@code result} is 1 for a win (the sparring died first, whether it ran dry of totems or was hit dead
 * outright), -1 for a loss (we died), 0 for a draw (the time limit, neither dead). Pure and deterministic:
 * the bench decides which of {@code weDied} / {@code sparringDied} to report from the tick order, since a
 * fight-mode run stops the instant either happens, so the two are never both true in the same judgement.
 */
public final class FightResult {
    private FightResult() {
    }

    /**
     * @throws IllegalArgumentException when both are true: a run that ends must have stopped at the first
     *                                  death, so this pure core never has to pick a winner between them
     */
    public static int result(boolean weDied, boolean sparringDied) {
        if (weDied && sparringDied) {
            throw new IllegalArgumentException("both died: the run must stop at the first death, not judge both at once");
        }
        if (weDied) return -1;
        if (sparringDied) return 1;
        return 0;
    }

    /** {@code pops_dealt - pops_taken}: positive when we out-popped the sparring. */
    public static int netPops(int popsDealt, int popsTaken) {
        return popsDealt - popsTaken;
    }
}
