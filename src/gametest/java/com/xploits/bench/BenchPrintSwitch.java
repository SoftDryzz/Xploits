package com.xploits.bench;

import com.xploits.restock.PrintSwitch;

import java.util.ArrayList;
import java.util.List;

/**
 * litematica-printer's print mode as a fake flag (restock spec §6 "printer pause/resume observed through a fake
 * print-mode flag"): restock switches it as it would the real one, and every switch is kept, in order. Client thread.
 */
final class BenchPrintSwitch implements PrintSwitch {
    private boolean printing;
    private final List<Boolean> history = new ArrayList<>();

    BenchPrintSwitch(boolean printing) {
        this.printing = printing;
    }

    @Override
    public boolean installed() {
        return true;
    }

    @Override
    public Boolean printing() {
        return printing;
    }

    @Override
    public boolean set(boolean on) {
        printing = on;
        history.add(on);
        return true;
    }

    /** Every value restock set, in order. */
    List<Boolean> history() {
        return List.copyOf(history);
    }
}
