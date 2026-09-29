package com.xploits.bench.core;

import com.xploits.pvp.recorder.core.CombatEvent.AttackerKind;
import com.xploits.pvp.recorder.core.DamageKind;
import com.xploits.pvp.recorder.core.FightRecord.DamageEvent;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.OptionalDouble;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task A1 requirement 3: our health plus absorption right after each hit from one of OUR OWN crystals
 * (the damage events with {@code by() == AttackerKind.SELF}), the lowest over the run. Pure: it only
 * reads {@link DamageEvent#by()} and {@link DamageEvent#after()}.
 */
class MinHealthAfterOwnHitTest {
    private static DamageEvent hit(AttackerKind by, double before, double after) {
        return new DamageEvent(1, DamageKind.CRYSTAL, by, by == AttackerKind.PLAYER ? "someone" : null, before, after, false);
    }

    @Test
    void emptyWithNoDamageAtAll() {
        assertTrue(MinHealthAfterOwnHit.of(List.of()).isEmpty());
    }

    @Test
    void emptyWhenNoHitWasOurOwnCrystal() {
        List<DamageEvent> damage = List.of(hit(AttackerKind.PLAYER, 20, 12), hit(AttackerKind.NONE, 20, 18));
        assertTrue(MinHealthAfterOwnHit.of(damage).isEmpty());
    }

    @Test
    void oneOwnHitIsItsAfterHealth() {
        List<DamageEvent> damage = List.of(hit(AttackerKind.SELF, 20, 14));
        assertEquals(OptionalDouble.of(14), MinHealthAfterOwnHit.of(damage));
    }

    @Test
    void theLowestAfterHealthOverEveryOwnHitOnly() {
        List<DamageEvent> damage = List.of(
            hit(AttackerKind.SELF, 20, 15),
            hit(AttackerKind.PLAYER, 15, 2), // an opponent's hit: dips lower, but is not counted
            hit(AttackerKind.SELF, 2, 1.5),
            hit(AttackerKind.SELF, 10, 9));
        assertEquals(OptionalDouble.of(1.5), MinHealthAfterOwnHit.of(damage));
    }

    @Test
    void orderInTheListDoesNotMatter() {
        List<DamageEvent> forward = List.of(hit(AttackerKind.SELF, 20, 8), hit(AttackerKind.SELF, 8, 3));
        List<DamageEvent> backward = List.of(hit(AttackerKind.SELF, 8, 3), hit(AttackerKind.SELF, 20, 8));
        assertFalse(MinHealthAfterOwnHit.of(forward).isEmpty());
        assertEquals(MinHealthAfterOwnHit.of(forward), MinHealthAfterOwnHit.of(backward));
    }

    private static DamageEvent hitAt(long tick, AttackerKind by, double before, double after) {
        return new DamageEvent(tick, DamageKind.CRYSTAL, by, by == AttackerKind.PLAYER ? "someone" : null, before, after, false);
    }

    @Test
    void aFinishingHitIsLeftOutSoTheReserveRuleOnlySeesOrdinaryOnes() {
        // Task B0b: an ordinary hit at 9 and a finishing hit that took us to 1: the reserve rule sees 9.
        List<DamageEvent> damage = List.of(hitAt(10, AttackerKind.SELF, 20, 9), hitAt(50, AttackerKind.SELF, 6, 1));
        assertEquals(OptionalDouble.of(9), MinHealthAfterOwnHit.of(damage, java.util.Set.of(50L)));
        assertEquals(OptionalDouble.of(1), MinHealthAfterOwnHit.of(damage));
    }

    @Test
    void emptyWhenEveryOwnHitWasAFinishingOne() {
        List<DamageEvent> damage = List.of(hitAt(50, AttackerKind.SELF, 6, 0));
        assertTrue(MinHealthAfterOwnHit.of(damage, java.util.Set.of(50L)).isEmpty());
    }
}
