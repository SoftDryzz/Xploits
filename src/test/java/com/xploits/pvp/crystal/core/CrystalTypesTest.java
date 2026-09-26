package com.xploits.pvp.crystal.core;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** The plain input and output records: copies, sums and the values they refuse. */
class CrystalTypesTest {
    private static CrystalView crystal(Map<String, Double> damage, double self, double distance, int attempts, long attacked) {
        return new CrystalView(1, damage, self, distance, true, true, attempts, attacked, CrystalView.NEVER);
    }

    @Test
    void targetDamageIsSummedOverTheGivenTargetsOnly() {
        Map<String, Double> damage = new LinkedHashMap<>();
        damage.put("a", 4.0);
        damage.put("b", 2.5);
        damage.put("c", 100.0);
        CrystalView c = crystal(damage, 1, 3, 0, CrystalView.NEVER);
        Candidate p = new Candidate(7, damage, 1, true, Set.of(), false);

        assertEquals(6.5, c.damageTo(List.of("a", "b", "nobody")), 0.0);
        assertEquals(6.5, p.damageTo(List.of("a", "b")), 0.0);
        assertEquals(0.0, p.damageTo(List.of()), 0.0);
    }

    @Test
    void theRecordsCopyWhatTheyAreGiven() {
        Map<String, Double> damage = new HashMap<>(Map.of("a", 4.0));
        Set<Integer> box = new HashSet<>(Set.of(3));
        CrystalView c = crystal(damage, 1, 3, 0, CrystalView.NEVER);
        Candidate p = new Candidate(7, damage, 1, true, box, false);

        damage.put("a", 99.0);
        box.add(4);

        assertEquals(4.0, c.targetDamage().get("a"), 0.0);
        assertEquals(4.0, p.targetDamage().get("a"), 0.0);
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
        assertThrows(IllegalArgumentException.class, () -> new TargetView("a", 3, -1, 50, false, true, false));
        assertThrows(IllegalArgumentException.class, () -> new TargetView("a", 3, 20, Double.NaN, false, true, false));
        new TargetView("a", 3, 20, TargetView.NO_ARMOR, false, true, false);
    }

    @Test
    void aTickRefusesDuplicatesAndTicksFromTheFuture() {
        TargetView t = new TargetView("a", 3, 20, TargetView.NO_ARMOR, false, true, false);
        Candidate p = new Candidate(7, Map.of(), 1, true, Set.of(), false);
        CrystalView future = new CrystalView(1, Map.of(), 1, 3, true, true, 1, 11, CrystalView.NEVER);

        assertThrows(IllegalArgumentException.class,
            () -> new CrystalTick(10, 20, 0, false, false, false, false, List.of(t, t), List.of(), List.of()));
        assertThrows(IllegalArgumentException.class,
            () -> new CrystalTick(10, 20, 0, false, false, false, false, List.of(), List.of(), List.of(p, p)));
        assertThrows(IllegalArgumentException.class,
            () -> new CrystalTick(10, 20, 0, false, false, false, false, List.of(), List.of(future), List.of()));
        assertThrows(IllegalArgumentException.class,
            () -> new CrystalTick(10, -1, 0, false, false, false, false, List.of(), List.of(), List.of()));
        assertThrows(IllegalArgumentException.class,
            () -> new CrystalTick(10, 20, -1, false, false, false, false, List.of(), List.of(), List.of()));
    }

    @Test
    void aDecisionNamesWhatItActsOn() {
        assertEquals(Decision.Kind.NONE, Decision.none(Reason.NOTHING_TO_DO).kind());
        assertEquals(42, Decision.breakCrystal(42, Reason.FOREIGN_CRYSTAL).ref());
        assertEquals(1L << 40, Decision.place(1L << 40, Reason.WITHIN_BUDGET).ref());
        assertThrows(IllegalArgumentException.class, () -> new Decision(Decision.Kind.NONE, 3, Reason.NOTHING_TO_DO));
        assertThrows(IllegalArgumentException.class, () -> new Decision(Decision.Kind.BREAK, 1L << 40, Reason.WITHIN_BUDGET));
        assertThrows(NullPointerException.class, () -> Decision.none(null));
    }
}
