package com.xploits.bench.core;

import com.xploits.bench.core.Acceptance.Verdict;

import java.util.List;

/**
 * Whether the bench recommends crystal-aura++ (crystal-aura++ spec, Round 2, acceptance criterion: strict,
 * "premium"): YES only when every applicable pair is ACCEPT, the no-regeneration pairs and the healing
 * pairs alike. A NOT_APPLICABLE pair (no crystal placed on either side) counts neither way; any REJECT or
 * INCOMPLETE, a pair that did not run included, is NO; and so is no applicable pair at all, since nothing
 * was then shown. Pure; the recommendation never fails the bench.
 *
 * @param yes           recommended
 * @param accepted      applicable pairs that are ACCEPT
 * @param applicable    pairs that are not NOT_APPLICABLE
 * @param notApplicable pairs that are NOT_APPLICABLE
 */
public record Recommendation(boolean yes, int accepted, int applicable, int notApplicable) {
    /** What the line starts with: every comparison is crystal-aura++ against crystal-aura. */
    public static final String LABEL = "capp recommendation";

    /** The verdict of every pair the full bench judges, in any order. */
    public static Recommendation of(List<Verdict> verdicts) {
        int accepted = 0;
        int applicable = 0;
        int notApplicable = 0;
        for (Verdict verdict : verdicts) {
            if (verdict == Verdict.NOT_APPLICABLE) {
                notApplicable++;
                continue;
            }
            applicable++;
            if (verdict == Verdict.ACCEPT) accepted++;
        }
        return new Recommendation(applicable > 0 && accepted == applicable, accepted, applicable, notApplicable);
    }

    /** {@code capp recommendation: YES (n of m applicable pairs ACCEPT; k not applicable)}, or NO. */
    public String line() {
        return LABEL + ": " + (yes ? "YES" : "NO") + " (" + accepted + " of " + applicable
            + " applicable pairs ACCEPT; " + notApplicable + " not applicable)";
    }
}
