package com.xploits.bench.core;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Task A2+ requirement 6: the escape's trigger and its steady ramp to the full distance. */
class EscapePaceTest {
    @Test
    void theConstantsAreTheBriefsExactValues() {
        assertEquals(2, EscapePace.ESCAPE_TOTEMS);
        assertEquals(12, EscapePace.ESCAPE_DISTANCE);
        assertEquals(10, EscapePace.ESCAPE_TICKS);
    }

    @Test
    void triggersAtOrBelowEscapeTotems() {
        assertTrue(EscapePace.triggered(0));
        assertTrue(EscapePace.triggered(1));
        assertTrue(EscapePace.triggered(2));
        assertFalse(EscapePace.triggered(3));
        assertFalse(EscapePace.triggered(8));
    }

    @Test
    void noDisplacementAtOrBeforeTheTrigger() {
        assertEquals(0, EscapePace.displacement(0));
        assertEquals(0, EscapePace.displacement(-1));
    }

    @Test
    void reachesTheFullDistanceExactlyAtEscapeTicks() {
        assertEquals(12.0, EscapePace.displacement(EscapePace.ESCAPE_TICKS), 1e-9);
    }

    @Test
    void rampsHalfwayAtHalfTheTicks() {
        assertEquals(6.0, EscapePace.displacement(5), 1e-9);
    }

    @Test
    void neverOvershootsPastTheFullDistance() {
        assertEquals(12.0, EscapePace.displacement(11), 1e-9);
        assertEquals(12.0, EscapePace.displacement(1000), 1e-9);
    }

    // --- Fix round 1: arena-bound clamp (Critical, review-a2plus.md) --------------------------------------

    @Test
    void axisAlignedClampStopsExactlyAtTheBound() {
        // Start 3 out from centre, heading further out along +x: 9 - 3 = 6 left before the bound.
        assertEquals(6.0, EscapePace.maxDisplacement(3, 0, 1, 0, 9), 1e-9);
        // Symmetric on -x.
        assertEquals(6.0, EscapePace.maxDisplacement(-3, 0, -1, 0, 9), 1e-9);
        // Same on z.
        assertEquals(6.0, EscapePace.maxDisplacement(0, 3, 0, 1, 9), 1e-9);
    }

    @Test
    void headingBackTowardCentreIsBoundedByTheOppositeSideInstead() {
        // From +8 heading toward -x: moving away from the +9 bound, but still 17 blocks from the -9 one.
        assertEquals(17.0, EscapePace.maxDisplacement(8, 0, -1, 0, 9), 1e-9);
    }

    @Test
    void movingPurelyAlongOneAxisIsBoundedByThatAxisAlone() {
        // dirX is 0: x never constrains it, whatever the fixed x is; only z's own 9-block bound applies.
        assertEquals(9.0, EscapePace.maxDisplacement(8, 0, 0, 1, 9), 1e-9);
    }

    @Test
    void diagonalClampUsesTheTighterAxis() {
        // From centre, 45 degrees: both axes reach the bound at the same t (9 / (1/sqrt2)).
        double t = EscapePace.maxDisplacement(0, 0, Math.sqrt(0.5), Math.sqrt(0.5), 9);
        assertEquals(9 / Math.sqrt(0.5), t, 1e-9);
    }

    @Test
    void alreadyPastTheBoundGivesZeroNotNegative() {
        assertEquals(0.0, EscapePace.maxDisplacement(20, 0, 1, 0, 9), 1e-9);
    }

    @Test
    void aNonPositiveBoundIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> EscapePace.maxDisplacement(0, 0, 1, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> EscapePace.maxDisplacement(0, 0, 1, 0, -1));
    }

    @Test
    void aZeroDirectionIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> EscapePace.maxDisplacement(0, 0, 0, 0, 9));
    }

    // --- Fix round 1: blocked-destination fallback (Critical, review-a2plus.md) ---------------------------

    @Test
    void landsExactlyAtTheIdealDistanceWhenItIsFree() {
        assertEquals(6.34, EscapePace.landingDisplacement(6.34, t -> true), 1e-9);
    }

    @Test
    void stepsBackOneBlockAtATimeUntilSomethingIsFree() {
        List<Double> tried = new ArrayList<>();
        double landed = EscapePace.landingDisplacement(6.34, t -> {
            tried.add(t);
            return t < 4;
        });
        assertEquals(3.34, landed, 1e-9);
        assertEquals(List.of(6.34, 5.34, 4.34, 3.34), tried);
    }

    @Test
    void fallsBackToZeroWhenNothingAlongTheWayIsFree() {
        assertEquals(0.0, EscapePace.landingDisplacement(6.34, t -> false), 1e-9);
    }

    @Test
    void zeroIsNeverItselfTested() {
        List<Double> tried = new ArrayList<>();
        EscapePace.landingDisplacement(0.5, t -> {
            tried.add(t);
            return false;
        });
        assertEquals(List.of(0.5), tried);
    }

    @Test
    void anIdealDisplacementOfZeroNeedsNoSearch() {
        assertEquals(0.0, EscapePace.landingDisplacement(0, t -> {
            throw new AssertionError("free must not be asked about anything when the ideal is already 0");
        }), 1e-9);
    }

    @Test
    void aNegativeIdealDisplacementIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> EscapePace.landingDisplacement(-1, t -> true));
    }
}
