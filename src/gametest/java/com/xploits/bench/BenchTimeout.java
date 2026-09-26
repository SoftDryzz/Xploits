package com.xploits.bench;

/** A run waited past its scenario's {@link Scenario#budgetTicks()}: ERROR. */
public final class BenchTimeout extends BenchException {
    public BenchTimeout(int budget) {
        super("the run went past its budget of " + budget + " ticks");
    }
}
