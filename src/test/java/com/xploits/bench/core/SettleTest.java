package com.xploits.bench.core;

import com.xploits.bench.core.Settle.Observation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Ending a measured crystal-aura run early (R3-6): only once nothing can happen any more, which is regeneration
 * off, a static script, and {@value Settle#SETTLE_TICKS} quiet ticks in a row, at least that long after T0.
 */
class SettleTest {
    /** A quiet state: no crystal, some placements and attacks already sent, both sides hurt, one pop so far. */
    private static final Observation QUIET = new Observation(0, 7, 5, 3.5, 12, 1, 0);

    /** A run that can settle: no regeneration in the scenario nor in the world, a static script. */

    private static Settle settle() {
        return new Settle(false, false, true);
    }

    /** Feeds {@code n} observations of {@code o} and returns what the last one said. */
    private static boolean feed(Settle s, Observation o, int n) {
        boolean settled = false;
        for (int i = 0; i < n; i++) settled = s.observe(o);
        return settled;
    }

    @Test
    void itSettlesOnTheSixtiethQuietTickNotTheFiftyNinth() {
        Settle s = settle();
        assertFalse(s.observe(QUIET), "the baseline at T0 is not a quiet tick");
        assertFalse(feed(s, QUIET, 59));
        assertEquals(59, s.ticks());
        assertTrue(s.observe(QUIET));
        assertEquals(60, s.ticks());
        assertTrue(s.observe(QUIET), "it stays settled while nothing happens");
    }

    @Test
    void theSettleCountIsSixtyTicks() {
        assertEquals(60, Settle.SETTLE_TICKS);
    }

    @Test
    void aCrystalOnTheFiftyNinthTickStartsTheCountAgain() {
        Settle s = settle();
        s.observe(QUIET);
        assertFalse(feed(s, QUIET, 58));
        assertFalse(s.observe(new Observation(1, 7, 5, 3.5, 12, 1, 0)));
        assertFalse(feed(s, QUIET, 59));
        assertTrue(s.observe(QUIET));
    }

    @Test
    void aCrystalThatStaysNeverLetsItSettle() {
        Settle s = settle();
        Observation lingering = new Observation(1, 7, 5, 3.5, 12, 1, 0);
        s.observe(lingering);
        assertFalse(feed(s, lingering, 1000));
    }

    @Test
    void ourHealthChangingStartsTheCountAgain() {
        assertResets(new Observation(0, 7, 5, 3.0, 12, 1, 0));
        // A rise counts as much as a fall.
        assertResets(new Observation(0, 7, 5, 4.0, 12, 1, 0));
    }

    @Test
    void theSparringsHealthChangingStartsTheCountAgain() {
        assertResets(new Observation(0, 7, 5, 3.5, 13, 1, 0));
    }

    @Test
    void aSparringPopStartsTheCountAgain() {
        // The same health as before, only the pop count moves.
        assertResets(new Observation(0, 7, 5, 3.5, 12, 2, 0));
    }

    @Test
    void aPlacementStartsTheCountAgain() {
        assertResets(new Observation(0, 8, 5, 3.5, 12, 1, 0));
    }

    @Test
    void anAttackStartsTheCountAgain() {
        assertResets(new Observation(0, 7, 6, 3.5, 12, 1, 0));
    }

    /**
     * 59 quiet ticks, then {@code event} on the 60th, which stays as the new quiet state: the run settles only
     * 60 quiet ticks after it.
     */
    private static void assertResets(Observation event) {
        assertResets(event, event);
    }

    /**
     * 59 quiet ticks, then {@code event} on the 60th, then {@code after} from then on: the run settles only 60
     * quiet ticks after the event.
     */
    private static void assertResets(Observation event, Observation after) {
        Settle s = settle();
        s.observe(QUIET);
        assertFalse(feed(s, QUIET, 59));
        assertFalse(s.observe(event), "the event's tick is not quiet");
        assertFalse(feed(s, after, 59));
        assertTrue(s.observe(after));
    }

    @Test
    void withRegenerationOnItNeverSettles() {
        Settle s = new Settle(true, true, true);
        assertFalse(s.possible());
        assertFalse(feed(s, QUIET, 1000));
    }

    @Test
    void withAMovingScriptItNeverSettles() {
        Settle s = new Settle(false, false, false);
        assertFalse(s.possible());
        assertFalse(feed(s, QUIET, 1000));
    }

    @Test
    void itIsPossibleOnlyWithoutRegenerationAndWithAStaticScript() {
        assertTrue(new Settle(false, false, true).possible());
        assertFalse(new Settle(true, true, false).possible());
    }

    @Test
    void theWorldsRuleMustAgreeWithTheScenario() {
        IllegalStateException off = assertThrows(IllegalStateException.class, () -> new Settle(false, true, true));
        assertEquals("the scenario runs without natural regeneration but the world has it on", off.getMessage());
        IllegalStateException on = assertThrows(IllegalStateException.class, () -> new Settle(true, false, true));
        assertEquals("the scenario runs with natural regeneration but the world has it off", on.getMessage());
    }

    @Test
    void withTheWorldHealingItNeverSettlesEvenWithAStaticScript() {
        Settle s = new Settle(true, true, true);
        assertFalse(s.possible());
        assertFalse(feed(s, QUIET, 1000));
    }

    @Test
    void anAuraTimerOfFiftyNineTicksStillLetsItSettle() {
        Settle s = settle();
        Observation timer = new Observation(0, 7, 5, 3.5, 12, 1, 59);
        s.observe(timer);
        assertFalse(feed(s, timer, 59));
        assertTrue(s.observe(timer));
    }

    @Test
    void anAuraTimerOfSixtyTicksOrMoreIsNeverQuiet() {
        Settle s = settle();
        Observation timer = new Observation(0, 7, 5, 3.5, 12, 1, 60);
        s.observe(timer);
        assertFalse(feed(s, timer, 1000));
    }

    @Test
    void aLongAuraTimerOnTheFiftyNinthTickStartsTheCountAgain() {
        // Only the timer moves: a ping spike on one tick, then back to normal.
        assertResets(new Observation(0, 7, 5, 3.5, 12, 1, 60), QUIET);
    }

    @Test
    void crystalAuraPlusPlusLongestTimerIsItsPendingLifetime() {
        // max(5, ping + 2): crystal-aura++'s pending placements (CrystalBrain Q2).
        assertEquals(7, Settle.plusPlusLongestTimer(5));
        assertEquals(5, Settle.plusPlusLongestTimer(0));
        assertEquals(59, Settle.plusPlusLongestTimer(57));
        assertEquals(60, Settle.plusPlusLongestTimer(58));
        assertThrows(IllegalArgumentException.class, () -> Settle.plusPlusLongestTimer(-1));
    }

    @Test
    void aPingOfFiftyEightTicksKeepsCrystalAuraPlusPlusFromSettling() {
        Settle s = settle();
        Observation slow = new Observation(0, 7, 5, 3.5, 12, 1, Settle.plusPlusLongestTimer(58));
        s.observe(slow);
        assertFalse(feed(s, slow, 1000));
        Settle t = settle();
        Observation fast = new Observation(0, 7, 5, 3.5, 12, 1, Settle.plusPlusLongestTimer(57));
        t.observe(fast);
        assertTrue(feed(t, fast, 60));
    }

    @Test
    void anObservationIsRequired() {
        assertThrows(NullPointerException.class, () -> settle().observe(null));
    }
}
