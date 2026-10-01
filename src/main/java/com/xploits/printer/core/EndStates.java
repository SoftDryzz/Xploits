package com.xploits.printer.core;

import java.util.Optional;

/**
 * The end states of a phase-1 session (printer spec §5.7, N-I6), all of them stops that say why; asked only when nothing
 * is left to do within reach. Empty means carry on (walk to a spot, or towards an unknown part).
 */
public final class EndStates {
    private EndStates() {
    }

    public enum End { FINISHED, LEFTOVERS, MATERIAL_MISSING, NOTHING_REACHABLE, NOTHING_KNOWN }

    /**
     * @param anyActionable      some known missing or wrong target has its material carried
     * @param anyMaterialMissing some known missing or wrong target's material is not carried
     * @param noSpot             the walk is exhausted, or no stand spot reaches an actionable target
     * @param unknownGoalsUsed   {@code #goto x z} goals already sent towards unknown parts
     * @param pendingIdle        no placement in flight
     * @param scanComplete       the index has made one full pass
     */
    public record Inputs(BuildIndex.Counts counts, boolean fixWrong, boolean anyActionable, boolean anyMaterialMissing,
                         boolean moverAvailable, boolean noSpot, int unknownGoalsUsed, boolean pendingIdle,
                         boolean scanComplete) {
    }

    public static Optional<End> decide(Inputs in, PrinterLimits limits) {
        if (!in.scanComplete() || !in.pendingIdle()) return Optional.empty();
        BuildIndex.Counts c = in.counts();
        int known = c.missing() + (in.fixWrong() ? c.wrong() : 0);
        if (known == 0) {
            if (c.unknown() + c.unscanned() == 0) {
                int left = c.kept() + c.skipped() + (in.fixWrong() ? 0 : c.wrong());
                return Optional.of(left > 0 ? End.LEFTOVERS : End.FINISHED);
            }
            if (!in.moverAvailable()) return Optional.of(End.NOTHING_REACHABLE);
            if (in.unknownGoalsUsed() >= limits.unknownGoals()) return Optional.of(End.NOTHING_KNOWN);
            return Optional.empty();
        }
        if (!in.anyActionable()) return Optional.of(End.MATERIAL_MISSING);
        if (!in.moverAvailable() || in.noSpot()) {
            return Optional.of(in.anyMaterialMissing() ? End.MATERIAL_MISSING : End.NOTHING_REACHABLE);
        }
        return Optional.empty();
    }
}
