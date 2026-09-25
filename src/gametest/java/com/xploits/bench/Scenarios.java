package com.xploits.bench;

import java.util.List;

/** Every bench scenario, in the order a full run takes them. */
public final class Scenarios {
    private Scenarios() {
    }

    public static List<Scenario> all() {
        return List.of(new Smoke());
    }
}
