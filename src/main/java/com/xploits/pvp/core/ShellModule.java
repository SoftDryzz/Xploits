package com.xploits.pvp.core;

/**
 * Which defence auto-pvp keeps you in (surround++ spec §8): Meteor's {@code surround} and {@code hole-filler}, the default
 * and 0.7.1's behaviour, or Xploits' {@code surround++}, which works out where the crystals that could hurt you would go
 * and fills the opponents' holes itself.
 *
 * <p>As with {@link CrystalModule}, everything below auto-pvp keys on the catalog's name ({@link #LOGICAL}); only the
 * adapter turns it into the real module, through {@link #resolve}. {@link #toString()} is what Meteor saves and a player
 * types, fixed here and never derived from the constant's name.
 */
public enum ShellModule {
    METEOR("meteor", "surround"),
    XPLOITS("xploits++", "surround++");

    /** The catalog's name for the shell, whichever it is. */
    public static final String LOGICAL = "surround";

    private final String saved;
    private final String moduleName;

    ShellModule(String saved, String moduleName) {
        this.saved = saved;
        this.moduleName = moduleName;
    }

    /** The module's name. */
    public String moduleName() {
        return moduleName;
    }

    /** The real module behind a managed module's catalog name: only {@link #LOGICAL} changes. */
    public String resolve(String managedName) {
        return LOGICAL.equals(managedName) ? moduleName : managedName;
    }

    @Override
    public String toString() {
        return saved;
    }
}
