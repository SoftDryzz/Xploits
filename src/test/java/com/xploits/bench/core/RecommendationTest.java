package com.xploits.bench.core;

import com.xploits.bench.core.Acceptance.Verdict;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The strict ("premium") recommendation of crystal-aura++ (crystal-aura++ spec, Round 2, acceptance
 * criterion): YES only when every applicable pair is ACCEPT; a pair where no crystal was placed is not
 * applicable and counts neither way; no applicable pair at all is NO.
 */
class RecommendationTest {
    private static final Verdict A = Verdict.ACCEPT;
    private static final Verdict R = Verdict.REJECT;
    private static final Verdict I = Verdict.INCOMPLETE;
    private static final Verdict N = Verdict.NOT_APPLICABLE;

    @Test
    void everyPairAcceptedIsYes() {
        Recommendation r = Recommendation.of(List.of(A, A, A, A, A));
        assertTrue(r.yes());
        assertEquals(5, r.accepted());
        assertEquals(5, r.applicable());
        assertEquals(0, r.notApplicable());
        assertEquals("capp recommendation: YES (5 of 5 applicable pairs ACCEPT; 0 not applicable)", r.line());
    }

    @Test
    void oneRejectIsNo() {
        Recommendation r = Recommendation.of(List.of(A, A, R, A, A));
        assertFalse(r.yes());
        assertEquals("capp recommendation: NO (4 of 5 applicable pairs ACCEPT; 0 not applicable)", r.line());
    }

    @Test
    void oneIncompleteIsNo() {
        Recommendation r = Recommendation.of(List.of(A, I, A, A, A));
        assertFalse(r.yes());
        assertEquals("capp recommendation: NO (4 of 5 applicable pairs ACCEPT; 0 not applicable)", r.line());
    }

    @Test
    void aNotApplicablePairIsIgnored() {
        // capp-defender today: neither aura placed a crystal. It is not evidence for ++, nor against it.
        Recommendation r = Recommendation.of(List.of(A, A, N, A, A));
        assertTrue(r.yes());
        assertEquals(4, r.accepted());
        assertEquals(4, r.applicable());
        assertEquals(1, r.notApplicable());
        assertEquals("capp recommendation: YES (4 of 4 applicable pairs ACCEPT; 1 not applicable)", r.line());

        assertFalse(Recommendation.of(List.of(A, R, N, A, A)).yes());
    }

    @Test
    void noApplicablePairIsNo() {
        Recommendation onlyEmpty = Recommendation.of(List.of(N, N));
        assertFalse(onlyEmpty.yes());
        assertEquals("capp recommendation: NO (0 of 0 applicable pairs ACCEPT; 2 not applicable)", onlyEmpty.line());
        assertFalse(Recommendation.of(List.of()).yes());
    }

    @Test
    void theLineNeverLooksLikeAPosition() {
        List<Verdict> verdicts = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            verdicts.add(Verdict.values()[i % Verdict.values().length]);
            String line = Recommendation.of(verdicts).line();
            assertFalse(PositionLike.in(line), line);
        }
    }
}
