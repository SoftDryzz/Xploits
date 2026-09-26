package com.xploits.bench.core;

import com.xploits.bench.core.Acceptance.Verdict;
import com.xploits.bench.core.Recommendation.Judged;
import com.xploits.pvp.crystal.core.RiskLevel;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static com.xploits.pvp.crystal.core.RiskLevel.AGGRESSIVE;
import static com.xploits.pvp.crystal.core.RiskLevel.BALANCED;
import static com.xploits.pvp.crystal.core.RiskLevel.SAFE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The strict ("premium") recommendation of crystal-aura++ (crystal-aura++ spec, Round 2, acceptance
 * criterion), one per risk level (R2-5): YES only when every applicable pair of that level is ACCEPT; a pair
 * where no crystal was placed is not applicable and counts neither way; no applicable pair at all is NO; and
 * each level is judged over its own pairs only.
 */
class RecommendationTest {
    private static final Verdict A = Verdict.ACCEPT;
    private static final Verdict R = Verdict.REJECT;
    private static final Verdict I = Verdict.INCOMPLETE;
    private static final Verdict N = Verdict.NOT_APPLICABLE;

    @Test
    void everyPairAcceptedIsYes() {
        Recommendation r = Recommendation.of(SAFE, List.of(A, A, A, A, A));
        assertTrue(r.yes());
        assertEquals(SAFE, r.level());
        assertEquals(5, r.accepted());
        assertEquals(5, r.applicable());
        assertEquals(0, r.notApplicable());
        assertEquals("capp Safe: YES (5 of 5 applicable)", r.line());
    }

    @Test
    void oneRejectIsNo() {
        Recommendation r = Recommendation.of(BALANCED, List.of(A, A, R, A));
        assertFalse(r.yes());
        assertEquals("capp Balanced: NO (3 of 4 applicable)", r.line());
    }

    @Test
    void oneIncompleteIsNo() {
        Recommendation r = Recommendation.of(AGGRESSIVE, List.of(A, I, A, A));
        assertFalse(r.yes());
        assertEquals("capp Aggressive: NO (3 of 4 applicable)", r.line());
    }

    @Test
    void aNotApplicablePairIsIgnored() {
        // capp-defender today: neither aura placed a crystal. It is not evidence for ++, nor against it.
        Recommendation r = Recommendation.of(SAFE, List.of(A, A, N, A, A));
        assertTrue(r.yes());
        assertEquals(4, r.accepted());
        assertEquals(4, r.applicable());
        assertEquals(1, r.notApplicable());
        assertEquals("capp Safe: YES (4 of 4 applicable; 1 not applicable)", r.line());

        assertFalse(Recommendation.of(SAFE, List.of(A, R, N, A, A)).yes());
    }

    @Test
    void noApplicablePairIsNo() {
        Recommendation onlyEmpty = Recommendation.of(SAFE, List.of(N, N));
        assertFalse(onlyEmpty.yes());
        assertEquals("capp Safe: NO (0 of 0 applicable; 2 not applicable)", onlyEmpty.line());
        assertFalse(Recommendation.of(SAFE, List.of()).yes());
    }

    // Per level (R2-5)

    @Test
    void eachLevelIsJudgedOverItsOwnPairsOnly() {
        // The full bench today: Safe has the defender pair (not applicable) and four REJECTs; each other level
        // has its four applicable pairs. A REJECT at one level never reaches another.
        List<Judged> pairs = List.of(
            new Judged(SAFE, R), new Judged(SAFE, R), new Judged(SAFE, N), new Judged(SAFE, R), new Judged(SAFE, R),
            new Judged(BALANCED, A), new Judged(BALANCED, A), new Judged(BALANCED, A), new Judged(BALANCED, A),
            new Judged(AGGRESSIVE, A), new Judged(AGGRESSIVE, R), new Judged(AGGRESSIVE, A), new Judged(AGGRESSIVE, A));
        List<Recommendation> r = Recommendation.byLevel(pairs);
        assertEquals(List.of(
                "capp Safe: NO (0 of 4 applicable; 1 not applicable)",
                "capp Balanced: YES (4 of 4 applicable)",
                "capp Aggressive: NO (3 of 4 applicable)"),
            r.stream().map(Recommendation::line).toList());
        assertEquals(List.of(false, true, false), r.stream().map(Recommendation::yes).toList());
    }

    @Test
    void theLinesFollowTheLevelsOrderWhateverThePairsOrder() {
        List<Judged> pairs = List.of(new Judged(AGGRESSIVE, A), new Judged(SAFE, A), new Judged(BALANCED, R),
            new Judged(SAFE, A), new Judged(AGGRESSIVE, A), new Judged(BALANCED, A));
        assertEquals(List.of(
                "capp Safe: YES (2 of 2 applicable)",
                "capp Balanced: NO (1 of 2 applicable)",
                "capp Aggressive: YES (2 of 2 applicable)"),
            Recommendation.byLevel(pairs).stream().map(Recommendation::line).toList());
    }

    @Test
    void anIncompletePairCountsAgainstItsOwnLevelOnly() {
        // A pair that did not run is INCOMPLETE: only its own level says NO.
        List<Recommendation> r = Recommendation.byLevel(List.of(new Judged(SAFE, A), new Judged(BALANCED, I),
            new Judged(AGGRESSIVE, A)));
        assertEquals(List.of(true, false, true), r.stream().map(Recommendation::yes).toList());
    }

    @Test
    void aLevelWithoutPairsGetsNoLine() {
        // Custom is never benched; a level with no pair says nothing rather than a NO about nothing.
        List<Recommendation> r = Recommendation.byLevel(List.of(new Judged(SAFE, A), new Judged(AGGRESSIVE, R)));
        assertEquals(List.of(SAFE, AGGRESSIVE), r.stream().map(Recommendation::level).toList());
        assertEquals(List.of(), Recommendation.byLevel(List.of()));
    }

    @Test
    void aLevelWhosePairsAreAllNotApplicableIsNo() {
        List<Recommendation> r = Recommendation.byLevel(List.of(new Judged(SAFE, N), new Judged(BALANCED, A)));
        assertEquals(List.of("capp Safe: NO (0 of 0 applicable; 1 not applicable)", "capp Balanced: YES (1 of 1 applicable)"),
            r.stream().map(Recommendation::line).toList());
    }

    @Test
    void aPairNeedsALevelAndAVerdict() {
        assertThrows(NullPointerException.class, () -> new Judged(null, A));
        assertThrows(NullPointerException.class, () -> new Judged(SAFE, null));
    }

    @Test
    void theLineNeverLooksLikeAPosition() {
        for (RiskLevel level : RiskLevel.values()) {
            List<Verdict> verdicts = new ArrayList<>();
            for (int i = 0; i < 12; i++) {
                verdicts.add(Verdict.values()[i % Verdict.values().length]);
                String line = Recommendation.of(level, verdicts).line();
                assertFalse(PositionLike.in(line), line);
            }
        }
    }
}
