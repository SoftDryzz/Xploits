package com.xploits.bench.core;

import com.xploits.bench.core.Acceptance.Verdict;
import com.xploits.pvp.crystal.core.RiskLevel;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Whether the bench recommends crystal-aura++ at one risk level (crystal-aura++ spec, Round 2, acceptance
 * criterion: strict, "premium"; R2-5: one recommendation per level): YES only when every applicable pair of
 * that level is ACCEPT, the no-regeneration pairs and the healing pairs alike. A NOT_APPLICABLE pair (no
 * crystal placed on either side) counts neither way; any REJECT or INCOMPLETE, a pair that did not run
 * included, is NO; and so is no applicable pair at all, since nothing was then shown. Each level is judged
 * over its own pairs only. Pure; a recommendation never fails the bench.
 *
 * @param level         the risk level crystal-aura++ ran at in these pairs
 * @param yes           recommended at that level
 * @param accepted      applicable pairs that are ACCEPT
 * @param applicable    pairs that are not NOT_APPLICABLE
 * @param notApplicable pairs that are NOT_APPLICABLE
 */
public record Recommendation(RiskLevel level, boolean yes, int accepted, int applicable, int notApplicable) {
    /** What every line starts with: every comparison is crystal-aura++ against crystal-aura. */
    public static final String LABEL = "capp";

    public Recommendation {
        Objects.requireNonNull(level, "level");
    }

    /** One pair of the full bench: the level crystal-aura++ ran at, and the pair's verdict. */
    public record Judged(RiskLevel level, Verdict verdict) {
        public Judged {
            Objects.requireNonNull(level, "level");
            Objects.requireNonNull(verdict, "verdict");
        }
    }

    /** The verdicts of every pair the full bench judges at {@code level}, in any order. */
    public static Recommendation of(RiskLevel level, List<Verdict> verdicts) {
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
        return new Recommendation(level, applicable > 0 && accepted == applicable, accepted, applicable, notApplicable);
    }

    /**
     * One recommendation per level that has pairs, each over its own pairs only, in {@link RiskLevel}'s order
     * whatever the order of {@code pairs}.
     */
    public static List<Recommendation> byLevel(List<Judged> pairs) {
        Map<RiskLevel, List<Verdict>> verdicts = new EnumMap<>(RiskLevel.class);
        for (Judged pair : pairs) verdicts.computeIfAbsent(pair.level(), level -> new ArrayList<>()).add(pair.verdict());
        List<Recommendation> recommendations = new ArrayList<>();
        verdicts.forEach((level, list) -> recommendations.add(of(level, list)));
        return recommendations;
    }

    /**
     * The report's recommendation lines (R3-9): one per level this run measured, over its own pairs
     * ({@link #byLevel}), then, when the run's profile left levels out ({@link Profile#notMeasured}), one line
     * naming them ({@link #notMeasured}) instead of a NO about pairs that were never meant to run.
     */
    public static List<String> lines(List<Judged> pairs, List<RiskLevel> notMeasured) {
        List<Judged> measured = pairs.stream().filter(pair -> !notMeasured.contains(pair.level())).toList();
        List<String> lines = new ArrayList<>(byLevel(measured).stream().map(Recommendation::line).toList());
        if (!notMeasured.isEmpty()) lines.add(notMeasured(notMeasured));
        return lines;
    }

    /** {@code capp Safe, Aggressive: not measured in this run (use -Pbench.full)}. */
    public static String notMeasured(List<RiskLevel> levels) {
        return LABEL + " " + String.join(", ", levels.stream().map(RiskLevel::toString).toList())
            + ": not measured in this run (use -Pbench.full)";
    }

    /**
     * {@code capp Safe: YES (n of m applicable)}, or NO; {@code ; k not applicable} is added when a pair of
     * the level was not applicable.
     */
    public String line() {
        return LABEL + " " + level + ": " + (yes ? "YES" : "NO") + " (" + accepted + " of " + applicable + " applicable"
            + (notApplicable > 0 ? "; " + notApplicable + " not applicable" : "") + ")";
    }
}
