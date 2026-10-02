package me.aleksilassila.litematica.printer.config;

/**
 * A stand-in for litematica-printer's {@code Configs}, on the unit tests' classpath only (deferred L16/L23): restock's
 * {@code LitematicaPrinterSwitch} reaches the real one by reflection, by this exact class name and field, so its reading,
 * setting and failure paths can be tested without the mod. The real {@code PRINT_MODE} is a malilib
 * {@code ConfigBoolean} with public {@code getBooleanValue()} and {@code setBooleanValue(boolean)}; this one is not
 * final, so each test sets the option it needs. BoundaryTest scans src/main only.
 */
public final class Configs {
    public static Option PRINT_MODE = new Option();

    private Configs() {
    }

    /** The print mode's option, with the two ways a real one can disappoint. */
    public static final class Option {
        public boolean value;
        /** A setter that does not take: the value stays as it was. */
        public boolean stuck;
        /** A change callback that throws after the value was stored, as malilib's {@code onValueChanged} may. */
        public boolean throwAfterSet;

        public boolean getBooleanValue() {
            return value;
        }

        public void setBooleanValue(boolean value) {
            if (!stuck) this.value = value;
            if (throwAfterSet) throw new IllegalStateException("a change callback failed");
        }
    }
}
