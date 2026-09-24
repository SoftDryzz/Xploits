package com.xploits.console.core;

import com.xploits.shared.core.i18n.Catalog;

/** Which messages the log pane shows, by menu option. */
public enum LogFilter {
    ALL(WindowText.FILTER_ALL),
    PVP(WindowText.FILTER_PVP),
    TRAVEL(WindowText.FILTER_TRAVEL),
    SWEEP(WindowText.FILTER_SWEEP),
    WARNINGS(WindowText.FILTER_WARNINGS);

    private final WindowText label;

    LogFilter(WindowText label) {
        this.label = label;
    }

    public String label(Catalog texts) {
        return texts.render(label);
    }

    public boolean accepts(LogEntry.Message m) {
        return switch (this) {
            case ALL -> true;
            case PVP -> m.source().equals("auto-pvp");
            case TRAVEL -> m.source().equals("auto-travel");
            case SWEEP -> m.source().equals("nether-sweep");
            case WARNINGS -> m.level() != Level.INFO;
        };
    }
}
