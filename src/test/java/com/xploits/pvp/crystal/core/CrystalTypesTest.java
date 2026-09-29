package com.xploits.pvp.crystal.core;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The plain input and output records: copies, sums and the values they refuse. */
class CrystalTypesTest {
    private static final CrystalTick.Hands HANDS = new CrystalTick.Hands(true, true, false, false, false,
        CrystalTick.Hands.NO_EFFECT, CrystalTick.Hands.NO_EFFECT, true, true, false);

    private static CrystalView crystal(Map<String, Double> damage, double self, double distance, int attempts, long attacked) {
        return new CrystalView(1, 77, damage, self, distance, true, true, attempts, attacked, CrystalView.NEVER);
    }

    private static CrystalTick tick(List<TargetView> targets, List<CrystalSeen> crystals, List<Candidate> candidates) {
        return new CrystalTick(10, 20, 0, false, false, false, false, HANDS, targets, crystals, candidates);
    }

    @Test
    void targetDamageIsSummedOverTheGivenTargetsOnly() {
        Map<String, Double> damage = new LinkedHashMap<>();
        damage.put("a", 4.0);
        damage.put("b", 2.5);
        damage.put("c", 100.0);
        CrystalView c = crystal(damage, 1, 3, 0, CrystalView.NEVER);
        Candidate p = new Candidate(7, damage, 1, true, Set.of(), false);
        CrystalSeen seen = new CrystalSeen(1, 77, damage, 1, 3, true);

        assertEquals(6.5, c.damageTo(List.of("a", "b", "nobody")), 0.0);
        assertEquals(6.5, seen.damageTo(List.of("b", "a")), 0.0);
        assertEquals(6.5, p.damageTo(List.of("a", "b")), 0.0);
        assertEquals(0.0, p.damageTo(List.of()), 0.0);
    }

    @Test
    void targetDamageIsSummedInFloatLikeMeteorSoItReachesMinDamageExactlyWhenMeteorDoes() {
        // Meteor: float damage = 0; damage += dmg (lines 1190, 1208). 3 + 2.9999998 is 5.99999976... in
        // double, under min-damage 6; in float it rounds to 6.0, which Meteor accepts.
        float a = 3.0f;
        float b = Math.nextDown(3.0f);
        Map<String, Double> damage = new LinkedHashMap<>();
        damage.put("a", (double) a);
        damage.put("b", (double) b);
        CrystalView c = crystal(damage, 1, 3, 0, CrystalView.NEVER);
        Candidate p = new Candidate(7, damage, 1, true, Set.of(), false);

        assertTrue((double) a + (double) b < 6.0);
        assertEquals(6.0f, c.damageTo(List.of("a", "b")));
        assertEquals(6.0f, p.damageTo(List.of("a", "b")));
        assertFalse(p.damageTo(List.of("a", "b")) < 6.0);
    }

    @Test
    void targetDamageIsSummedInTheOrderOfTheTargetsGiven() {
        // Float addition depends on the order; Meteor adds in its targets' order.
        Map<String, Double> damage = Map.of("big", 16_777_216.0, "one", 1.0, "two", 1.0);
        Candidate p = new Candidate(7, damage, 1, true, Set.of(), false);

        assertEquals(16_777_216.0f, p.damageTo(List.of("big", "one", "two")));
        assertEquals(16_777_218.0f, p.damageTo(List.of("one", "two", "big")));
        assertNotEquals(p.damageTo(List.of("big", "one", "two")), p.damageTo(List.of("one", "two", "big")));
    }

    @Test
    void aCrystalCarriesItsBaseBlockKeyLikeACandidate() {
        CrystalView c = crystal(Map.of(), 1, 3, 0, CrystalView.NEVER);
        Candidate p = new Candidate(77, Map.of(), 1, true, Set.of(), false);

        assertEquals(p.pos(), c.pos());
    }

    @Test
    void handsSayWhatMeteorChecksBeforeSwitchingAndAttacking() {
        CrystalTick.Hands weak = new CrystalTick.Hands(true, false, true, true, true, 0, 1, false, true, false);

        assertTrue(weak.weakened());
        assertTrue(weak.strengthened());
        assertEquals(0, weak.weaknessAmplifier());
        assertEquals(1, weak.strengthAmplifier());
        assertTrue(weak.offhandCrystals());
        assertTrue(weak.gappleInHand());
        assertTrue(weak.bowInHand());
        assertFalse(weak.totemInHand());
        assertFalse(HANDS.weakened());
        assertFalse(HANDS.strengthened());
        assertFalse(HANDS.totemInHand());
        assertTrue(new CrystalTick.Hands(true, true, false, false, false,
            CrystalTick.Hands.NO_EFFECT, CrystalTick.Hands.NO_EFFECT, true, true, true).totemInHand());
        assertEquals(HANDS, tick(List.of(), List.of(), List.of()).hands());
    }

    @Test
    void handsRefuseWhatCannotBe() {
        int none = CrystalTick.Hands.NO_EFFECT;
        assertThrows(IllegalArgumentException.class, () -> new CrystalTick.Hands(true, false, false, false, false, -2, none, false, false, false));
        assertThrows(IllegalArgumentException.class, () -> new CrystalTick.Hands(true, false, false, false, false, none, -2, false, false, false));
        // testInHotbar tests the hands first, and findInHotbar the main hand
        assertThrows(IllegalArgumentException.class, () -> new CrystalTick.Hands(false, true, false, false, false, none, none, false, false, false));
        assertThrows(IllegalArgumentException.class, () -> new CrystalTick.Hands(false, false, true, false, false, none, none, false, false, false));
        assertThrows(IllegalArgumentException.class, () -> new CrystalTick.Hands(true, false, false, false, false, none, none, true, false, false));
        assertThrows(NullPointerException.class,
            () -> new CrystalTick(10, 20, 0, false, false, false, false, null, List.of(), List.of(), List.of()));
    }

    @Test
    void theRecordsCopyWhatTheyAreGiven() {
        Map<String, Double> damage = new HashMap<>(Map.of("a", 4.0));
        Set<Integer> box = new HashSet<>(Set.of(3));
        CrystalView c = crystal(damage, 1, 3, 0, CrystalView.NEVER);
        Candidate p = new Candidate(7, damage, 1, true, box, false);
        CrystalSeen seen = new CrystalSeen(1, 77, damage, 1, 3, true);

        damage.put("a", 99.0);
        box.add(4);

        assertEquals(4.0, c.targetDamage().get("a"), 0.0);
        assertEquals(4.0, p.targetDamage().get("a"), 0.0);
        assertEquals(4.0, seen.targetDamage().get("a"), 0.0);
        assertEquals(Set.of(3), p.crystalsInBox());
        assertThrows(UnsupportedOperationException.class, () -> c.targetDamage().put("b", 1.0));
    }

    @Test
    void theRecordsRefuseImpossibleValues() {
        Map<String, Double> ok = Map.of("a", 1.0);
        assertThrows(IllegalArgumentException.class, () -> crystal(ok, -0.1, 3, 0, CrystalView.NEVER));
        assertThrows(IllegalArgumentException.class, () -> crystal(ok, Double.NaN, 3, 0, CrystalView.NEVER));
        assertThrows(IllegalArgumentException.class, () -> crystal(Map.of("a", -1.0), 1, 3, 0, CrystalView.NEVER));
        assertThrows(IllegalArgumentException.class, () -> crystal(ok, 1, -1, 0, CrystalView.NEVER));
        assertThrows(IllegalArgumentException.class, () -> crystal(ok, 1, 3, -1, CrystalView.NEVER));
        assertThrows(IllegalArgumentException.class, () -> crystal(ok, 1, 3, 0, 5));
        assertThrows(IllegalArgumentException.class, () -> crystal(ok, 1, 3, 1, -2));
        assertThrows(IllegalArgumentException.class, () -> new Candidate(1, ok, Double.POSITIVE_INFINITY, true, Set.of(), false));
        assertThrows(IllegalArgumentException.class, () -> new CrystalSeen(1, 1, ok, -1, 3, true));
        assertThrows(IllegalArgumentException.class, () -> new CrystalSeen(1, 1, ok, 1, Double.NaN, true));
        assertThrows(IllegalArgumentException.class, () -> new TargetView("a", 9, -1, 50, false, true, false, false, true));
        assertThrows(IllegalArgumentException.class, () -> new TargetView("a", 9, 20, Double.NaN, false, true, false, false, true));
        new TargetView("a", 9, 20, TargetView.NO_ARMOR, false, true, false, false, true);
    }

    @Test
    void aTickRefusesDuplicatesAndImpossibleValues() {
        TargetView t = new TargetView("a", 9, 20, TargetView.NO_ARMOR, false, true, false, false, true);
        Candidate p = new Candidate(7, Map.of(), 1, true, Set.of(), false);
        CrystalSeen one = new CrystalSeen(1, 5, Map.of(), 1, 3, true);
        CrystalSeen sameId = new CrystalSeen(1, 6, Map.of(), 2, 3, true);

        assertThrows(IllegalArgumentException.class, () -> tick(List.of(t, t), List.of(), List.of()));
        assertThrows(IllegalArgumentException.class, () -> tick(List.of(), List.of(), List.of(p, p)));
        assertThrows(IllegalArgumentException.class, () -> tick(List.of(), List.of(one, sameId), List.of()));
        assertThrows(IllegalArgumentException.class,
            () -> new CrystalTick(10, -1, 0, false, false, false, false, HANDS, List.of(), List.of(), List.of()));
        assertThrows(IllegalArgumentException.class,
            () -> new CrystalTick(10, 20, -1, false, false, false, false, HANDS, List.of(), List.of(), List.of()));
    }

    @Test
    void aDecisionNamesWhatItActsOn() {
        assertEquals(Decision.Kind.NONE, Decision.none(Reason.NOTHING_TO_DO).kind());
        assertEquals(42, Decision.breakCrystal(42, Reason.FOREIGN_CRYSTAL).ref());
        assertEquals(1L << 40, Decision.place(1L << 40, Reason.WITHIN_BUDGET).ref());
        assertThrows(IllegalArgumentException.class, () -> new Decision(Decision.Kind.NONE, 3, Reason.NOTHING_TO_DO));
        assertThrows(IllegalArgumentException.class, () -> new Decision(Decision.Kind.BREAK, 1L << 40, Reason.WITHIN_BUDGET));
        assertThrows(NullPointerException.class, () -> Decision.none(null));
        assertEquals(Decision.Kind.SWAP_WEAPON, Decision.swapWeapon(42).kind());
        assertEquals(42, Decision.swapWeapon(42).ref());
        assertEquals(Reason.ANTI_WEAKNESS, Decision.swapWeapon(42).reason());
        assertThrows(IllegalArgumentException.class, () -> new Decision(Decision.Kind.SWAP_WEAPON, 1L << 40, Reason.ANTI_WEAKNESS));
    }
}
