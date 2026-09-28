package com.xploits.bench.core;

/**
 * Task A3 (city): the timer behind {@code SurroundMiner}'s re-mining of one of our hole's four wall blocks
 * (pure, one instance per miner). The wall stands from the start of the fight, so the first mine takes
 * {@value #CITY_FIRST_BREAK} ticks; every later one, timed from the tick the gap was last refilled (by our own
 * side's defence), takes only {@value #CITY_REBREAK} ticks — an "instant" mine next to the first.
 */
public final class MiningSchedule {
    /** Ticks the very first mine takes, counted from the start of the fight. */
    public static final int CITY_FIRST_BREAK = 40;
    /** Ticks every later mine takes, counted from the tick the gap was last refilled. */
    public static final int CITY_REBREAK = 2;

    private boolean everArmed;
    private boolean armed;
    private int dueAt;

    /**
     * Arms the timer from {@code tick}: the very first call ever made on this instance waits
     * {@value #CITY_FIRST_BREAK} ticks; every later call, whether or not an earlier arming ever fired, waits
     * only {@value #CITY_REBREAK}. There is only ever one mine in flight, so arming again before the pending
     * one fires simply replaces it.
     */
    public void arm(int tick) {
        dueAt = tick + (everArmed ? CITY_REBREAK : CITY_FIRST_BREAK);
        armed = true;
        everArmed = true;
    }

    /** Whether the timer is armed and its tick has come; consumes the arming exactly once. */
    public boolean due(int tick) {
        if (!armed || tick < dueAt) return false;
        armed = false;
        return true;
    }

    /** Whether a mine is currently pending: armed, whether or not it has reached its tick yet. */
    public boolean armed() {
        return armed;
    }
}
