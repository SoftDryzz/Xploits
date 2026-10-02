package com.xploits.restock;

import net.fabricmc.loader.api.FabricLoader;

/**
 * litematica-printer's print mode, reached by reflection only (BoundaryTest forbids naming {@code me.aleksilassila}).
 * VERIFIED in the 3.2.1B jar: {@code Configs.PRINT_MODE} is a public static malilib {@code ConfigBoolean}, whose class is
 * public with public {@code getBooleanValue()} and {@code setBooleanValue(boolean)}. Any reflective failure reads as
 * "cannot be read" — never a guess. Mods share Fabric's class loader, so {@code Class.forName} finds its classes.
 */
public final class LitematicaPrinterSwitch implements PrintSwitch {
    public static final String MOD_ID = "litematica_printer";
    private static final String CONFIGS = "me.aleksilassila.litematica.printer.config.Configs";

    /** The class holding {@code PRINT_MODE}: litematica-printer's, or the unit tests' stand-in. */
    private final String configs;

    private LitematicaPrinterSwitch() {
        this(CONFIGS);
    }

    /** For the unit tests (deferred L23): the switch over another class with a {@code PRINT_MODE} of the same shape. */
    LitematicaPrinterSwitch(String configs) {
        this.configs = configs;
    }

    /** The real switch when litematica-printer is installed, otherwise {@link PrintSwitch#NONE}. */
    public static PrintSwitch find() {
        return FabricLoader.getInstance().isModLoaded(MOD_ID) ? new LitematicaPrinterSwitch() : PrintSwitch.NONE;
    }

    @Override
    public boolean installed() {
        return true;
    }

    @Override
    public Boolean printing() {
        try {
            Object option = option();
            return (Boolean) option.getClass().getMethod("getBooleanValue").invoke(option);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            return null;
        }
    }

    /**
     * True only when the print mode now is {@code on} (deferred L16): malilib stores the value before its change callback
     * runs, so a callback that throws does not mean the value did not change, and a setter that returns quietly does not
     * prove that it did.
     */
    @Override
    public boolean set(boolean on) {
        try {
            Object option = option();
            option.getClass().getMethod("setBooleanValue", boolean.class).invoke(option, on);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            // What counts is the value it holds now, read below.
        }
        return Boolean.valueOf(on).equals(printing());
    }

    private Object option() throws ReflectiveOperationException {
        return Class.forName(configs).getField("PRINT_MODE").get(null);
    }
}
