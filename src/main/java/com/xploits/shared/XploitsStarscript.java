package com.xploits.shared;

import meteordevelopment.meteorclient.utils.misc.MeteorStarscript;
import org.meteordev.starscript.value.ValueMap;

/**
 * The single {@code xploits} Starscript root that every subsystem shares.
 *
 * <p>Verified in the {@code starscript-0.2.5} sources ({@code ValueMap#set(String, Supplier)}): a plain
 * name with no dot in it always goes through {@code setRaw}, which unconditionally replaces whatever
 * was stored there. So a subsystem calling {@code MeteorStarscript.ss.set("xploits", ownMap)} directly
 * would silently erase any other subsystem's {@code xploits.*} values registered before it — there is no
 * merge, only the last call wins. This class owns the one {@code ValueMap} that gets set as {@code
 * xploits} in {@link MeteorStarscript#ss}, and every subsystem adds its own child map into it through
 * {@link #addChild} instead of touching {@code MeteorStarscript.ss} itself.
 */
public final class XploitsStarscript {
    private static final ValueMap ROOT = new ValueMap();
    private static boolean registered;

    private XploitsStarscript() {
    }

    /**
     * Adds {@code child} as {@code xploits.<name>}. The shared root is registered into {@code
     * MeteorStarscript.ss} the first time this is called (call order between subsystems does not
     * matter: each just adds its own name into the same root).
     */
    public static synchronized void addChild(String name, ValueMap child) {
        if (!registered) {
            MeteorStarscript.ss.set("xploits", ROOT);
            registered = true;
        }
        ROOT.set(name, child);
    }
}
