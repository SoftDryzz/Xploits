package com.xploits.bench.core;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The release rule benchVerify runs on a report (R3-9, fix round 1): a release re-measures Meteor, so a full run
 * that served any {@code ca-*} scenario from the Meteor cache is not valid for one.
 */
class ReleaseGateTest {
    private static Map<String, Object> scenario(String name, Object cached) {
        Map<String, Object> s = new HashMap<>();
        s.put("name", name);
        s.put("status", "DONE");
        if (cached != null) s.put("cached", cached);
        return s;
    }

    private static Map<String, Object> report(String profile, List<Map<String, Object>> scenarios) {
        Map<String, Object> root = new HashMap<>();
        if (profile != null) root.put("profile", profile);
        root.put("scenarios", scenarios);
        return root;
    }

    @Test
    void aFullRunWithACachedMeteorScenarioIsNotValid() {
        List<String> lines = ReleaseGate.check(report("full", List.of(scenario("ca-still", true), scenario("ca-circler", null),
            scenario("ca-defender", true), scenario("capp-still", null))));
        assertEquals(List.of("bench: full run with cached Meteor results — not valid for a release (add -Pbench.fresh)"),
            lines);
    }

    @Test
    void aFullRunThatMeasuredEveryMeteorScenarioIsValid() {
        assertEquals(List.of(), ReleaseGate.check(report("full", List.of(scenario("ca-still", null),
            scenario("ca-circler", false), scenario("capp-still", null)))));
    }

    @Test
    void onlyAFullRunIsHeldToIt() {
        // The everyday and partial runs are never valid for a release anyway, and say so on their own.
        List<Map<String, Object>> cached = List.of(scenario("ca-still", true));
        assertEquals(List.of(), ReleaseGate.check(report("everyday", cached)));
        assertEquals(List.of(), ReleaseGate.check(report("partial", cached)));
    }

    @Test
    void onlyMeteorsScenariosCount() {
        // Only ca-* are ever cached; a cached flag anywhere else is not Meteor's.
        assertEquals(List.of(), ReleaseGate.check(report("full", List.of(scenario("capp-still", true),
            scenario("defense-attacker", true)))));
    }

    @Test
    void aCachedFlagThatIsNotTrueDoesNotCount() {
        assertEquals(List.of(), ReleaseGate.check(report("full", List.of(scenario("ca-still", "true"),
            scenario("ca-circler", 1)))));
    }

    @Test
    void aReportWithoutScenariosOrProfileIsNotThisRulesBusiness() {
        // benchVerify fails a report without scenarios on its own.
        assertEquals(List.of(), ReleaseGate.check(Map.of()));
        assertEquals(List.of(), ReleaseGate.check(report("full", null)));
        Map<String, Object> odd = report("full", null);
        odd.put("scenarios", "not a list");
        assertEquals(List.of(), ReleaseGate.check(odd));
    }
}
