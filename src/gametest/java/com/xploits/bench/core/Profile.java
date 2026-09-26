package com.xploits.bench.core;

import com.xploits.pvp.crystal.core.RiskLevel;

import java.util.List;

/**
 * Which scenarios a bench run plays (R3-9). The EVERYDAY run, {@code ./gradlew runClientGameTest}, plays every
 * CHECK, every Meteor MEASURE ({@code ca-*}, which the Meteor cache may serve) and the other MEASUREs with no
 * crystal-aura++ level, and crystal-aura++'s MEASUREs at Balanced only: Safe and Aggressive are experimental,
 * so their {@code capp-*} and {@code capp-aggressive-*} MEASUREs are skipped, and listed as skipped. The FULL
 * run, {@code -Pbench.full}, plays everything, and is the only one valid for a release. A PARTIAL run,
 * {@code -Pbench.only}, plays exactly the scenarios it names, whichever of the other two was asked for. Pure.
 */
public enum Profile {
    EVERYDAY("everyday"),
    FULL("full"),
    PARTIAL("partial");

    /** The one crystal-aura++ level the everyday run measures. */
    public static final RiskLevel EVERYDAY_LEVEL = RiskLevel.BALANCED;
    /** The levels the bench measures ({@link RiskTable#LEVELS}); Custom is never benched. */
    private static final List<RiskLevel> BENCHED = RiskTable.LEVELS;

    private final String id;

    Profile(String id) {
        this.id = id;
    }

    /** {@code full}: {@code -Pbench.full}; {@code only}: {@code -Pbench.only} named scenarios, which wins. */
    public static Profile of(boolean full, boolean only) {
        if (only) return PARTIAL;
        return full ? FULL : EVERYDAY;
    }

    /**
     * Whether this run plays a scenario.
     *
     * @param measure whether it is a MEASURE (a CHECK is always played)
     * @param risk    the level crystal-aura++ runs at in it, or null when it is not crystal-aura++'s
     */
    public boolean plays(boolean measure, RiskLevel risk) {
        if (this != EVERYDAY || !measure || risk == null) return true;
        return risk == EVERYDAY_LEVEL;
    }

    /** The benched levels this run does not measure, in their order: Safe and Aggressive in the everyday run. */
    public List<RiskLevel> notMeasured() {
        if (this != EVERYDAY) return List.of();
        return BENCHED.stream().filter(level -> level != EVERYDAY_LEVEL).toList();
    }

    /** {@code everyday}, {@code full} or {@code partial}: the report's {@code profile} field. */
    public String id() {
        return id;
    }

    /** {@code everyday run}, {@code full run} or {@code partial run}: how the summary line names it. */
    public String label() {
        return id + " run";
    }

    /** Why a scenario this run does not play is SKIPPED, as the report says it. */
    public String skipped() {
        return "not in the " + label() + " (use -Pbench.full)";
    }
}
