package com.xploits.pvp.crystal.core;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the server sends is not trusted (anarchy servers spoof health): every value is checked here before it
 * reaches the core, so an odd one can never throw inside a tick handler.
 */
class ServerValuesTest {
    /** What a hostile or broken server can make the game report. */
    private static final double[] ODD = {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, -1, -0.0,
        -Double.MIN_VALUE, Float.MAX_VALUE, Double.MAX_VALUE};

    @Test
    void aTargetWhoseHealthIsNotANumberIsSkipped() {
        assertTrue(target(20, 0, 3).isPresent());
        assertFalse(target(Float.NaN, 0, 3).isPresent());
        assertFalse(target(Float.POSITIVE_INFINITY, 0, 3).isPresent());
        assertFalse(target(20, Float.NaN, 3).isPresent(), "absorption counts too");
        assertFalse(target(20, Float.NEGATIVE_INFINITY, 3).isPresent());
        assertFalse(target(-1, 0, 3).isPresent());
    }

    @Test
    void aTargetsHealthIsSummedInFloatAsMeteorDoes() {
        float health = 0.1f;
        float absorption = 0.2f;
        assertEquals((double) (health + absorption), target(health, absorption, 9).orElseThrow().totalHealth());
    }

    @Test
    void aTargetAtNoMeasurableSquaredDistanceIsSkipped() {
        assertFalse(target(20, 0, Double.NaN).isPresent());
        assertFalse(target(20, 0, Double.POSITIVE_INFINITY).isPresent());
        assertFalse(target(20, 0, -1).isPresent());
        assertEquals(9, target(20, 0, 9).orElseThrow().squaredDistance());
    }

    @Test
    void ourOwnHealthThatIsNotANumberMeansDoingNothing() {
        assertEquals(OptionalDouble.of(25), ServerValues.ownHealth(20, 5));
        assertFalse(ServerValues.ownHealth(Float.NaN, 0).isPresent());
        assertFalse(ServerValues.ownHealth(20, Float.POSITIVE_INFINITY).isPresent());
        assertFalse(ServerValues.ownHealth(-3, 0).isPresent());
    }

    @Test
    void oddTargetDamageCountsAsNone() {
        assertEquals(4.5, ServerValues.targetDamage(4.5));
        for (double v : ODD) {
            double d = ServerValues.targetDamage(v);
            assertTrue(Double.isFinite(d) && d >= 0, "damage " + v);
        }
        assertEquals(0, ServerValues.targetDamage(Double.NaN));
        assertEquals(0, ServerValues.targetDamage(Double.POSITIVE_INFINITY));
        assertEquals(0, ServerValues.targetDamage(-2));
    }

    @Test
    void aSpotWithOddSelfDamageIsNotAnOption() {
        assertEquals(OptionalDouble.of(3), ServerValues.spotSelfDamage(3));
        assertFalse(ServerValues.spotSelfDamage(Double.NaN).isPresent());
        assertFalse(ServerValues.spotSelfDamage(Double.POSITIVE_INFINITY).isPresent());
        assertFalse(ServerValues.spotSelfDamage(-1).isPresent());
    }

    @Test
    void aStandingCrystalWithOddSelfDamageIsTreatedAsDeadly() {
        assertEquals(3, ServerValues.crystalSelfDamage(3));
        for (double v : new double[] {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, -1}) {
            assertEquals(ServerValues.UNKNOWN_SELF_DAMAGE, ServerValues.crystalSelfDamage(v), "self " + v);
        }
    }

    @Test
    void aCrystalAtNoMeasurableDistanceIsCountedAsNextToUs() {
        assertEquals(7, ServerValues.crystalDistance(7));
        assertEquals(0, ServerValues.crystalDistance(Double.NaN));
        assertEquals(0, ServerValues.crystalDistance(Double.POSITIVE_INFINITY));
        assertEquals(0, ServerValues.crystalDistance(-1));
    }

    @Test
    void armourPiecesWithNoOrOddDurability() {
        assertEquals(50, ServerValues.armorPercent(100, 50).getAsDouble());
        assertFalse(ServerValues.armorPercent(0, 0).isPresent(), "no durability never counts, as in Meteor (NaN)");
        assertFalse(ServerValues.armorPercent(-5, 1).isPresent());
        assertEquals(0, ServerValues.armorPercent(100, 250).getAsDouble(), "more damage than durability is worn out");
        assertEquals(200, ServerValues.armorPercent(100, -100).getAsDouble());
    }

    @Test
    void anEffectAmplifierIsNeverBelowZero() {
        assertEquals(CrystalTick.Hands.NO_EFFECT, ServerValues.amplifier(false, 3));
        assertEquals(2, ServerValues.amplifier(true, 2));
        assertEquals(0, ServerValues.amplifier(true, -5));
        assertDoesNotThrow(() -> new CrystalTick.Hands(true, true, false, false, false,
            ServerValues.amplifier(true, Integer.MIN_VALUE), ServerValues.amplifier(true, -1), false, false, false));
    }

    @Test
    void theCoreNeverReceivesAnOddValueThroughTheseChecks() {
        for (double health : ODD) {
            for (double absorption : ODD) {
                for (double distance : ODD) {
                    Optional<TargetView> t = assertDoesNotThrow(() -> ServerValues.target("p", distance, health, absorption,
                        TargetView.NO_ARMOR, false, true, false, false, false, false, false));
                    t.ifPresent(v -> {
                        assertTrue(Double.isFinite(v.totalHealth()) && v.totalHealth() >= 0);
                        assertTrue(Double.isFinite(v.squaredDistance()) && v.squaredDistance() >= 0);
                    });
                }
            }
            ServerValues.ownHealth(health, 0).ifPresent(h -> assertTrue(Double.isFinite(h) && h >= 0));
        }
        for (double v : ODD) {
            assertDoesNotThrow(() -> new CrystalSeen(1, 1, Map.of("p", ServerValues.targetDamage(v)),
                ServerValues.crystalSelfDamage(v), ServerValues.crystalDistance(v), true));
            ServerValues.spotSelfDamage(v).ifPresent(self -> assertDoesNotThrow(() ->
                new Candidate(1, Map.of("p", ServerValues.targetDamage(v)), self, true, Set.of(), false)));
        }
    }

    @Test
    void theBudgetTakesTheExactSelfDamageWhenItIsHigherAndMeteorsOtherwise() {
        assertEquals(5.5420, ServerValues.budgetSelfDamage(5.4144, 5.5420));
        assertEquals(5.4144, ServerValues.budgetSelfDamage(5.4144, 5.0), "never below Meteor's");
        assertEquals(ServerValues.UNKNOWN_SELF_DAMAGE, ServerValues.budgetSelfDamage(ServerValues.UNKNOWN_SELF_DAMAGE, 5));
    }

    @Test
    void anOddExactSelfDamageFallsBackToMeteorsNeverToNothing() {
        for (double v : ODD) {
            if (Double.isFinite(v) && v >= 0) continue;
            assertEquals(5.4144, ServerValues.budgetSelfDamage(5.4144, v), "exact " + v);
            double self = ServerValues.budgetSelfDamage(5.4144, v);
            assertDoesNotThrow(() -> new Candidate(1, Map.of(), 5.4144, self, true, Set.of(), false));
        }
        assertEquals(Float.MAX_VALUE, ServerValues.budgetSelfDamage(5.4144, Float.MAX_VALUE));
    }

    private static Optional<TargetView> target(double health, double absorption, double squaredDistance) {
        return ServerValues.target("p", squaredDistance, health, absorption, TargetView.NO_ARMOR, false, true, false, false, false, false, false);
    }
}
