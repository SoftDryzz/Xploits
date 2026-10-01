package com.xploits.printer.core;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static com.xploits.printer.core.EndStates.End;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** Printer spec §5.7 "End states" (N-I6): all of them stops that say why. */
class EndStatesTest {
    private static BuildIndex.Counts counts(int unknown, int matches, int missing, int wrong, int kept, int skipped) {
        return new BuildIndex.Counts(0, unknown, 0, 0, matches, 0, missing, wrong, kept, skipped);
    }

    private static Optional<End> decide(BuildIndex.Counts c, boolean fix, boolean actionable, boolean mover,
                                        boolean noSpot, int goals) {
        return decide(c, fix, actionable, false, mover, noSpot, goals);
    }

    private static Optional<End> decide(BuildIndex.Counts c, boolean fix, boolean actionable, boolean materialMissing,
                                        boolean mover, boolean noSpot, int goals) {
        return EndStates.decide(new EndStates.Inputs(c, fix, actionable, materialMissing, mover, noSpot, goals, true, true),
            PrinterLimits.DEFAULTS);
    }

    @Test
    void nothingIsDecidedBeforeTheFirstFullScanOrWithPlacementsInFlight() {
        BuildIndex.Counts done = counts(0, 10, 0, 0, 0, 0);
        assertEquals(Optional.empty(), EndStates.decide(new EndStates.Inputs(done, true, false, false, true, false, 0, true, false), PrinterLimits.DEFAULTS));
        assertEquals(Optional.empty(), EndStates.decide(new EndStates.Inputs(done, true, false, false, true, false, 0, false, true), PrinterLimits.DEFAULTS));
    }

    @Test
    void finishedOnlyWhenEveryKnownTargetMatchesAndNothingIsUnknown() {
        assertEquals(Optional.of(End.FINISHED), decide(counts(0, 10, 0, 0, 0, 0), true, false, true, false, 0));
        assertEquals(Optional.of(End.LEFTOVERS), decide(counts(0, 10, 0, 0, 1, 0), true, false, true, false, 0));
        assertEquals(Optional.of(End.LEFTOVERS), decide(counts(0, 10, 0, 0, 0, 2), true, false, true, false, 0));
        assertEquals(Optional.of(End.LEFTOVERS), decide(counts(0, 10, 0, 3, 0, 0), false, false, true, false, 0),
            "wrong blocks kept by fix-wrong-blocks off");
    }

    @Test
    void onlyUnknownPartsLeft() {
        assertEquals(Optional.empty(), decide(counts(5, 10, 0, 0, 0, 0), true, false, true, false, 3), "head there");
        assertEquals(Optional.of(End.NOTHING_KNOWN), decide(counts(5, 10, 0, 0, 0, 0), true, false, true, false, 4));
        assertEquals(Optional.of(End.NOTHING_REACHABLE), decide(counts(5, 10, 0, 0, 0, 0), true, false, false, false, 0),
            "without a Mover");
    }

    @Test
    void knownWorkLeft() {
        assertEquals(Optional.of(End.MATERIAL_MISSING), decide(counts(0, 10, 3, 0, 0, 0), true, false, true, false, 0));
        assertEquals(Optional.of(End.NOTHING_REACHABLE), decide(counts(0, 10, 3, 0, 0, 0), true, true, false, false, 0));
        assertEquals(Optional.of(End.NOTHING_REACHABLE), decide(counts(0, 10, 3, 0, 0, 0), true, true, true, true, 0));
        assertEquals(Optional.empty(), decide(counts(0, 10, 3, 0, 0, 0), true, true, true, false, 0), "walk to a spot");
    }

    @Test
    void whenWorkIsUnreachableTheMissingMaterialDecidesTheEnd() {
        BuildIndex.Counts c = counts(0, 10, 3, 0, 0, 0);
        assertEquals(Optional.of(End.MATERIAL_MISSING), decide(c, true, true, true, true, true, 0), "no spot, material missing");
        assertEquals(Optional.of(End.MATERIAL_MISSING), decide(c, true, true, true, false, false, 0), "no Mover, material missing");
        assertEquals(Optional.of(End.NOTHING_REACHABLE), decide(c, true, true, false, true, true, 0), "no spot, nothing missing");
        assertEquals(Optional.of(End.NOTHING_REACHABLE), decide(c, true, true, false, false, false, 0), "no Mover, nothing missing");
    }
}
