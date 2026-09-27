package com.xploits.bench.core;

import java.util.List;
import java.util.Map;

/**
 * The release rule benchVerify runs on the bench report (R3-9, fix round 1). A release re-measures Meteor
 * ({@code -Pbench.full -Pbench.fresh}): the Meteor cache's key cannot see everything that could change Meteor's
 * numbers, and a release must not rest on one frozen sample of them. So a full run that served any {@code ca-*}
 * scenario from the cache fails, with {@link #CACHED_FULL_RUN}. The everyday and partial runs are not held to it:
 * they are never valid for a release, and benchVerify says so on its own. Pure, over the report as a JSON parser
 * gives it (maps, lists, strings, booleans), so build.gradle.kts can call it without a JSON library of its own.
 */
public final class ReleaseGate {
    /** The line benchVerify prints, and fails on, for a full run with cached Meteor results. */
    public static final String CACHED_FULL_RUN =
        "bench: full run with cached Meteor results — not valid for a release (add -Pbench.fresh)";

    private ReleaseGate() {
    }

    /** The release lines that fail {@code report}: empty when it breaks no release rule. */
    public static List<String> check(Map<String, ?> report) {
        if (!"full".equals(report.get("profile"))) return List.of();
        if (!(report.get("scenarios") instanceof List<?> scenarios)) return List.of();
        for (Object element : scenarios) {
            if (!(element instanceof Map<?, ?> scenario)) continue;
            if (scenario.get("name") instanceof String name && name.startsWith("ca-")
                && Boolean.TRUE.equals(scenario.get("cached"))) {
                return List.of(CACHED_FULL_RUN);
            }
        }
        return List.of();
    }
}
