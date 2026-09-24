package com.xploits.elytra.core;

import java.util.List;

/**
 * Decides when to swap the worn elytra and for which one (spec §4). It keeps three things between
 * decisions: when the last swap was, so as not to repeat it while the chest slot updates; at what
 * percentage the last no-spare warning was given, so as not to warn twenty times a second; and
 * when that last warning was, to rearm it by time if the worn elytra changes without the chest
 * slot ever going empty.
 */
public final class ElytraPolicy {
    /** Window after a SWAP during which the swap is not repeated, in milliseconds. */
    public static final long SWAP_COOLDOWN_MS = 2_000;

    /** If this many milliseconds have passed since the last "no spare" warning, it rearms. */
    public static final long WARNING_REARM_WINDOW_MS = 5 * 60 * 1_000;

    private static final long NEVER = Long.MIN_VALUE;

    private long lastSwapAt = NEVER;
    private Integer warnedAtPercent;
    private long lastWarnedAt = NEVER;

    /**
     * @param slot slot of the chosen spare, or -1 if the decision is not SWAP
     */
    public record Result(Decision decision, int slot, int wornPercent) {}

    /** Remaining durability as a percentage. An item without durability counts as intact. */
    public static int percentOf(int damage, int maxDamage) {
        if (maxDamage <= 0) return 100;
        long remaining = (long) maxDamage - damage;
        int percent = (int) (remaining * 100 / maxDamage);
        return Math.max(0, Math.min(100, percent));
    }

    /**
     * @param wornPercent durability of the worn elytra, or null if none is worn
     * @param candidates  loose elytras in the inventory
     * @param swapBelow   swap when the worn one's durability is at or below this percentage
     * @param minSpare    only consider spares with at least this percentage
     * @param now         System.currentTimeMillis()
     */
    public Result decide(Integer wornPercent, List<ElytraCandidate> candidates,
                         int swapBelow, int minSpare, long now) {
        if (wornPercent == null) {
            // An empty chest slot is the most reliable sign that the elytra has changed: it covers
            // dying and most swaps by hand. Rearm here, not in shouldWarnNoSpare(), which does not
            // repeat this rule: rearming on a rise or on an empty chest slot lives in one place.
            warnedAtPercent = null;
            return new Result(Decision.NOT_WEARING, -1, 0);
        }

        // Seeing the worn one's percentage rise means it is another elytra: rearm the "no spare"
        // warning. This is checked on every decide(), not only when warning, because decide() is
        // called every tick and is the only thing that sees the elytra change in time (spec
        // §4.5). It is the only place this rule applies: shouldWarnNoSpare() does not repeat it.
        if (warnedAtPercent != null && wornPercent > warnedAtPercent) {
            warnedAtPercent = null;
        }

        // After a swap the chest slot takes a tick or so to reflect it (spec §4.4).
        // The sentinel is checked separately on purpose: "now - Long.MIN_VALUE" overflows to a
        // negative number, which would pass the comparison and leave the module never swapping.
        if (lastSwapAt != NEVER && now >= lastSwapAt && now - lastSwapAt < SWAP_COOLDOWN_MS) {
            return new Result(Decision.OK, -1, wornPercent);
        }
        if (wornPercent > swapBelow) return new Result(Decision.OK, -1, wornPercent);

        ElytraCandidate best = null;
        for (ElytraCandidate candidate : candidates) {
            // Strictly better than the worn one: without this, a low minimum chains swaps (spec §4.3).
            if (candidate.percent() < minSpare || candidate.percent() <= wornPercent) continue;
            if (best == null
                || candidate.percent() < best.percent()
                || (candidate.percent() == best.percent() && candidate.slot() < best.slot())) {
                best = candidate;
            }
        }

        if (best == null) return new Result(Decision.NO_SPARE, -1, wornPercent);

        lastSwapAt = now;
        return new Result(Decision.SWAP, best.slot(), wornPercent);
    }

    /**
     * true if the no-spare warning is due now. Rearming on a rising percentage or on an empty
     * chest slot is already handled by {@link #decide(Integer, List, int, int, long)}, which clears
     * {@code warnedAtPercent} before getting here; this method only adds rearming by time, just
     * like {@code AutoTpyPolicy.shouldReportIgnored}, for the case of changing elytra without the
     * chest slot ever going empty.
     *
     * @param wornPercent durability of the worn elytra
     * @param now         System.currentTimeMillis()
     */
    public boolean shouldWarnNoSpare(int wornPercent, long now) {
        if (warnedAtPercent != null && lastWarnedAt != NEVER && now >= lastWarnedAt
            && now - lastWarnedAt < WARNING_REARM_WINDOW_MS) {
            return false;
        }
        warnedAtPercent = wornPercent;
        lastWarnedAt = now;
        return true;
    }

    /**
     * Rearms the warning and forgets the last swap. Called <strong>only</strong> when the module is
     * turned on. It must not be called after a SWAP: that would erase the {@code lastSwapAt} that
     * {@code decide()} has just set and void the anti-repeat window of
     * {@link #SWAP_COOLDOWN_MS}, making the module repeat the same swap on every tick.
     */
    public void reset() {
        lastSwapAt = NEVER;
        warnedAtPercent = null;
        lastWarnedAt = NEVER;
    }
}
