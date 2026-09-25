package com.xploits.pvp.profile.core;

import com.xploits.pvp.core.ManagedModule;
import com.xploits.pvp.core.ManagedModules;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The three profiles the player always has, editable but never truly removable: {@link ProfileBook#delete}
 * resets one of these to its factory values instead of deleting it (design §2).
 *
 * <p>The margins are {@code ±4} around {@link com.xploits.pvp.core.DefensivePolicy#THREAT_MARGIN}'s
 * default of 12: {@link #AGGRESSIVE} accepts a one-crystal-deep threat more before turning defensive;
 * {@link #DEFENSIVE} turns defensive with 4 more points of slack.
 */
public final class BuiltInProfiles {
    private static final Set<String> ALL_MODULES = ManagedModules.ALL.stream()
        .map(ManagedModule::name)
        .collect(Collectors.toUnmodifiableSet());

    public static final PvpProfile BALANCED = new PvpProfile("balanced", 16, 6, 12.0, ALL_MODULES);
    public static final PvpProfile AGGRESSIVE = new PvpProfile("aggressive", 24, 4, 8.0, ALL_MODULES);
    /** Everything but {@code auto-city} and {@code auto-anvil}: the two offensive modules least about staying alive. */
    public static final PvpProfile DEFENSIVE = new PvpProfile("defensive", 12, 6, 16.0, withoutCityAndAnvil());

    /** Fixed order: also the order {@link ProfileBook} keeps them in, and the order {@code Modules} settings show them. */
    public static final List<PvpProfile> ALL = List.of(BALANCED, AGGRESSIVE, DEFENSIVE);

    private static Set<String> withoutCityAndAnvil() {
        Set<String> modules = new LinkedHashSet<>(ALL_MODULES);
        modules.remove(ManagedModules.AUTO_CITY.name());
        modules.remove(ManagedModules.AUTO_ANVIL.name());
        return modules;
    }

    public static boolean isBuiltIn(String name) {
        for (PvpProfile profile : ALL) {
            if (profile.name().equals(name)) return true;
        }
        return false;
    }

    /** The factory values for a built-in name, ignoring anything the player may have saved over it. */
    public static PvpProfile factory(String name) {
        for (PvpProfile profile : ALL) {
            if (profile.name().equals(name)) return profile;
        }
        throw new IllegalArgumentException("not a built-in profile: " + name);
    }

    private BuiltInProfiles() {
    }
}
